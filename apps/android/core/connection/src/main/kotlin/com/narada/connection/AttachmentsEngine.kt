package com.narada.connection

import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.*

/** Sealed intents precede provider/network work. The only resumable artifact is an already complete,
 * immutable age ciphertext. Interrupted encryption/import never resumes a nonce or reuses a key. */
class AttachmentsEngine(
    private val repository: TrustRepository, private val crypto: AgeCryptography, private val storage: TransferChunks,
    private val gateway: AttachmentsGateway, private val now: () -> Long = System::currentTimeMillis,
    private val changed: suspend () -> Unit = {},
) {
    private fun quota(doc: TrustDocument, extra: Long) {
        demand(doc.attachments.size < AttachmentRules.MAX_JOBS && doc.attachments.sumOf { it.reservedBytes } + extra <= AttachmentRules.QUOTA && doc.downloads.sumOf { it.size } + doc.attachments.sumOf { it.reservedBytes } + extra <= TransferRules.QUOTA, "ATTACHMENT_LOCAL_QUOTA")
    }
    private suspend fun record(id: String): Pair<TrustDocument, AttachmentRecord> {
        val doc = repository.read() ?: fail("IDENTITY_NOT_READY"); return doc to doc.attachment(id)
    }
    suspend fun prepareImport(peerId: String, text: String, confirmed: Boolean, guard: () -> Unit = {}): AttachmentRecord {
        demand(confirmed, "LOCAL_CONFIRMATION_REQUIRED"); ChatRules.text(text, true); demand(text.toByteArray().size <= 1024, "ATTACHMENT_CAPTION_LIMIT"); crypto.info()
        val id = UUID.randomUUID().toString()
        val doc = repository.mutate { d ->
            guard(); val p = d.peer(peerId); AttachmentRules.authority(d, peerId, p.attemptId)
            demand(d.chat?.binding(peerId)?.pairingAttempt == p.attemptId, "CHAT_NOT_ENABLED"); quota(d, AttachmentRules.MAX_CIPHER.toLong())
            d.copy(version = maxOf(d.version, 5), attachments = d.attachments + AttachmentRecord(id, UUID.randomUUID().toString(), peerId, p.attemptId, ChatDirection.OUTGOING, now(), text, AttachmentState.PICKER_PENDING))
        }
        try {
            val reply = gateway.capabilities(doc.attachment(id)); reply.value.validate()
            repository.mutate { d -> guard(); reply.scope.check(d); demand(d.attachment(id).state == AttachmentState.PICKER_PENDING, "ATTACHMENT_STOPPED"); d.replaceAttachment(d.attachment(id).copy(uploadVersion = 1)) }
        } catch (e: Exception) { withContext(NonCancellable) { interrupted(id, (e as? ConnectionFailure)?.code ?: "ATTACHMENT_INTERRUPTED") }; throw e }
        changed(); return record(id).second
    }
    suspend fun pickerCancelled(id: String) {
        repository.mutate { d -> val r = d.attachment(id); demand(r.state == AttachmentState.PICKER_PENDING, "ATTACHMENT_STOPPED"); d.replaceAttachment(r.copy(state = AttachmentState.CANCELLED, error = "IMPORT_PICKER_CANCELLED")) }; changed()
    }
    /** Persist the selected read-only URI before opening it. It is not a reusable automatic import. */
    suspend fun admitImport(id: String, uri: String, guard: () -> Unit = {}) {
        TransferRules.uri(uri)
        repository.mutate { d ->
            guard(); val r = d.attachment(id); AttachmentRules.authority(d, r.peerId, r.pairingAttempt)
            demand(r.state == AttachmentState.PICKER_PENDING && r.uploadVersion == 1, "ATTACHMENT_STOPPED")
            d.replaceAttachment(r.copy(state = AttachmentState.IMPORTING, sourceUri = uri))
        }; changed()
    }
    suspend fun import(id: String, name: String, input: InputStream, guard: () -> Unit = {}): AttachmentRecord {
        val buffer = ByteArray(AttachmentRules.MAX_PLAIN + 1)
        var cipher: ByteArray? = null
        try {
            AttachmentRules.name(name)
            val initial = record(id).second; demand(initial.state == AttachmentState.IMPORTING && initial.sourceUri != null, "ATTACHMENT_STOPPED")
            var used = 0
            input.use { stream -> while (true) {
                currentCoroutineContext().ensureActive(); guard()
                val n = stream.read(buffer, used, minOf(8192, buffer.size - used)); if (n < 0) break
                demand(n > 0 && used + n <= AttachmentRules.MAX_PLAIN, "ATTACHMENT_LIMIT"); used += n
            } }
            guard(); val plain = buffer.copyOf(used)
            val encrypted = try { crypto.encrypt(name, plain) } finally { plain.fill(0) }; cipher = encrypted.ciphertext
            repository.mutate { d ->
                guard(); val r = d.attachment(id); AttachmentRules.authority(d, r.peerId, r.pairingAttempt)
                demand(r == initial, "ATTACHMENT_STOPPED")
                d.replaceAttachment(r.copy(state = AttachmentState.STAGING, sourceUri = null, secret = encrypted.secret))
            }
            var (doc, r) = record(id); storage.reserve(doc.storeId, r.ciphertextRecord())
            for (i in encrypted.secret.descriptor.hashes.indices) {
                currentCoroutineContext().ensureActive(); guard()
                val bytes = encrypted.ciphertext.copyOfRange(i * TransferRules.CHUNK, i * TransferRules.CHUNK + encrypted.secret.descriptor.length(i))
                try { storage.write(doc.storeId, r.ciphertextRecord(), i, bytes) } finally { bytes.fill(0) }
                val updated = repository.mutate { d -> guard(); val current = d.attachment(id); demand(current.state == AttachmentState.STAGING && current.secret == r.secret, "ATTACHMENT_STOPPED"); d.replaceAttachment(current.copy(chunks = current.chunks + i)) }
                doc = updated; r = doc.attachment(id)
            }
            verifiedPlain(id, requireReceipt = false, guard = guard).fill(0)
            val complete = repository.mutate { d ->
                guard(); val current = d.attachment(id); demand(current.state == AttachmentState.STAGING && current.secret == encrypted.secret, "ATTACHMENT_STOPPED")
                val saved = current.copy(state = AttachmentState.VERIFIED, receipt = receipt(current), error = null)
                queueMessage(d.replaceAttachment(saved), saved)
            }
            changed(); return complete.attachment(id)
        } catch (e: Exception) {
            withContext(NonCancellable) { interrupted(id, (e as? ConnectionFailure)?.code ?: "IMPORT_INTERRUPTED") }; throw e
        } finally { buffer.fill(0); cipher?.fill(0); runCatching { input.close() } }
    }
    private fun receipt(r: AttachmentRecord): AttachmentReceipt {
        val s = r.secret ?: fail("INVALID_ATTACHMENT")
        return r.receipt ?: AttachmentReceipt(s.descriptor.sha256, s.plaintext_sha256, s.plaintext_size, now())
    }
    private fun queueMessage(doc: TrustDocument, r: AttachmentRecord): TrustDocument {
        AttachmentRules.authority(doc, r.peerId, r.pairingAttempt)
        demand(r.direction == ChatDirection.OUTGOING && r.available, "ATTACHMENT_NOT_VERIFIED")
        val secret = r.secret ?: fail("INVALID_ATTACHMENT")
        val chat = doc.chat ?: fail("CHAT_NOT_ENABLED"); demand(chat.binding(r.peerId).pairingAttempt == r.pairingAttempt, "CHAT_PAIRING_CHANGED")
        val hash = chatRequestHash(doc.deviceId, r.peerId, r.pairingAttempt, r.messageId, r.text, r.secret)
        val old = chat.messages.find { it.id == r.messageId }
        if (old != null) { demand(old.requestHash == hash && old.attachment == r.secret && old.direction == ChatDirection.OUTGOING, "MESSAGE_ID_CONFLICT"); return doc }
        demand(chat.messages.size < ChatRules.MAX_MESSAGES && chat.messages.none { it.attachment?.descriptor?.blob_id == secret.descriptor.blob_id }, "CHAT_HISTORY_FULL")
        val message = ChatMessage(r.messageId, r.peerId, r.pairingAttempt, ChatDirection.OUTGOING, r.text, now(), ChatStatus.QUEUED, hash, attachment = r.secret)
        return doc.copy(chat = chat.copy(messages = chat.messages + message))
    }
    suspend fun enqueueReceive(messageId: String, confirmed: Boolean): AttachmentRecord {
        demand(confirmed, "LOCAL_CONFIRMATION_REQUIRED")
        val after = repository.mutate { d ->
            val m = d.chat?.message(messageId) ?: fail("MESSAGE_UNAVAILABLE")
            demand(m.direction == ChatDirection.INCOMING && m.receipt != null, "INVALID_ATTACHMENT")
            val s = m.attachment ?: fail("INVALID_ATTACHMENT"); crypto.validateIdentity(s)
            AttachmentRules.authority(d, m.peerId, m.pairingAttempt)
            demand(d.attachments.none { it.messageId == m.id }, "ATTACHMENT_ALREADY_QUEUED"); quota(d, s.descriptor.size)
            d.copy(version = maxOf(d.version, 5), attachments = d.attachments + AttachmentRecord(UUID.randomUUID().toString(), m.id, m.peerId, m.pairingAttempt, ChatDirection.INCOMING, now(), state = AttachmentState.PAUSED, secret = s))
        }; changed(); return after.attachments.single { it.messageId == messageId }
    }
    suspend fun run(id: String, confirmed: Boolean, guard: () -> Unit = {}): AttachmentRecord {
        demand(confirmed, "LOCAL_CONFIRMATION_REQUIRED"); guard()
        var (doc, r) = record(id)
        demand(r.state in setOf(AttachmentState.PAUSED, AttachmentState.VERIFIED) && r.secret != null, "ATTACHMENT_STOPPED")
        if (r.direction == ChatDirection.OUTGOING && r.receipt == null) {
            // All immutable fragments may have survived a crash after encryption completed. Missing
            // fragments cannot be re-encrypted using an old key: start a new reviewed import instead.
            reconcile(id, null, guard)
            verifiedPlain(id, requireReceipt = false, guard = guard).fill(0)
            repository.mutate { d -> val saved = d.attachment(id).copy(state = AttachmentState.VERIFIED, receipt = receipt(d.attachment(id)), error = null); guard(); queueMessage(d.replaceAttachment(saved), saved) }
            r = record(id).second
        }
        if (r.direction == ChatDirection.OUTGOING) {
            verifiedPlain(id, guard = guard).fill(0)
            repository.mutate { d -> guard(); queueMessage(d, d.attachment(id)) }
            val m = record(id).first.chat?.message(r.messageId) ?: fail("MESSAGE_UNAVAILABLE")
            demand(m.status == ChatStatus.DELIVERED, "ATTACHMENT_MESSAGE_PENDING")
            if (r.remoteStored) return r
        }
        val token = UUID.randomUUID().toString()
        repository.mutate { d ->
            guard(); val current = d.attachment(id); demand(current == r, "ATTACHMENT_STOPPED")
            // Existing complete received bytes may be verified locally without a new network grant.
            if (current.chunks.size != current.secret?.descriptor?.hashes?.size || current.direction == ChatDirection.OUTGOING) AttachmentRules.authority(d, current.peerId, current.pairingAttempt)
            d.replaceAttachment(current.copy(state = AttachmentState.RUNNING, runId = token, error = null))
        }
        try {
            reconcile(id, token, guard); doc = record(id).first; r = doc.attachment(id)
            val secret = r.secret ?: fail("INVALID_ATTACHMENT")
            if (r.direction == ChatDirection.INCOMING) {
                storage.reserve(doc.storeId, r.ciphertextRecord())
                for (i in secret.descriptor.hashes.indices) {
                    r = running(id, token, guard).second
                    if (i in r.chunks) continue
                    val reply = gateway.download(r, i)
                    try {
                        val current = running(id, token, guard).first; reply.scope.check(current)
                        demand(reply.value.size == secret.descriptor.length(i) && sha256(reply.value) == secret.descriptor.hashes[i], "HASH_MISMATCH")
                        storage.write(current.storeId, r.ciphertextRecord(), i, reply.value)
                        update(id, token, guard, reply.scope) { it.copy(chunks = (it.chunks + i).sorted()) }
                    } finally { reply.value.fill(0) }
                }
                verifiedPlain(id, requireReceipt = false, guard = guard).fill(0)
                update(id, token, guard) { it.copy(state = AttachmentState.VERIFIED, runId = null, receipt = receipt(it), error = null) }
            } else {
                val caps = gateway.capabilities(r); caps.value.validate(); caps.scope.check(record(id).first)
                val begin = gateway.begin(r); begin.value.validate(r)
                r = update(id, token, guard, begin.scope) { it.copy(uploaded = secret.descriptor.hashes.indices.filterNot { i -> i in begin.value.missing }) }
                for (i in begin.value.missing) {
                    r = running(id, token, guard).second
                    val bytes = storage.read(doc.storeId, r.ciphertextRecord(), i) ?: fail("LOCAL_COPY_MISSING")
                    try { val reply = gateway.upload(r, i, bytes); update(id, token, guard, reply.scope) { it.copy(uploaded = (it.uploaded + i).distinct().sorted()) } }
                    finally { bytes.fill(0) }
                }
                r = running(id, token, guard).second
                val finish = gateway.finish(r); finish.value.validate(r)
                demand(finish.value.state == "available" && finish.value.missing.isEmpty(), "ATTACHMENT_NOT_VERIFIED")
                update(id, token, guard, finish.scope) { it.copy(state = AttachmentState.VERIFIED, runId = null, uploaded = secret.descriptor.hashes.indices.toList(), remoteStored = true, error = null) }
            }
            changed(); return record(id).second
        } catch (e: Exception) {
            withContext(NonCancellable) { interrupted(id, (e as? ConnectionFailure)?.code ?: "ATTACHMENT_INTERRUPTED") }; throw e
        }
    }
    private suspend fun running(id: String, token: String, guard: () -> Unit): Pair<TrustDocument, AttachmentRecord> {
        currentCoroutineContext().ensureActive(); guard(); return record(id).also { demand(it.second.state == AttachmentState.RUNNING && it.second.runId == token, "ATTACHMENT_STOPPED") }
    }
    private suspend fun update(id: String, token: String, guard: () -> Unit, scope: ChatScope? = null, change: (AttachmentRecord) -> AttachmentRecord): AttachmentRecord {
        currentCoroutineContext().ensureActive(); guard()
        val doc = repository.mutate { d -> guard(); scope?.check(d); val r = d.attachment(id); demand(r.state == AttachmentState.RUNNING && r.runId == token, "ATTACHMENT_STOPPED"); d.replaceAttachment(change(r)) }
        changed(); return doc.attachment(id)
    }
    private suspend fun reconcile(id: String, token: String?, guard: () -> Unit) {
        val (d, r) = record(id); val s = r.secret ?: fail("INVALID_ATTACHMENT"); val found = mutableListOf<Int>()
        for (i in s.descriptor.hashes.indices) {
            currentCoroutineContext().ensureActive(); guard(); val bytes = storage.read(d.storeId, r.ciphertextRecord(), i) ?: continue
            try { demand(bytes.size == s.descriptor.length(i) && sha256(bytes) == s.descriptor.hashes[i], "LOCAL_COPY_CHANGED"); found.add(i) } finally { bytes.fill(0) }
        }
        demand(r.receipt == null || found.size == s.descriptor.hashes.size, "LOCAL_COPY_MISSING")
        repository.mutate { current -> guard(); val old = current.attachment(id); demand(old == r && old.runId == token, "ATTACHMENT_STOPPED"); current.replaceAttachment(old.copy(chunks = found.toList())) }; changed()
    }
    suspend fun verifiedPlain(id: String, requireReceipt: Boolean = true, guard: () -> Unit = {}): ByteArray {
        val (doc, r) = record(id); val s = r.secret ?: fail("INVALID_ATTACHMENT")
        demand(!requireReceipt || r.available, "ATTACHMENT_NOT_VERIFIED")
        val ciphertext = ByteArray(s.descriptor.size.toInt())
        try {
            for (i in s.descriptor.hashes.indices) {
                currentCoroutineContext().ensureActive(); guard(); val bytes = storage.read(doc.storeId, r.ciphertextRecord(), i) ?: fail("LOCAL_COPY_MISSING")
                try { demand(bytes.size == s.descriptor.length(i) && sha256(bytes) == s.descriptor.hashes[i], "LOCAL_COPY_CHANGED"); bytes.copyInto(ciphertext, i * TransferRules.CHUNK) } finally { bytes.fill(0) }
            }
            guard(); return crypto.decrypt(s, ciphertext)
        } catch (e: ConnectionFailure) {
            if (e.code in setOf("HASH_MISMATCH", "LOCAL_COPY_CHANGED", "LOCAL_COPY_MISSING", "AGE_DECRYPT_FAILED")) withContext(NonCancellable) {
                repository.mutate { d -> val current = d.attachment(id); demand(current.secret == s, "ATTACHMENT_STOPPED"); d.replaceAttachment(current.copy(state = AttachmentState.CORRUPT, runId = null, sourceUri = null, error = e.code)) }
            }
            throw e
        } finally { ciphertext.fill(0) }
    }
    suspend fun interrupted(id: String, code: String = "ATTACHMENT_INTERRUPTED") {
        repository.mutate { d -> val r = d.attachment(id)
            if (code in setOf("LOCAL_COPY_CHANGED", "LOCAL_COPY_MISSING", "HASH_MISMATCH", "AGE_DECRYPT_FAILED")) d.replaceAttachment(r.copy(state = AttachmentState.CORRUPT, runId = null, sourceUri = null, error = code))
            else if (r.state in setOf(AttachmentState.CANCELLED, AttachmentState.CORRUPT, AttachmentState.VERIFIED)) d
            else d.replaceAttachment(r.copy(state = AttachmentState.PAUSED, runId = null, sourceUri = null, error = code))
        }; changed()
    }
    suspend fun recover() {
        if (repository.read() == null) return
        repository.mutate { d -> d.copy(version = maxOf(d.version, 5), attachments = d.attachments.map { r ->
            val stopped = when (r.state) {
                AttachmentState.PICKER_PENDING -> r.copy(state = AttachmentState.CANCELLED, error = "IMPORT_PICKER_CANCELLED")
                AttachmentState.IMPORTING, AttachmentState.STAGING, AttachmentState.RUNNING -> r.copy(state = AttachmentState.PAUSED, runId = null, sourceUri = null, error = "RESTART_PAUSED")
                else -> r
            }
            stopped.copy(exports = stopped.exports.map { e -> when (e.state) {
                ExportState.PICKER_PENDING -> e.copy(state = ExportState.NOT_CREATED, error = "EXPORT_PICKER_CANCELLED")
                ExportState.INTENT, ExportState.CREATED -> e.copy(state = ExportState.UNCERTAIN, error = "EXPORT_INTERRUPTED")
                else -> e
            } })
        }) }; changed()
    }
    suspend fun cancel(id: String, reviewed: Boolean) {
        demand(reviewed, "LOCAL_CONFIRMATION_REQUIRED")
        repository.mutate { d ->
            val r = d.attachment(id); demand(r.runId == null && r.exports.none { it.state in TransferExport.inFlight || it.state == ExportState.UNCERTAIN }, "ATTACHMENT_BUSY")
            var next = d.replaceAttachment(r.copy(state = AttachmentState.CANCELLED, sourceUri = null))
            val m = d.chat?.messages?.find { it.id == r.messageId }
            if (m != null && m.direction == ChatDirection.OUTGOING && m.status != ChatStatus.DELIVERED) {
                demand(m.runId == null, "CHAT_BUSY")
                next = next.copy(chat = d.chat?.replace(m.copy(status = if (m.envelope == null) ChatStatus.CANCELLED_NOT_SENT else ChatStatus.CANCELLED_UNCONFIRMED)))
            }
            next
        }; changed()
    }
    suspend fun remove(id: String, reviewed: Boolean) {
        demand(reviewed, "LOCAL_CONFIRMATION_REQUIRED")
        val (doc, r) = record(id); demand(r.state == AttachmentState.CANCELLED && r.runId == null && r.exports.none { it.state in TransferExport.inFlight || it.state == ExportState.UNCERTAIN }, "ATTACHMENT_REFERENCED")
        if (r.secret != null) storage.remove(doc.storeId, r.ciphertextRecord())
        repository.mutate { d -> demand(d.attachment(id) == r, "ATTACHMENT_STOPPED"); d.copy(attachments = d.attachments.filterNot { it.id == id }) }; changed()
    }
}
