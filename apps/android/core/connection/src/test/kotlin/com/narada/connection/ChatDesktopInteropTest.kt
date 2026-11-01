package com.narada.connection

import java.net.Socket
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*

/** Current actual Rust desktop (including the separately versioned upload extension), owner IPC + production Kotlin mTLS + real JNI Olm.
 * Linux software-key execution, NOT an Android/Windows/native-keystore qualification. */
class ChatDesktopInteropTest {
    private lateinit var host: RealDesktop
    private lateinit var store: MemoryTrust
    private lateinit var tls: TlsConnector
    private lateinit var pairing: PairingEngine
    private lateinit var engine: ChatEngine
    private lateinit var peerId: String
    private var clock = System.currentTimeMillis()
    private var afterPush: suspend () -> Unit = {}
    private var afterAck: suspend () -> Unit = {}
    private var afterPull: suspend () -> Unit = {}
    private val pushed = mutableListOf<ChatEnvelope>()
    private val pulled = mutableListOf<ChatEnvelope>()
    private val gateway = PacedChatGateway(object : ChatGateway {
        override suspend fun bundle(ticket: ChatTicket) = pairing.withActiveSession(peerId) { p,s -> ChatClient(store,pairing).bundle(p,s,ticket) }
        override suspend fun push(ticket: ChatTicket, delivery: ChatDelivery): ChatResult<ChatReceipt> {
            assertEquals(delivery.envelope,store.document.chat!!.message(delivery.id).envelope);pushed += delivery.envelope!!
            return pairing.withActiveSession(peerId) { p,s -> ChatClient(store,pairing).push(p,s,ticket,delivery) }.also { afterPush() }
        }
        override suspend fun pull(ticket: ChatTicket) = pairing.withActiveSession(peerId) { p,s -> ChatClient(store,pairing).pull(p,s,ticket) }.also { it.value.envelope?.let(pulled::add); afterPull() }
        override suspend fun ack(ticket: ChatTicket, delivery: ChatDelivery): ChatResult<Unit> {
            assertEquals(delivery.receipt,store.document.chat!!.message(delivery.id).receipt)
            return pairing.withActiveSession(peerId) { p,s -> ChatClient(store,pairing).ack(p,s,ticket,delivery) }.also { afterAck() }
        }
        override fun cancel() = tls.closeAll()
    })
    @Before fun setup() = runBlocking<Unit> {
        host=RealDesktop();host.enable();store=MemoryTrust();tls=TlsConnector(SocketSource{Socket()});pairing=PairingEngine(store,tls)
        val code=PairCode.parse(host.call("start_pairing").jsonObject["code"]!!.jsonPrimitive.content)
        val pending=pairing.begin(code,"Android Olm JVM fixture");pairing.prove(pending.id)
        host.call("approve_pairing",buildJsonObject { put("attempt_id",pending.attemptId);put("permissions",Wire.json.encodeToJsonElement(Permissions(chat=true))) })
        pairing.prove(pending.id);peerId=pairing.finish(pending.id,true).id
        pairing.withActiveSession(peerId){p,s->pairing.health(p,s)}
        engine=ChatEngine(store,VodozemacOlm()){clock};engine.enablePeer(peerId,true)
    }
    @After fun cleanup(){if(::tls.isInitialized)tls.closeAll();if(::host.isInitialized)host.close()}
    private fun history()=host.call("chat_history",buildJsonObject {put("peer_id",store.key.deviceId);put("before",JsonNull)}).jsonObject["messages"]!!.jsonArray
    private fun remoteSend(text:String):String {val id=UUID.randomUUID().toString();host.call("send_chat",buildJsonObject {put("message_id",id);put("peer_id",store.key.deviceId);put("text",text);put("attachment",JsonNull)});return id}
    private suspend fun sync()=engine.sync(peerId,gateway)
    private suspend fun send(text:String):ChatMessage {val m=engine.queue(peerId,UUID.randomUUID().toString(),text);sync();return store.document.chat!!.message(m.id)}
    private suspend fun restartBoth() {host.restart();pairing.setEndpoint(peerId,host.snapshot()["endpoint"]!!.jsonPrimitive.content);store=MemoryTrust(store.key,Wire.decode(Wire.encode(store.document,Wire.MAX_STORE),Wire.MAX_STORE));pairing=PairingEngine(store,tls);engine=ChatEngine(store,VodozemacOlm()){clock};engine.recover()}
    @Test fun actualDesktopDecryptsUnicodeTextAndReturnsADurableReceipt()=runBlocking<Unit>{val m=send("నమస్కారం — phone to desktop\nTwo lines.");assertEquals(ChatStatus.DELIVERED,m.status);assertEquals(m.text,history().single().jsonObject["text"]!!.jsonPrimitive.content);assertEquals(m.digest,m.receipt!!.digest)}
    @Test fun actualDesktopOutgoingPullCommitsThenAcknowledges()=runBlocking<Unit>{sync();val id=remoteSend("Desktop to phone — café");sync();val m=store.document.chat!!.message(id);assertEquals("Desktop to phone — café",m.text);assertTrue(m.acknowledged);assertEquals("delivered",history().single().jsonObject["state"]!!.jsonPrimitive.content)}
    @Test fun bidirectionalMessagesAndSubsequentRepliesStayInteroperable()=runBlocking<Unit>{sync();val incoming=remoteSend("Desktop prekey message");val local=engine.queue(peerId,UUID.randomUUID().toString(),"Phone prekey message");sync();assertEquals(ChatStatus.DELIVERED,store.document.chat!!.message(local.id).status);assertTrue(store.document.chat!!.message(incoming).acknowledged);send("Phone follow-up");val next=remoteSend("Desktop follow-up");sync();assertEquals("Desktop follow-up",store.document.chat!!.message(next).text);assertEquals(4,history().size)}
    @Test fun lostPushReplyReusesExactIdCiphertextAndServerReceiptAfterBothRestart()=runBlocking<Unit>{val m=engine.queue(peerId,UUID.randomUUID().toString(),"Exactly once in retained desktop history");afterPush={fail("PEER_TIMEOUT")};expectFailure("PEER_TIMEOUT"){runBlocking{sync()}};assertEquals(ChatStatus.UNCERTAIN,store.document.chat!!.message(m.id).status);restartBoth();clock+=5000;afterPush={};sync();assertEquals(pushed[0],pushed[1]);assertEquals(1,history().size);assertEquals(ChatStatus.DELIVERED,store.document.chat!!.message(m.id).status)}
    @Test fun lostAcknowledgmentReplyRetainsInboxAcrossBothRestarts()=runBlocking<Unit>{sync();val id=remoteSend("A saved receipt is retried, not decrypted again");afterAck={fail("PEER_TIMEOUT")};expectFailure("PEER_TIMEOUT"){runBlocking{sync()}};val before=store.document.chat!!.message(id);assertFalse(before.acknowledged);restartBoth();afterAck={};sync();assertEquals(before.receipt,store.document.chat!!.message(id).receipt);assertEquals(1,store.document.chat!!.messages.size);assertTrue(store.document.chat!!.message(id).acknowledged);assertEquals(1,pulled.size)}
    @Test fun withdrawnDesktopChatConsentStopsBeforeAnyMessageBody()=runBlocking<Unit>{val m=engine.queue(peerId,UUID.randomUUID().toString(),"Must stay queued");val revision=host.snapshot()["peers"]!!.jsonArray.single().jsonObject["grant_revision"]!!.jsonPrimitive.int;host.call("update_peer_permissions",buildJsonObject{put("peer_id",store.key.deviceId);put("expected_revision",revision);put("permissions",Wire.json.encodeToJsonElement(Permissions()))});expectFailure("ACCESS_DENIED"){runBlocking{sync()}};assertNull(store.document.chat!!.message(m.id).envelope);assertTrue(history().isEmpty());assertTrue(pushed.isEmpty())}
    @Test fun failedPhoneInboxWriteCannotAcknowledgeDesktopDelivery()=runBlocking<Unit>{sync();val id=remoteSend("Do not ACK an uncommitted inbox");afterPull={store.failWrite=true};expectFailure("STORAGE_UNAVAILABLE"){runBlocking{sync()}};assertTrue(store.document.chat!!.messages.isEmpty());assertNotEquals("delivered",history().single().jsonObject["state"]!!.jsonPrimitive.content);store.failWrite=false;afterPull={};Thread.sleep(4100);sync();assertTrue(store.document.chat!!.message(id).acknowledged)}
    @Test fun maximumUtf8TextFitsTheActualWireAndOlmPayload()=runBlocking<Unit>{val text="é".repeat(2048);val m=send(text);assertEquals(ChatStatus.DELIVERED,m.status);assertEquals(text,history().single().jsonObject["text"]!!.jsonPrimitive.content)}
    @Test fun withdrawnAttachmentGrantPreventsInboxRatchetCommitOrAck()=runBlocking<Unit>{
        val directory=java.nio.file.Files.createTempDirectory("narada-p67-attachment-withdrawal-").toFile()
        try {
            java.io.File(directory,"not-enabled.bin").writeBytes(byteArrayOf(1,2,3,4))
            val root=host.call("register_root",buildJsonObject{put("path",directory.path);put("label","Disposable attachment grant-withdrawal fixture");put("exclusions",buildJsonArray{})}).jsonObject["root_id"]!!.jsonPrimitive.content
            val revision=host.snapshot()["peers"]!!.jsonArray.single().jsonObject["grant_revision"]!!.jsonPrimitive.int
            host.call("update_peer_permissions",buildJsonObject{put("peer_id",store.key.deviceId);put("expected_revision",revision);put("permissions",Wire.json.encodeToJsonElement(Permissions(chat=true,attachment=true,roots=listOf(RootGrant(root,listOf("browse","read"))))))})
            pairing.withActiveSession(peerId){p,s->pairing.health(p,s)};sync()
            val entry=pairing.withActiveSession(peerId){p,s->(FilesClient(store,pairing).query(p,s,FileQuery.Search(root,"not-enabled.bin")) as FileResult.Page).value.entries.single()}
            val id=UUID.randomUUID().toString();host.call("send_chat",buildJsonObject{put("message_id",id);put("peer_id",store.key.deviceId);put("text","Attachment must not be acknowledged after grant withdrawal");put("attachment",Wire.json.encodeToJsonElement(TransferSource(root,entry.object_id,entry.version)))})
            val nextRevision=host.snapshot()["peers"]!!.jsonArray.single().jsonObject["grant_revision"]!!.jsonPrimitive.int
            host.call("update_peer_permissions",buildJsonObject{put("peer_id",store.key.deviceId);put("expected_revision",nextRevision);put("permissions",Wire.json.encodeToJsonElement(Permissions(chat=true,roots=listOf(RootGrant(root,listOf("browse","read"))))))})
            // Current desktop withholds attachment envelopes after the grant is removed.
            val before=store.document.chat!!;sync()
            assertEquals(before.account,store.document.chat!!.account);assertEquals(before.bindings,store.document.chat!!.bindings);assertTrue(store.document.chat!!.messages.isEmpty());assertNotEquals("delivered",history().single().jsonObject["state"]!!.jsonPrimitive.content)
        } finally { directory.deleteRecursively() }
    }

}
