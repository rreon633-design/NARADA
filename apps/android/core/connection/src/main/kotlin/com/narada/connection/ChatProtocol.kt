package com.narada.connection

import java.time.OffsetDateTime
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

object ChatRules {
    const val PATH = "/api/v1/chat"
    const val CAPABILITY = "chat.olm.v1"
    const val MAX_MESSAGES = 32
    const val MAX_PEERS = 4
    const val MAX_TEXT = 4096
    const val MAX_ACCOUNT = 65_536
    const val MAX_SESSION = 98_304
    fun text(value: String, empty: Boolean = false) {
        demand(value.toByteArray(Charsets.UTF_8).size <= MAX_TEXT && (empty || value.isNotBlank()) && value.none { Character.isISOControl(it) && it != '\n' && it != '\t' }, "INVALID_MESSAGE")
        // Reject unpaired UTF-16 surrogates rather than silently replacing user text during UTF-8 encoding.
        try { Charsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(value)) } catch (_: Exception) { fail("INVALID_MESSAGE") }
    }
    fun key(value: String, bytes: Int) {
        val decoded = try { Base64.getDecoder().decode(value) } catch (_: IllegalArgumentException) { fail("CHAT_KEY_INVALID") }
        demand(decoded.size == bytes && Base64.getEncoder().withoutPadding().encodeToString(decoded) == value, "CHAT_KEY_INVALID")
    }
    fun authority(doc: TrustDocument, peerId: String, attempt: String): TrustedPeer {
        val p = doc.peer(peerId)
        demand(doc.enabled && p.status == TrustStatus.ACTIVE && !p.blocked && p.failures < 6 && p.attemptId == attempt, "CHAT_SCOPE_CHANGED")
        demand(p.remotePermissions.chat, "ACCESS_DENIED")
        demand(CAPABILITY in p.remoteOperations, "CHAT_UNSUPPORTED")
        demand(doc.peers.none { it.status !in setOf(TrustStatus.ACTIVE, TrustStatus.REVOKED) }, "PAIRING_IN_PROGRESS")
        return p
    }
}
@Serializable data class ChatBundle(val version: Int = 1, val transport_id: String, val recipient: String, val curve: String, val ed: String, val one_time: String, val signature: String) {
    fun validate(sender: String, receiver: String) {
        demand(version == 1 && transport_id == sender && recipient == receiver && sender != receiver, "CHAT_KEY_INVALID")
        TransferRules.hash(sender); TransferRules.hash(receiver); ChatRules.key(curve, 32); ChatRules.key(ed, 32); ChatRules.key(one_time, 32); ChatRules.key(signature, 64)
    }
    override fun toString() = "ChatBundle([private binding redacted])"
}
@Serializable data class ChatSession(val id: String, val pickle: String) {
    fun validate() { ChatRules.key(id, 32); demand(pickle.isNotEmpty() && pickle.toByteArray().size <= ChatRules.MAX_SESSION, "CRYPTO_STATE_INVALID") }
    override fun toString() = "ChatSession([secret state redacted])"
}
@Serializable data class ChatEnvelope(val version: Int = 1, val message_id: String, val sender: String, val recipient: String, val session_id: String, val message_type: Int, val ciphertext: String) {
    fun validate() {
        demand(version == 1 && message_type in 0..1, "INVALID_MESSAGE"); canonicalUuid(message_id); TransferRules.hash(sender); TransferRules.hash(recipient)
        demand(sender != recipient && session_id.length <= 64 && ciphertext.isNotEmpty() && ciphertext.length <= 13000, "INVALID_MESSAGE"); ChatRules.key(session_id, 32)
        val bytes = try { Base64.getDecoder().decode(ciphertext) } catch (_: IllegalArgumentException) { fail("INVALID_MESSAGE") }
        demand(Base64.getEncoder().encodeToString(bytes) == ciphertext, "INVALID_MESSAGE")
    }
    /** Matches serde's declaration-ordered, compact ASCII envelope JSON exactly. */
    fun digest(): String { validate(); return sha256(Wire.encode(this)) }
    override fun toString() = "ChatEnvelope([private ciphertext redacted])"
}
@Serializable data class ChatReceipt(val message_id: String, val sender: String, val recipient: String, val digest: String, val state: String = "delivered", val committed_at: String) {
    fun validate() {
        canonicalUuid(message_id); TransferRules.hash(sender); TransferRules.hash(recipient); TransferRules.hash(digest)
        demand(sender != recipient && state == "delivered" && committed_at.length in 20..48, "INVALID_RECEIPT")
        try { OffsetDateTime.parse(committed_at) } catch (_: Exception) { fail("INVALID_RECEIPT") }
    }
    fun matches(e: ChatEnvelope) { validate(); demand(message_id == e.message_id && sender == e.sender && recipient == e.recipient && digest == e.digest(), "INVALID_RECEIPT") }
    override fun toString() = "ChatReceipt([private receipt redacted])"
}
@Serializable data class ChatBody(val domain: String = "NARADA-CHAT-1", val message_id: String, val sender: String, val recipient: String, val text: String, val attachment: AttachmentSecret? = null) {
    override fun toString() = "ChatBody([plaintext redacted])"
}
@Serializable data class ChatWire(val hello: Hello = Hello(), val version: Int = 1, val action: JsonObject)
@Serializable data class ChatPullReply(val bundle: ChatBundle, val envelope: ChatEnvelope?)
@Serializable data class ChatAcknowledged(val acknowledged: Boolean)
@Serializable data class ChatBinding(val peerId: String, val pairingAttempt: String, val ours: ChatBundle, val remote: ChatBundle? = null, val sessions: List<ChatSession> = emptyList()) {
    override fun toString() = "ChatBinding([protected state redacted])"
}
@Serializable data class ChatDraft(val peerId: String, val pairingAttempt: String, val text: String) {
    override fun toString() = "ChatDraft([plaintext redacted])"
}
@Serializable enum class ChatDirection { OUTGOING, INCOMING }
@Serializable enum class ChatStatus { QUEUED, READY, UNCERTAIN, DELIVERED, CANCELLED_NOT_SENT, CANCELLED_UNCONFIRMED }
@Serializable data class ChatMessage(
    val id: String, val peerId: String, val pairingAttempt: String, val direction: ChatDirection, val text: String, val createdAt: Long,
    val status: ChatStatus, val requestHash: String, val envelope: ChatEnvelope? = null, val digest: String? = null, val receipt: ChatReceipt? = null,
    val acknowledged: Boolean = false, val attempts: Int = 0, val retryAfter: Long = 0, val runId: String? = null, val error: String? = null, val unread: Boolean = false, val attachment: AttachmentSecret? = null,
) {
    fun validate(localId: String) {
        canonicalUuid(id); TransferRules.hash(peerId); canonicalUuid(pairingAttempt); TransferRules.hash(requestHash)
        ChatRules.text(text, attachment != null); attachment?.validate(); demand(createdAt > 0 && attempts in 0..3 && retryAfter >= 0 && peerId != localId, "CHAT_STATE_INVALID")
        runId?.let(::canonicalUuid); digest?.let(TransferRules::hash)
        demand(error == null || error.matches(Regex("[A-Z][A-Z0-9_]{0,63}")), "CHAT_STATE_INVALID")
        envelope?.let { it.validate(); demand(it.message_id == id && it.sender == localId && it.recipient == peerId && digest == it.digest(), "CHAT_STATE_INVALID") }
        receipt?.let { it.validate(); demand(it.message_id == id && it.digest == digest, "CHAT_STATE_INVALID") }
        if (direction == ChatDirection.INCOMING) {
            demand(status == ChatStatus.DELIVERED && envelope == null && receipt != null && receipt.sender == peerId && receipt.recipient == localId && requestHash == digest && attempts == 0, "CHAT_STATE_INVALID")
        } else {
            demand(!unread && requestHash == chatRequestHash(localId, peerId, pairingAttempt, id, text, attachment), "CHAT_STATE_INVALID")
            when (status) {
                ChatStatus.QUEUED, ChatStatus.CANCELLED_NOT_SENT -> demand(envelope == null && digest == null && receipt == null && runId == null && !acknowledged, "CHAT_STATE_INVALID")
                ChatStatus.READY, ChatStatus.UNCERTAIN, ChatStatus.CANCELLED_UNCONFIRMED -> demand(envelope != null && receipt == null && !acknowledged, "CHAT_STATE_INVALID")
                ChatStatus.DELIVERED -> { demand(envelope != null && receipt != null && runId == null && acknowledged, "CHAT_STATE_INVALID"); (receipt ?: fail("CHAT_STATE_INVALID")).matches(envelope ?: fail("CHAT_STATE_INVALID")) }
            }
            if (status in setOf(ChatStatus.CANCELLED_NOT_SENT, ChatStatus.CANCELLED_UNCONFIRMED)) demand(runId == null, "CHAT_STATE_INVALID")
        }
    }
    override fun toString() = "ChatMessage([plaintext and private state redacted])"
}
@Serializable data class ChatState(
    val version: Int = 1, val library: String = "0.11.1", val enrollmentId: String, val account: String, val curve: String, val ed: String,
    val bindings: List<ChatBinding> = emptyList(), val messages: List<ChatMessage> = emptyList(), val drafts: List<ChatDraft> = emptyList(),
) {
    fun validate(localId: String, peers: List<TrustedPeer>) {
        demand(version == 1 && library == "0.11.1" && account.isNotEmpty() && account.toByteArray().size <= ChatRules.MAX_ACCOUNT, "CRYPTO_STATE_INVALID")
        canonicalUuid(enrollmentId); ChatRules.key(curve, 32); ChatRules.key(ed, 32)
        demand(bindings.size <= ChatRules.MAX_PEERS && bindings.map { it.peerId }.distinct().size == bindings.size, "CHAT_STATE_INVALID")
        bindings.forEach { b ->
            canonicalUuid(b.pairingAttempt); demand(peers.any { it.id == b.peerId }, "CHAT_STATE_INVALID")
            b.ours.validate(localId, b.peerId); demand(b.ours.curve == curve && b.ours.ed == ed, "CRYPTO_STATE_INVALID")
            b.remote?.validate(b.peerId, localId)
            demand(b.sessions.size <= 2 && b.sessions.map { it.id }.distinct().size == b.sessions.size && (b.sessions.isEmpty() || b.remote != null), "CRYPTO_STATE_INVALID")
            b.sessions.forEach(ChatSession::validate)
        }
        demand(messages.size <= ChatRules.MAX_MESSAGES && messages.map { it.id }.distinct().size == messages.size, "CHAT_STATE_INVALID")
        messages.forEach { it.validate(localId); demand(bindings.any { b -> b.peerId == it.peerId }, "CHAT_STATE_INVALID") }
        demand(drafts.size <= ChatRules.MAX_PEERS && drafts.map { it.peerId }.distinct().size == drafts.size, "CHAT_STATE_INVALID")
        drafts.forEach { d -> ChatRules.text(d.text, true); canonicalUuid(d.pairingAttempt); demand(bindings.any { it.peerId == d.peerId && it.pairingAttempt == d.pairingAttempt }, "CHAT_STATE_INVALID") }
    }
    fun binding(peerId: String) = bindings.singleOrNull { it.peerId == peerId } ?: fail("CHAT_NOT_ENABLED")
    fun message(id: String) = messages.singleOrNull { it.id == id } ?: fail("MESSAGE_UNAVAILABLE")
    fun replace(binding: ChatBinding) = copy(bindings = bindings.map { if (it.peerId == binding.peerId) binding else it })
    fun replace(message: ChatMessage) = copy(messages = messages.map { if (it.id == message.id) message else it })
    override fun toString() = "ChatState([secret account, sessions and messages redacted])"
}

