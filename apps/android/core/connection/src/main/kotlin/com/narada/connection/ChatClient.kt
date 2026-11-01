package com.narada.connection

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

data class ChatTicket(val peerId: String, val pairingAttempt: String, val enrollmentId: String, val ours: ChatBundle) { override fun toString() = "ChatTicket([private binding redacted])" }
data class ChatDelivery(val id: String, val runId: String, val envelope: ChatEnvelope?, val receipt: ChatReceipt?) { override fun toString() = "ChatDelivery([private envelope/receipt redacted])" }
data class ChatScope(val peer: TrustedPeer) {
    fun check(doc: TrustDocument) {
        val p = ChatRules.authority(doc, peer.id, peer.attemptId)
        demand(p.localEpoch == peer.localEpoch && p.fingerprint == peer.fingerprint && p.remoteRevision == peer.remoteRevision && p.remoteEpoch == peer.remoteEpoch && p.remotePermissions == peer.remotePermissions && p.remoteOperations == peer.remoteOperations, "CHAT_ACCESS_CHANGED")
    }
}
data class ChatResult<T>(val value: T, val scope: ChatScope) { override fun toString() = "ChatResult([private result redacted])" }
interface ChatGateway {
    suspend fun bundle(ticket: ChatTicket): ChatResult<ChatBundle>
    suspend fun push(ticket: ChatTicket, delivery: ChatDelivery): ChatResult<ChatReceipt>
    suspend fun pull(ticket: ChatTicket): ChatResult<ChatPullReply>
    suspend fun ack(ticket: ChatTicket, delivery: ChatDelivery): ChatResult<Unit>
    fun cancel()
}
/** All requests borrow the connection owner's IO-scoped mTLS lease. No plaintext body, account
 * pickle, ratchet/session state or draft is ever a wire field or exposed to this gateway. */
class ChatClient(private val repository: TrustRepository, private val pairing: PairingEngine) {
    private suspend fun fence(peer: TrustedPeer, ticket: ChatTicket, delivery: ChatDelivery?, guard: () -> Unit) {
        currentCoroutineContext().ensureActive(); guard()
        val doc = repository.read() ?: fail("IDENTITY_NOT_READY"); ChatScope(peer).check(doc)
        val chat = doc.chat ?: fail("CHAT_NOT_ENABLED"); val binding = chat.binding(ticket.peerId)
        demand(chat.enrollmentId == ticket.enrollmentId && binding.pairingAttempt == ticket.pairingAttempt && ticket.peerId == peer.id && binding.ours == ticket.ours, "CHAT_SCOPE_CHANGED")
        delivery?.let { d ->
            val saved = chat.message(d.id)
            saved.attachment?.let { AttachmentRules.authority(doc, saved.peerId, saved.pairingAttempt) }
            demand(saved.peerId == ticket.peerId && saved.pairingAttempt == ticket.pairingAttempt && saved.runId == d.runId && saved.envelope == d.envelope && saved.receipt == d.receipt, "CHAT_STOPPED")
            if (d.envelope != null) demand(saved.direction == ChatDirection.OUTGOING && saved.status == ChatStatus.UNCERTAIN && saved.attempts > 0 && saved.receipt == null, "CHAT_STOPPED")
            else demand(saved.direction == ChatDirection.INCOMING && saved.status == ChatStatus.DELIVERED && saved.receipt != null, "CHAT_STOPPED")
        }
    }
    private suspend fun before(peer: TrustedPeer, session: PeerSession, ticket: ChatTicket, delivery: ChatDelivery?, guard: () -> Unit): TrustedPeer {
        fence(peer, ticket, delivery, guard)
        val fresh = pairing.health(peer, session); fence(fresh, ticket, delivery, guard); return fresh
    }
    private suspend fun after(peer: TrustedPeer, session: PeerSession, ticket: ChatTicket, delivery: ChatDelivery?, guard: () -> Unit) {
        fence(peer, ticket, delivery, guard); pairing.health(peer, session); fence(peer, ticket, delivery, guard)
    }
    suspend fun bundle(peer: TrustedPeer, session: PeerSession, ticket: ChatTicket, guard: () -> Unit = {}): ChatResult<ChatBundle> {
        val p = before(peer, session, ticket, null, guard)
        val value: ChatBundle = session.post(ChatRules.PATH, ChatWire(action = buildJsonObject { put("kind", "bundle") }))
        value.validate(ticket.peerId, ticket.ours.transport_id); after(p, session, ticket, null, guard); return ChatResult(value, ChatScope(p))
    }
    suspend fun push(peer: TrustedPeer, session: PeerSession, ticket: ChatTicket, delivery: ChatDelivery, guard: () -> Unit = {}): ChatResult<ChatReceipt> {
        val e = delivery.envelope ?: fail("MESSAGE_UNAVAILABLE"); e.validate()
        val p = before(peer, session, ticket, delivery, guard)
        val value: ChatReceipt = session.post(ChatRules.PATH, ChatWire(action = buildJsonObject {
            put("kind", "push"); put("bundle", Wire.json.encodeToJsonElement(ChatBundle.serializer(), ticket.ours)); put("envelope", Wire.json.encodeToJsonElement(ChatEnvelope.serializer(), e))
        }))
        value.matches(e); after(p, session, ticket, delivery, guard); return ChatResult(value, ChatScope(p))
    }
    suspend fun pull(peer: TrustedPeer, session: PeerSession, ticket: ChatTicket, guard: () -> Unit = {}): ChatResult<ChatPullReply> {
        val p = before(peer, session, ticket, null, guard)
        val value: ChatPullReply = session.post(ChatRules.PATH, ChatWire(action = buildJsonObject { put("kind", "pull"); put("bundle", Wire.json.encodeToJsonElement(ChatBundle.serializer(), ticket.ours)) }))
        value.bundle.validate(ticket.peerId, ticket.ours.transport_id)
        value.envelope?.let { it.validate(); demand(it.sender == ticket.peerId && it.recipient == ticket.ours.transport_id, "INVALID_MESSAGE") }
        after(p, session, ticket, null, guard); return ChatResult(value, ChatScope(p))
    }
    suspend fun ack(peer: TrustedPeer, session: PeerSession, ticket: ChatTicket, delivery: ChatDelivery, guard: () -> Unit = {}): ChatResult<Unit> {
        val receipt = delivery.receipt ?: fail("MESSAGE_UNAVAILABLE"); receipt.validate()
        val p = before(peer, session, ticket, delivery, guard)
        val value: ChatAcknowledged = session.post(ChatRules.PATH, ChatWire(action = buildJsonObject { put("kind", "ack"); put("receipt", Wire.json.encodeToJsonElement(ChatReceipt.serializer(), receipt)) }))
        demand(value.acknowledged, "INVALID_RECEIPT"); after(p, session, ticket, delivery, guard); return ChatResult(Unit, ChatScope(p))
    }
}
class PacedChatGateway(private val delegate: ChatGateway, private val admission: ContentAdmission = ContentAdmission()) : ChatGateway {
    override suspend fun bundle(ticket: ChatTicket) = admission.admit { delegate.bundle(ticket) }
    override suspend fun push(ticket: ChatTicket, delivery: ChatDelivery) = admission.admit { delegate.push(ticket, delivery) }
    override suspend fun pull(ticket: ChatTicket) = admission.admit { delegate.pull(ticket) }
    override suspend fun ack(ticket: ChatTicket, delivery: ChatDelivery) = admission.admit { delegate.ack(ticket, delivery) }
    override fun cancel() = delegate.cancel()
}
