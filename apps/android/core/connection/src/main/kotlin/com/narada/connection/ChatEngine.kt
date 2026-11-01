package com.narada.connection

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

fun chatRequestHash(localId: String, peerId: String, attempt: String, id: String, text: String, attachment: AttachmentSecret? = null): String {
    val values = listOf(if (attachment == null) "NARADA-ANDROID-CHAT-SEND-1" else "NARADA-ANDROID-CHAT-ATTACHMENT-1", localId, peerId, attempt, id, text).map(::JsonPrimitive)
    return sha256(Wire.encode(JsonArray(if (attachment == null) values else values + Wire.json.encodeToJsonElement(AttachmentSecret.serializer(), attachment))))
}

/** No mutable crypto handles: every candidate account/session is committed with the envelope or
 * inbox/receipt in ONE sealed aggregate mutation. A failed write cannot release send/ACK authority. */
class ChatEngine(private val repository: TrustRepository, private val crypto: OlmCryptography, private val age: AgeCryptography = RustAgeCryptography(), private val now: () -> Long = System::currentTimeMillis) {
    private fun state(doc: TrustDocument) = doc.chat ?: fail("CHAT_NOT_ENABLED")
    private fun ticket(doc: TrustDocument, peerId: String): ChatTicket {
        val s = state(doc); val b = s.binding(peerId); ChatRules.authority(doc, peerId, b.pairingAttempt)
        return ChatTicket(peerId, b.pairingAttempt, s.enrollmentId, b.ours)
    }
    private fun owned(doc: TrustDocument, t: ChatTicket): ChatState {
        ChatRules.authority(doc, t.peerId, t.pairingAttempt); val s = state(doc); val b = s.binding(t.peerId)
        demand(s.enrollmentId == t.enrollmentId && b.pairingAttempt == t.pairingAttempt && b.ours == t.ours, "CHAT_SCOPE_CHANGED"); return s
    }
    private fun bound(doc: TrustDocument, t: ChatTicket, remote: ChatBundle): ChatState {
        val s = owned(doc, t); val b = s.binding(t.peerId)
        crypto.verify(remote, doc.deviceId, t.peerId)
        b.remote?.let { demand(it.curve == remote.curve && it.ed == remote.ed, "CHAT_KEY_CHANGED") }
        return s.replace(b.copy(remote = remote))
    }
    suspend fun enablePeer(peerId: String, confirmed: Boolean, guard: () -> Unit = {}) {
        demand(confirmed, "LOCAL_CONFIRMATION_REQUIRED")
        repository.mutate { doc ->
            guard(); val peer = doc.peer(peerId); ChatRules.authority(doc, peerId, peer.attemptId); crypto.info()
            var s = doc.chat ?: crypto.create().let { ChatState(enrollmentId = UUID.randomUUID().toString(), account = it.account, curve = it.curve, ed = it.ed) }
            val public = crypto.inspect(s.account); demand(public.curve == s.curve && public.ed == s.ed, "CRYPTO_STATE_INVALID")
            val existing = s.bindings.singleOrNull { it.peerId == peerId }
            if (existing == null) {
                demand(s.bindings.size < ChatRules.MAX_PEERS, "CHAT_PEER_LIMIT")
                val created = crypto.bundle(s.account, doc.deviceId, peerId)
                created.bundle.validate(doc.deviceId, peerId)
                demand(created.bundle.curve == s.curve && created.bundle.ed == s.ed, "CRYPTO_STATE_INVALID")
                s = s.copy(account = created.account, bindings = s.bindings + ChatBinding(peerId, peer.attemptId, created.bundle))
            } else if (existing.pairingAttempt != peer.attemptId) {
                // Reviewed renewal retains identity/session/history; old-scope messages never auto-replay.
                s = s.replace(existing.copy(pairingAttempt = peer.attemptId)).copy(drafts = s.drafts.map { if (it.peerId == peerId) it.copy(pairingAttempt = peer.attemptId) else it })
            }
            guard(); doc.copy(chat = s)
        }
    }
    suspend fun saveDraft(peerId: String, text: String, guard: () -> Unit = {}) {
        ChatRules.text(text, true)
        repository.mutate { doc ->
            guard(); val s = state(doc); val b = s.binding(peerId)
            // Local encrypted draft access is distinct from new network permission.
            demand(doc.peer(peerId).attemptId == b.pairingAttempt, "CHAT_PAIRING_CHANGED")
            doc.copy(chat = s.copy(drafts = s.drafts.filterNot { it.peerId == peerId } + ChatDraft(peerId, b.pairingAttempt, text)))
        }
    }
    suspend fun queue(peerId: String, id: String, text: String, guard: () -> Unit = {}): ChatMessage {
        canonicalUuid(id); ChatRules.text(text)
        val after = repository.mutate { doc ->
            guard(); val t = ticket(doc, peerId); val s = owned(doc, t); val hash = chatRequestHash(doc.deviceId, peerId, t.pairingAttempt, id, text)
            val old = s.messages.singleOrNull { it.id == id }
            if (old != null) {
                demand(old.direction == ChatDirection.OUTGOING && old.peerId == peerId && old.pairingAttempt == t.pairingAttempt && old.requestHash == hash && old.text == text, "MESSAGE_ID_CONFLICT")
                doc
            } else {
                demand(s.messages.size < ChatRules.MAX_MESSAGES, "CHAT_HISTORY_FULL")
                val m = ChatMessage(id, peerId, t.pairingAttempt, ChatDirection.OUTGOING, text, now(), ChatStatus.QUEUED, hash)
                doc.copy(chat = s.copy(messages = s.messages + m, drafts = s.drafts.filterNot { it.peerId == peerId }))
            }
        }
        return state(after).message(id)
    }
    /** Call on process recovery / after joining a cancelled worker. No network or new encryption. */
    suspend fun recover() {
        repository.mutate { doc ->
            val s = doc.chat ?: return@mutate doc
            doc.copy(chat = s.copy(messages = s.messages.map { m -> if (m.runId == null) m else m.copy(runId = null, error = "CHAT_INTERRUPTED") }))
        }
    }
    suspend fun retry(id: String, reviewed: Boolean) {
        demand(reviewed, "LOCAL_CONFIRMATION_REQUIRED")
        repository.mutate { doc ->
            val s = state(doc); val m = s.message(id); val t = ticket(doc, m.peerId); owned(doc, t)
            demand(m.pairingAttempt == t.pairingAttempt, "CHAT_PAIRING_CHANGED")
            demand(m.direction == ChatDirection.OUTGOING && m.status != ChatStatus.DELIVERED && m.runId == null, "CHAT_STOPPED")
            doc.copy(chat = s.replace(m.copy(status = if (m.envelope == null) ChatStatus.QUEUED else ChatStatus.READY, attempts = 0, retryAfter = 0, error = null)))
        }
    }
    suspend fun cancel(id: String, reviewed: Boolean) {
        demand(reviewed, "LOCAL_CONFIRMATION_REQUIRED")
        repository.mutate { doc ->
            val s = state(doc); val m = s.message(id)
            demand(m.direction == ChatDirection.OUTGOING && m.status != ChatStatus.DELIVERED && m.runId == null, "CHAT_STOPPED")
            doc.copy(chat = s.replace(m.copy(status = if (m.envelope == null) ChatStatus.CANCELLED_NOT_SENT else ChatStatus.CANCELLED_UNCONFIRMED, error = null)))
        }
    }
    suspend fun markRead(peerId: String) {
        repository.mutate { doc -> val s = state(doc); doc.copy(chat = s.copy(messages = s.messages.map { if (it.peerId == peerId && it.direction == ChatDirection.INCOMING) it.copy(unread = false) else it })) }
    }
    private suspend fun acceptBundle(t: ChatTicket, reply: ChatResult<ChatBundle>, guard: () -> Unit) {
        repository.mutate { doc -> guard(); reply.scope.check(doc); val s = bound(doc, t, reply.value); guard(); doc.copy(chat = s) }
    }
    private suspend fun prepare(t: ChatTicket, id: String, scope: ChatScope, guard: () -> Unit): ChatDelivery {
        val run = UUID.randomUUID().toString()
        val after = repository.mutate { doc ->
            guard(); scope.check(doc); var s = owned(doc, t); val b = s.binding(t.peerId); val m = s.message(id)
            demand(m.peerId == t.peerId && m.pairingAttempt == t.pairingAttempt, "CHAT_PAIRING_CHANGED")
            demand(m.direction == ChatDirection.OUTGOING && m.status in setOf(ChatStatus.QUEUED, ChatStatus.READY, ChatStatus.UNCERTAIN) && m.runId == null && m.attempts < 3 && m.retryAfter <= now(), "MESSAGE_RETRY_LIMIT")
            m.attachment?.let { secret ->
                AttachmentRules.authority(doc, m.peerId, m.pairingAttempt); age.validateIdentity(secret)
                demand(doc.attachments.any { it.messageId == m.id && it.available && it.secret == secret && it.direction == ChatDirection.OUTGOING }, "ATTACHMENT_NOT_VERIFIED")
            }
            var e = m.envelope
            if (e == null) {
                val encrypted = crypto.encrypt(s.account, b.ours, b.remote ?: fail("CHAT_KEY_MISSING"), b.sessions.firstOrNull(), ChatBody(message_id = m.id, sender = doc.deviceId, recipient = m.peerId, text = m.text, attachment = m.attachment))
                encrypted.session.validate(); encrypted.envelope.validate()
                demand(encrypted.envelope.message_id == m.id && encrypted.envelope.sender == doc.deviceId && encrypted.envelope.recipient == m.peerId && encrypted.envelope.session_id == encrypted.session.id, "CRYPTO_STATE_INVALID")
                val sessions = if (b.sessions.isEmpty()) listOf(encrypted.session) else listOf(encrypted.session) + b.sessions.drop(1)
                if (b.sessions.isNotEmpty()) demand(encrypted.session.id == b.sessions.first().id, "CRYPTO_STATE_INVALID")
                s = s.replace(b.copy(sessions = sessions)); e = encrypted.envelope
            }
            guard(); doc.copy(chat = s.replace(m.copy(envelope = e, digest = e.digest(), status = ChatStatus.READY, runId = run, error = null)))
        }
        val saved = state(after).message(id); return ChatDelivery(id, run, saved.envelope, null)
    }
    private suspend fun interrupted(delivery: ChatDelivery, code: String) = withContext(NonCancellable) {
        repository.mutate { doc ->
            val s = state(doc); val m = s.message(delivery.id)
            if (m.runId != delivery.runId) doc else doc.copy(chat = s.replace(m.copy(runId = null, error = code)))
        }
    }
    private suspend fun send(t: ChatTicket, id: String, bundleScope: ChatScope, gateway: ChatGateway, guard: () -> Unit) {
        val delivery = prepare(t, id, bundleScope, guard)
        try {
            repository.mutate { doc ->
                guard(); bundleScope.check(doc); val s = owned(doc, t); val m = s.message(id)
                demand(m.runId == delivery.runId && m.envelope == delivery.envelope && m.status == ChatStatus.READY && m.attempts < 3, "CHAT_STOPPED")
                doc.copy(chat = s.replace(m.copy(status = ChatStatus.UNCERTAIN, attempts = m.attempts + 1, retryAfter = now() + 4000)))
            }
            val reply = gateway.push(t, delivery)
            repository.mutate { doc ->
                guard(); reply.scope.check(doc); val s = owned(doc, t); val m = s.message(id)
                demand(m.runId == delivery.runId && m.envelope == delivery.envelope && m.status == ChatStatus.UNCERTAIN, "CHAT_STOPPED")
                reply.value.matches(m.envelope ?: fail("CRYPTO_STATE_INVALID"))
                m.receipt?.let { demand(it == reply.value, "INVALID_RECEIPT") }
                guard(); doc.copy(chat = s.replace(m.copy(receipt = reply.value, status = ChatStatus.DELIVERED, acknowledged = true, runId = null, error = null)))
            }
        } catch (e: Exception) { interrupted(delivery, if (e is CancellationException) "CHAT_INTERRUPTED" else (e as? ConnectionFailure)?.code ?: "CHAT_UNAVAILABLE"); throw e }
    }
    private suspend fun receive(t: ChatTicket, reply: ChatResult<ChatPullReply>, guard: () -> Unit): String? {
        val envelope = reply.value.envelope
        val after = repository.mutate { doc ->
            guard(); reply.scope.check(doc); var s = bound(doc, t, reply.value.bundle)
            if (envelope == null) return@mutate doc.copy(chat = s)
            envelope.validate(); demand(envelope.sender == t.peerId && envelope.recipient == doc.deviceId, "INVALID_MESSAGE")
            val digest = envelope.digest(); val old = s.messages.singleOrNull { it.id == envelope.message_id }
            if (old != null) {
                demand(old.direction == ChatDirection.INCOMING && old.peerId == t.peerId && old.digest == digest && old.receipt != null, "MESSAGE_ID_CONFLICT")
                demand(old.pairingAttempt == t.pairingAttempt, "CHAT_PAIRING_CHANGED")
                return@mutate doc.copy(chat = s) // Exact replay returns the saved receipt; never decrypts/advances twice.
            }
            demand(s.messages.size < ChatRules.MAX_MESSAGES, "CHAT_HISTORY_FULL")
            val b = s.binding(t.peerId)
            val decrypted = crypto.decrypt(s.account, b.ours, b.remote ?: fail("CHAT_KEY_MISSING"), b.sessions, envelope)
            ChatRules.text(decrypted.text, decrypted.attachment != null); decrypted.session.validate()
            decrypted.attachment?.let { a ->
                AttachmentRules.authority(doc, t.peerId, t.pairingAttempt); age.validateIdentity(a)
                demand(s.messages.none { it.attachment?.descriptor?.blob_id == a.descriptor.blob_id }, "BLOB_ID_CONFLICT")
            }
            demand(decrypted.session.id == envelope.session_id, "CRYPTO_STATE_INVALID")
            val public = crypto.inspect(decrypted.account); demand(public.curve == s.curve && public.ed == s.ed, "CRYPTO_STATE_INVALID")
            val oldIndex = b.sessions.indexOfFirst { it.id == decrypted.session.id }
            demand(oldIndex >= 0 || b.sessions.size < 2, "CHAT_SESSION_LIMIT")
            val sessions = if (oldIndex >= 0) b.sessions.map { if (it.id == decrypted.session.id) decrypted.session else it } else b.sessions + decrypted.session
            s = s.copy(account = decrypted.account).replace(b.copy(sessions = sessions))
            val receipt = ChatReceipt(envelope.message_id, t.peerId, doc.deviceId, digest, committed_at = Instant.ofEpochMilli(now()).toString())
            val m = ChatMessage(envelope.message_id, t.peerId, t.pairingAttempt, ChatDirection.INCOMING, decrypted.text, now(), ChatStatus.DELIVERED, digest, digest = digest, receipt = receipt, unread = true, attachment = decrypted.attachment)
            guard(); doc.copy(chat = s.copy(messages = s.messages + m))
        }
        return envelope?.let { state(after).message(it.message_id).id }
    }
    private suspend fun acknowledge(t: ChatTicket, id: String, gateway: ChatGateway, guard: () -> Unit) {
        val run = UUID.randomUUID().toString()
        val after = repository.mutate { doc ->
            guard(); val s = owned(doc, t); val m = s.message(id)
            demand(m.peerId == t.peerId && m.pairingAttempt == t.pairingAttempt && m.direction == ChatDirection.INCOMING && m.receipt != null && m.runId == null, "CHAT_STOPPED")
            doc.copy(chat = s.replace(m.copy(runId = run)))
        }
        val delivery = ChatDelivery(id, run, null, state(after).message(id).receipt)
        try {
            val reply = gateway.ack(t, delivery)
            repository.mutate { doc ->
                guard(); reply.scope.check(doc); val s = owned(doc, t); val m = s.message(id)
                demand(m.runId == run && m.receipt == delivery.receipt, "CHAT_STOPPED")
                doc.copy(chat = s.replace(m.copy(acknowledged = true, runId = null, error = null)))
            }
        } catch (e: Exception) { interrupted(delivery, if (e is CancellationException) "CHAT_INTERRUPTED" else (e as? ConnectionFailure)?.code ?: "CHAT_UNAVAILABLE"); throw e }
    }
    /** Explicit foreground batch: at most 3 old ACKs, 3 outgoing messages and 3 pulls.
     * No timer/service/restart replay; another batch or retry needs an explicit user action. */
    suspend fun sync(peerId: String, gateway: ChatGateway, guard: () -> Unit = {}) {
        currentCoroutineContext().ensureActive(); guard()
        val doc = repository.read() ?: fail("IDENTITY_NOT_READY"); val t = ticket(doc, peerId)
        val remote = gateway.bundle(t); acceptBundle(t, remote, guard)
        state(repository.read() ?: fail("IDENTITY_NOT_READY")).messages.filter { it.peerId == peerId && it.pairingAttempt == t.pairingAttempt && it.direction == ChatDirection.INCOMING && !it.acknowledged }.take(3).forEach { acknowledge(t, it.id, gateway, guard) }
        state(repository.read() ?: fail("IDENTITY_NOT_READY")).messages.filter { it.peerId == peerId && it.pairingAttempt == t.pairingAttempt && it.direction == ChatDirection.OUTGOING && it.status in setOf(ChatStatus.QUEUED, ChatStatus.READY, ChatStatus.UNCERTAIN) && it.attempts < 3 && it.retryAfter <= now() }.take(3).forEach { send(t, it.id, remote.scope, gateway, guard) }
        repeat(3) {
            currentCoroutineContext().ensureActive(); guard()
            val reply = gateway.pull(t); val id = receive(t, reply, guard) ?: return
            acknowledge(t, id, gateway, guard)
        }
    }
}