/** Enrolled crypto identity and replay/history evidence cannot be silently reset or evicted by
 * another repository caller. Mutable ratchet pickles still need independent integration review. */
fun ChatState.requireContinuation(next: ChatState?) {
    val n = next ?: fail("CRYPTO_STATE_INVALID")
    demand(n.enrollmentId == enrollmentId && n.curve == curve && n.ed == ed && n.library == library && n.version == version, "CRYPTO_STATE_INVALID")
    bindings.forEach { old ->
        val b = n.bindings.singleOrNull { it.peerId == old.peerId } ?: fail("CRYPTO_STATE_INVALID")
        demand(b.ours == old.ours && old.sessions.all { s -> b.sessions.any { it.id == s.id } }, "CRYPTO_STATE_INVALID")
        old.remote?.let { demand(b.remote?.curve == it.curve && b.remote?.ed == it.ed, "CHAT_KEY_CHANGED") }
    }
    messages.forEach { old ->
        val m = n.messages.singleOrNull { it.id == old.id } ?: fail("CHAT_STATE_INVALID")
        demand(m.peerId == old.peerId && m.pairingAttempt == old.pairingAttempt && m.direction == old.direction && m.text == old.text && m.attachment == old.attachment && m.createdAt == old.createdAt && m.requestHash == old.requestHash, "CHAT_STATE_INVALID")
        old.envelope?.let { demand(m.envelope == it, "CHAT_STATE_INVALID") }
        old.digest?.let { demand(m.digest == it, "CHAT_STATE_INVALID") }
        old.receipt?.let { demand(m.receipt == it && m.status == ChatStatus.DELIVERED, "CHAT_STATE_INVALID") }
        if (old.acknowledged) demand(m.acknowledged, "CHAT_STATE_INVALID")
    }
}
