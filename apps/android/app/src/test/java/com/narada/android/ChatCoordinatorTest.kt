package com.narada.android

import com.narada.android.chat.*
import com.narada.android.connection.*
import com.narada.connection.*
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Lifecycle/ownership tests ONLY, using an explicitly fake crypto and peer boundary. Actual JNI
 * and real-desktop cryptography are separate test campaigns, not attributed to these fakes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=android.app.Application::class)
class ChatCoordinatorTest {
    private class FakeCrypto:OlmCryptography {
        fun k(b:Int,n:Int=32)=Base64.getEncoder().withoutPadding().encodeToString(ByteArray(n){b.toByte()})
        override fun info()=OlmInfo(2,"0.11.1","olm.v1",64,false)
        override fun create()=OlmAccount("TEST-ONLY-FAKE-ACCOUNT",k(1),k(2))
        override fun inspect(account:String)=OlmPublic(k(1),k(2))
        override fun inspectSession(session:ChatSession){}
        override fun bundle(account:String,localId:String,peerId:String)=OlmBundle(account,ChatBundle(transport_id=localId,recipient=peerId,curve=k(1),ed=k(2),one_time=k(3),signature=k(4,64)))
        override fun verify(bundle:ChatBundle,localId:String,peerId:String)=bundle.validate(peerId,localId)
        override fun encrypt(account:String,ours:ChatBundle,remote:ChatBundle,session:ChatSession?,body:ChatBody)=OlmEncrypted(ChatSession(k(5),"TEST-ONLY-FAKE-SESSION"),ChatEnvelope(message_id=body.message_id,sender=body.sender,recipient=body.recipient,session_id=k(5),message_type=0,ciphertext="AQID"))
        override fun decrypt(account:String,ours:ChatBundle,remote:ChatBundle,sessions:List<ChatSession>,envelope:ChatEnvelope):OlmDecrypted=error("Not a crypto test")
    }
    private class Repository:TrustRepository {
        val key=AndroidTestIdentities.create();val mutex=Mutex();var failWrite=false
        var doc=TrustDocument(storeId=UUID.randomUUID().toString(),deviceId=key.deviceId,fingerprint=key.fingerprint,certificate=Base64.getEncoder().encodeToString(key.certificate.encoded),enabled=true)
        override suspend fun read()=doc
        override suspend fun initialize()=doc
        override suspend fun identity()=key
        override suspend fun mutate(change:(TrustDocument)->TrustDocument)=mutex.withLock {val next=change(doc);next.validate();doc.chat?.requireContinuation(next.chat);if(failWrite)com.narada.connection.fail("STORAGE_UNAVAILABLE");doc=next.copy(generation=doc.generation+1);doc}
    }
    private lateinit var foundation:TestFixture;private lateinit var scope:CoroutineScope
    private lateinit var repository:Repository;private lateinit var connections:ConnectionsCoordinator;private lateinit var chat:ChatCoordinator
    private lateinit var peer:TrustedPeer;private val crypto=FakeCrypto()
    private val content=AtomicInteger();private val pushes=AtomicInteger();private val closes=AtomicInteger()
    private var blockPush=false;private val entered=CountDownLatch(1);private val release=CountDownLatch(1)
    private suspend fun ready(predicate:(ChatUiState)->Boolean)=withTimeout(5000){chat.state.first(predicate)}
    @Before fun setup()=runBlocking<Unit>{
        foundation=TestFixture();scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);repository=Repository()
        val host=AndroidTestIdentities.create(true);val operations=listOf("health","permissions","files.v1","transfers.v1","chat.olm.v1","attachments.age.v1","sync.v1")
        peer=TrustedPeer(host.deviceId,host.fingerprint,Base64.getEncoder().encodeToString(host.certificate.encoded),"Fixture desktop","windows","192.168.0.10:12345",UUID.randomUUID().toString(),UUID.randomUUID().toString(),System.currentTimeMillis()+120000,TrustStatus.ACTIVE,remotePermissions=Permissions(chat=true),remoteRevision=1,remoteEpoch=1,remoteOperations=operations)
        repository.doc=repository.doc.copy(peers=listOf(peer),preferredPeer=peer.id)
        val connector=object:PeerConnector {
            override fun closeAll(){closes.incrementAndGet();if(blockPush&&entered.count==0L)release.countDown()}
            override fun connect(endpoint:Endpoint,pin:PeerPin,identity:TransportIdentity?)=object:PeerSession {
                override val certificate=host.certificate.encoded;override fun close(){}
                override fun request(path:String,body:ByteArray):ByteArray {
                    if(path=="/api/v1/health")return Wire.encode(Envelope(true,Health(Hello(),peer.id,Permissions(chat=true),1,1,operations),null))
                    content.incrementAndGet();assertEquals(ChatRules.PATH,path);val w=Wire.decode<ChatWire>(body)
                    val b=crypto.bundle("fixture",peer.id,repository.doc.deviceId).bundle
                    val result:JsonElement=when(w.action["kind"]!!.jsonPrimitive.content){
                        "bundle" -> Wire.json.encodeToJsonElement(b)
                        "pull" -> Wire.json.encodeToJsonElement(ChatPullReply(b,null))
                        "push" -> {pushes.incrementAndGet();entered.countDown();if(blockPush)assertTrue(release.await(5,TimeUnit.SECONDS));val e=Wire.json.decodeFromJsonElement<ChatEnvelope>(w.action["envelope"]!!);Wire.json.encodeToJsonElement(ChatReceipt(e.message_id,e.sender,e.recipient,e.digest(),committed_at="2026-10-31T00:00:00Z"))}
                        else -> error("Unexpected fixture action")
                    };return Wire.encode(Envelope(true,result,null))
                }
            }
        }
        connections=ConnectionsCoordinator(repository,connector,ConnectionsCoordinatorTest.NetworkPolicy(),ConnectionsCoordinatorTest.Discovery(),foundation.settings,scope)
        connections.foregroundChanged(true);withTimeout(5000){connections.state.first{it.connectedPeer==peer.id}}
        chat=ChatCoordinator(repository,crypto,PacedChatGateway(RemoteChatGateway(repository,connections)),connections,ChatNotice{},scope)
        chat.visible(true);ready{it.peerId==peer.id&&it.access!=null};chat.enable(true);ready{it.enabled&&!it.busy}
    }
    @After fun cleanup()=runBlocking<Unit>{release.countDown();if(::chat.isInitialized)chat.visible(false);if(::connections.isInitialized)connections.foregroundChanged(false);if(::scope.isInitialized)scope.coroutineContext[Job]?.cancelAndJoin();if(::foundation.isInitialized)foundation.close()}
    @Test fun openingChatAndSavingDraftNeverStartsContentNetworking()=runBlocking<Unit>{chat.saveDraft("PRIVATE_DRAFT");ready{it.draft=="PRIVATE_DRAFT"&&!it.busy};assertEquals(0,content.get());assertEquals("PRIVATE_DRAFT",repository.doc.chat!!.drafts.single().text)}
    @Test fun leavingTabImmediatelyClearsPrivateProjectionButKeepsExplicitDraft()=runBlocking<Unit>{chat.saveDraft("PRIVATE_DRAFT");ready{it.draft=="PRIVATE_DRAFT"&&!it.busy};chat.visible(false);assertEquals("",chat.state.value.draft);assertTrue(chat.state.value.rows.isEmpty());assertEquals("PRIVATE_DRAFT",repository.doc.chat!!.drafts.single().text)}
    @Test fun backgroundClearsMemoryAndDoesNotAutoSendOnReturn()=runBlocking<Unit>{ChatEngine(repository,crypto).queue(peer.id,UUID.randomUUID().toString(),"queued privately");chat.reload();ready{it.rows.size==1&&!it.busy};connections.foregroundChanged(false);ready{!it.foreground&&it.rows.isEmpty()};connections.foregroundChanged(true);withTimeout(5000){connections.state.first{it.connectedPeer==peer.id}};assertEquals(0,pushes.get());assertTrue(chat.state.value.rows.isEmpty())}
    @Test fun generalConnectionFlowNeverContainsOlmStateOrChatPlaintext()=runBlocking<Unit>{chat.saveDraft("PRIVATE_CONNECTION_SENTINEL");ready{it.draft.isNotEmpty()&&!it.busy};connections.reload();withTimeout(5000){connections.state.first{it.connectedPeer==peer.id}};assertNull(connections.state.value.document!!.chat);val public=Wire.encode(connections.state.value.document!!,Wire.MAX_STORE).decodeToString();assertFalse(public.contains("TEST-ONLY-FAKE-ACCOUNT"));assertFalse(public.contains("PRIVATE_CONNECTION_SENTINEL"))}
    @Test fun explicitSendUsesTheConnectionOwnerAndGetsAReceipt()=runBlocking<Unit>{val id=UUID.randomUUID().toString();chat.send(id,"real coordinator, fake peer fixture");ready{it.rows.any{r->r.id==id&&r.status==ChatStatus.DELIVERED}&&!it.busy};assertEquals(1,pushes.get());assertNotNull(repository.doc.chat!!.message(id).receipt)}
    @Test fun cancellationAfterAdmissionKeepsAnUnknownOutcome()=runBlocking<Unit>{blockPush=true;val id=UUID.randomUUID().toString();chat.send(id,"cannot recall admitted work");assertTrue(entered.await(5,TimeUnit.SECONDS));chat.visible(false);withTimeout(5000){while(repository.doc.chat!!.message(id).runId!=null)delay(10)};assertEquals(ChatStatus.UNCERTAIN,repository.doc.chat!!.message(id).status);assertNotNull(repository.doc.chat!!.message(id).envelope);assertTrue(chat.state.value.rows.isEmpty())}
    @Test fun localWriteFailureLatchesAllNetworkingAndClearsThePrivateView()=runBlocking<Unit>{repository.failWrite=true;chat.send(UUID.randomUUID().toString(),"not admitted");withTimeout(5000){connections.state.first{it.error=="STORAGE_UNAVAILABLE"}};assertFalse(connections.state.value.transportAllowed);assertTrue(chat.state.value.rows.isEmpty());assertEquals(0,pushes.get());repository.failWrite=false;chat.reload();ready{!it.busy};assertFalse(connections.state.value.transportAllowed)}
    @Test fun chatCancellationCannotCloseAnUnrelatedFilesLease()=runBlocking<Unit>{val enteredFiles=CompletableDeferred<Unit>();val finishFiles=CompletableDeferred<Unit>();val worker=scope.launch{connections.withFiles{_,_,_,_->enteredFiles.complete(Unit);finishFiles.await()}};enteredFiles.await();val before=closes.get();chat.visible(false);delay(100);assertEquals(before,closes.get());finishFiles.complete(Unit);worker.join()}
}
