package com.narada.connection

import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

private class ChatPeerFixture {
    val f=ChatFixture();val client=ChatClient(f.memory,f.peer.engine)
    var health=f.peer.health;var healthCount=0;var chatCount=0
    var onHealth:(Int)->Unit={};var remoteError:String?=null
    var transform:(JsonElement)->JsonElement={it}
    val sent=mutableListOf<JsonObject>()
    fun peer()=f.memory.document.peer(f.peerId)
    fun ticket():ChatTicket {val s=f.memory.document.chat!!;val b=s.binding(f.peerId);return ChatTicket(f.peerId,b.pairingAttempt,s.enrollmentId,b.ours)}
    val session=object:PeerSession {
        override val certificate=f.peer.host.certificate.encoded
        override fun close(){}
        override fun request(path:String,body:ByteArray):ByteArray {
            if(path=="/api/v1/health"){healthCount++;onHealth(healthCount);return Wire.encode(Envelope(true,health,null))}
            assertEquals(ChatRules.PATH,path);chatCount++
            val wire=Wire.decode<ChatWire>(body);sent+=wire.action
            if(remoteError!=null)return Wire.encode(Envelope<JsonElement>(false,null,RemoteError(remoteError!!,"Never display this peer-provided text")))
            val response=when(wire.action["kind"]!!.jsonPrimitive.content){
                "bundle" -> Wire.json.encodeToJsonElement(f.remoteBundle)
                "pull" -> Wire.json.encodeToJsonElement(ChatPullReply(f.remoteBundle,f.remoteOutbox.firstOrNull()))
                "ack" -> Wire.json.encodeToJsonElement(ChatAcknowledged(true))
                else -> error("Unexpected fixture action")
            }
            return Wire.encode(Envelope(true,transform(response),null))
        }
    }
}
class ChatClientTest {
    @Test fun bundleHasPreAndPostHealthAndNoPrivateWireState()=runBlocking<Unit>{val f=ChatPeerFixture();f.client.bundle(f.peer(),f.session,f.ticket());assertEquals(2,f.healthCount);assertEquals(setOf("kind"),f.sent.single().keys);assertEquals(1,f.chatCount)}
    @Test fun missingChatConsentStopsBeforeContentRequest()=runBlocking<Unit>{val f=ChatPeerFixture();f.health=f.health.copy(permissions=Permissions(),grant_revision=2,session_epoch=2);expectFailure("ACCESS_DENIED"){runBlocking{f.client.pull(f.peer(),f.session,f.ticket())}};assertEquals(0,f.chatCount)}
    @Test fun postHealthAuthorityChangeDiscardsEvenAValidPull()=runBlocking<Unit>{val f=ChatPeerFixture();f.f.remoteMessage();f.onHealth={if(it==2)f.health=f.health.copy(grant_revision=2,session_epoch=2)};expectFailure("CHAT_ACCESS_CHANGED"){runBlocking{f.client.pull(f.peer(),f.session,f.ticket())}};assertEquals(1,f.chatCount);assertTrue(f.f.memory.document.chat!!.messages.isEmpty())}
    @Test fun wrongEnrollmentNeverReachesHealthOrWire()=runBlocking<Unit>{val f=ChatPeerFixture();expectFailure("CHAT_SCOPE_CHANGED"){runBlocking{f.client.bundle(f.peer(),f.session,f.ticket().copy(enrollmentId=UUID.randomUUID().toString()))}};assertEquals(0,f.healthCount);assertEquals(0,f.chatCount)}
    @Test fun foregroundFenceStopsBeforeAnyRequest()=runBlocking<Unit>{val f=ChatPeerFixture();expectFailure("CHAT_STOPPED"){runBlocking{f.client.pull(f.peer(),f.session,f.ticket()){fail("CHAT_STOPPED")}}};assertEquals(0,f.healthCount);assertEquals(0,f.chatCount)}
    @Test fun substitutedOuterRecipientIsRejectedBeforeDelivery()=runBlocking<Unit>{val f=ChatPeerFixture();val e=f.f.remoteMessage();f.transform={Wire.json.encodeToJsonElement(ChatPullReply(f.f.remoteBundle,e.copy(recipient="a".repeat(64))))};expectFailure("INVALID_MESSAGE"){runBlocking{f.client.pull(f.peer(),f.session,f.ticket())}};assertTrue(f.f.memory.document.chat!!.messages.isEmpty())}
    @Test fun alreadyAcknowledgedExactDuplicateCanReAckWithoutDecrypt()=runBlocking<Unit>{val f=ChatPeerFixture();val e=f.f.remoteMessage();f.f.sync();val id=UUID.randomUUID().toString();f.f.memory.mutate{d->d.copy(chat=d.chat!!.replace(d.chat.message(e.message_id).copy(runId=id)))};val m=f.f.memory.document.chat!!.message(e.message_id);assertTrue(m.acknowledged);f.client.ack(f.peer(),f.session,f.ticket(),ChatDelivery(m.id,id,null,m.receipt));assertEquals(1,f.chatCount);assertEquals("ack",f.sent.single()["kind"]!!.jsonPrimitive.content)}
    @Test fun uncommittedAckTokenCannotEscapeToNetwork()=runBlocking<Unit>{val f=ChatPeerFixture();val e=f.f.remoteMessage();f.f.sync();val m=f.f.memory.document.chat!!.message(e.message_id);expectFailure("CHAT_STOPPED"){runBlocking{f.client.ack(f.peer(),f.session,f.ticket(),ChatDelivery(m.id,UUID.randomUUID().toString(),null,m.receipt))}};assertEquals(0,f.chatCount);assertEquals(0,f.healthCount)}
    @Test fun remoteLockedVaultIsNotALocalStorageLatchCode()=runBlocking<Unit>{val f=ChatPeerFixture();f.remoteError="CRYPTO_STATE_INVALID";expectFailure("CHAT_REMOTE_STORAGE"){runBlocking{f.client.bundle(f.peer(),f.session,f.ticket())}}}
    @Test fun pinnedNativeModeAndPersistedSessionReopenAreRealJniCalls()=runBlocking<Unit>{val f=ChatFixture();assertEquals(OlmInfo(2,"0.11.1","olm.v1",64,false),f.crypto.info());f.queue();f.sync();verifyProtectedChat(f.memory.document,f.crypto);val session=f.memory.document.chat!!.bindings.single().sessions.single();expectFailure("CRYPTO_STATE_INVALID"){f.crypto.inspectSession(session.copy(pickle="{}"))};expectFailure("CRYPTO_STATE_INVALID"){f.crypto.inspectSession(session.copy(id=java.util.Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32))))}}
    @Test fun damagedPersistedAccountIsNotAnEmptyEnrollment() {val f=ChatFixture();expectFailure("CRYPTO_STATE_INVALID"){verifyProtectedChat(f.memory.document.copy(chat=f.memory.document.chat!!.copy(account="{}")),f.crypto)}}
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun chatAndTransfersShareOneAdmissionClock()=runTest {
        val starts=mutableListOf<Long>();val admission=ContentAdmission{testScheduler.currentTime};val f=ChatFixture();val ticket=ChatTicket(f.peerId,f.memory.document.peer(f.peerId).attemptId,f.memory.document.chat!!.enrollmentId,f.memory.document.chat!!.bindings.single().ours)
        val chat=PacedChatGateway(object:ChatGateway by f.gateway {override suspend fun bundle(ticket:ChatTicket):ChatResult<ChatBundle>{starts+=testScheduler.currentTime;return f.gateway.bundle(ticket)}},admission)
        val transfers=PacedTransfersGateway(object:TransfersGateway {override suspend fun prepare(record:DownloadRecord):ManifestBundle{starts+=testScheduler.currentTime;return TransferSamples.bundle(byteArrayOf())};override suspend fun chunk(record:DownloadRecord,index:Int)=byteArrayOf();override suspend fun finish(record:DownloadRecord){};override fun cancel(){}},admission)
        chat.bundle(ticket);transfers.prepare(TransferPeerFixture().record);chat.bundle(ticket);assertEquals(listOf(0L,300L,600L),starts)
    }
}
