package com.narada.connection

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*

val chatOperations = listOf("health", "permissions", "files.v1", "transfers.v1", "chat.olm.v1", "attachments.age.v1", "sync.v1")

/** Test-only gateway + software transport identity. Crypto is the actual host JNI library, not a ratchet fake. */
class ChatFixture {
    val peer = PeerFixture()
    val memory = peer.repository
    var beforeCommit: (TrustDocument) -> Unit = {}
    val repository = object : TrustRepository by memory {
        override suspend fun mutate(change: (TrustDocument) -> TrustDocument): TrustDocument = memory.mutate { change(it).also(beforeCommit) }
    }
    val crypto = VodozemacOlm()
    var clock = 1_793_404_800_000L
    var engine = ChatEngine(repository, crypto) { clock }
    val peerId: String
    var remoteAccount: String
    var remoteBundle: ChatBundle
    var remoteSessions = emptyList<ChatSession>()
    val remoteReceipts = mutableMapOf<String, ChatReceipt>()
    val remoteTexts = mutableListOf<String>()
    val remoteAttachments = mutableMapOf<String, AttachmentSecret>()
    val remoteOutbox = mutableListOf<ChatEnvelope>()
    val pushed = mutableListOf<ChatEnvelope>()
    var acknowledgments = 0
    var beforePush: () -> Unit = {}
    var afterPush: () -> Unit = {}
    var afterPull: () -> Unit = {}
    var beforeAck: () -> Unit = {}
    var afterAck: () -> Unit = {}
    init {
        peerId = runBlocking { peer.paired() }.id
        memory.document = memory.document.replace(memory.document.peer(peerId).copy(remotePermissions = Permissions(chat = true), remoteRevision = 1, remoteEpoch = 1, remoteOperations = chatOperations))
        peer.health = Health(Hello(), peerId, Permissions(chat = true), 1, 1, chatOperations)
        val remote = crypto.bundle(crypto.create().account, peerId, memory.document.deviceId)
        remoteAccount = remote.account; remoteBundle = remote.bundle
        runBlocking { engine.enablePeer(peerId, true) }
    }
    private fun scope() = ChatScope(memory.document.peer(peerId))
    private fun session(s: ChatSession) { remoteSessions = if (remoteSessions.any { it.id == s.id }) remoteSessions.map { if (it.id == s.id) s else it } else remoteSessions + s }
    val gateway = object : ChatGateway {
        override suspend fun bundle(ticket: ChatTicket) = ChatResult(remoteBundle, scope())
        override suspend fun push(ticket: ChatTicket, delivery: ChatDelivery): ChatResult<ChatReceipt> {
            beforePush(); val e = delivery.envelope!!
            val saved = memory.document.chat!!.message(delivery.id)
            assertEquals(ChatStatus.UNCERTAIN, saved.status); assertEquals(e, saved.envelope); assertEquals(delivery.runId, saved.runId)
            pushed += e
            val receipt = remoteReceipts[e.message_id] ?: run {
                val d = crypto.decrypt(remoteAccount, remoteBundle, ticket.ours, remoteSessions, e)
                remoteAccount = d.account; session(d.session); remoteTexts += d.text; d.attachment?.let { remoteAttachments[e.message_id] = it }
                ChatReceipt(e.message_id, e.sender, e.recipient, e.digest(), committed_at = Instant.ofEpochMilli(clock).toString()).also { remoteReceipts[e.message_id] = it }
            }
            afterPush(); return ChatResult(receipt, scope())
        }
        override suspend fun pull(ticket: ChatTicket): ChatResult<ChatPullReply> { val captured = scope(); afterPull(); return ChatResult(ChatPullReply(remoteBundle, remoteOutbox.firstOrNull()), captured) }
        override suspend fun ack(ticket: ChatTicket, delivery: ChatDelivery): ChatResult<Unit> {
            beforeAck(); assertEquals(delivery.receipt, memory.document.chat!!.message(delivery.id).receipt)
            acknowledgments++; remoteOutbox.removeAll { it.message_id == delivery.id }; afterAck(); return ChatResult(Unit, scope())
        }
        override fun cancel() {}
    }
    fun remoteMessage(text: String = "Real native ciphertext fixture", id: String = UUID.randomUUID().toString(), attachment: AttachmentSecret? = null): ChatEnvelope {
        val ours = memory.document.chat!!.binding(peerId).ours
        val e = crypto.encrypt(remoteAccount, remoteBundle, ours, remoteSessions.firstOrNull(), ChatBody(message_id = id, sender = peerId, recipient = memory.document.deviceId, text = text, attachment = attachment))
        session(e.session); remoteOutbox += e.envelope; return e.envelope
    }
    suspend fun queue(text: String = "Private fixture text", id: String = UUID.randomUUID().toString()) = engine.queue(peerId, id, text)
    suspend fun sync(guard: () -> Unit = {}) = engine.sync(peerId, gateway, guard)
    fun reopen() {
        memory.document = Wire.decode(Wire.encode(memory.document, Wire.MAX_STORE), Wire.MAX_STORE)
        engine = ChatEngine(repository, VodozemacOlm()) { clock }
    }
}
