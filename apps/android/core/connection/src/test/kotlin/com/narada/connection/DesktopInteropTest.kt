package com.narada.connection

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*

/** New campaign: production Kotlin transport + current corrected Rust, on Linux loopback.
 * JVM fixture keys are NOT an AndroidKeyStore or Windows execution claim. */
class DesktopInteropTest {
    private lateinit var host: RealDesktop
    private lateinit var store: MemoryTrust
    private lateinit var connector: TlsConnector
    private lateinit var engine: PairingEngine
    @Before fun setup() { host=RealDesktop();store=MemoryTrust();connector=TlsConnector(SocketSource{java.net.Socket()});engine=PairingEngine(store,connector);host.enable() }
    @After fun cleanup() { if(::connector.isInitialized)connector.closeAll();if(::host.isInitialized)host.close() }
    private fun code()=PairCode.parse(host.call("start_pairing").jsonObject["code"]!!.jsonPrimitive.content)
    private suspend fun pair(): TrustedPeer {
        val p=engine.begin(code(),"Android JVM fixture")
        assertEquals(TrustStatus.WAITING,engine.prove(p.id).status)
        assertTrue(host.snapshot()["peers"]!!.jsonArray.isEmpty())
        host.approve(p.attemptId)
        assertEquals(TrustStatus.APPROVED,engine.prove(p.id).status)
        return engine.finish(p.id,true)
    }
    @Test fun actualRustRequiresProofOwnerApprovalAndPhoneConfirmation() = runBlocking<Unit> {
        val p=pair();val (captured,s)=engine.openActive(p.id);s.use { val h=engine.health(captured,it);assertEquals(1L,h.remoteRevision);assertEquals(Permissions(),h.remotePermissions) }
        val remote=host.snapshot()["peers"]!!.jsonArray.single().jsonObject
        assertEquals(store.key.deviceId,remote["peer_id"]!!.jsonPrimitive.content)
        assertEquals("active",remote["state"]!!.jsonPrimitive.content)
    }
    @Test fun badCertificatePinNeverDisclosesAnInvitationToken() = runBlocking<Unit> {
        val c=code();val wrong=Wire.json.encodeToString(PairCode.serializer(),c).replace(c.fingerprint,"a".repeat(64))
        expectFailure("IDENTITY_REJECTED") { runBlocking { engine.begin(PairCode.parse(wrong),"Phone") } }
        assertEquals(JsonNull,host.snapshot()["invitation"]!!.jsonObject["pending"])
    }
    @Test fun wrongPublicKeyIdIsRejectedDespiteCorrectCertificateHash() = runBlocking<Unit> {
        val c=code();val wrong=Wire.json.encodeToString(PairCode.serializer(),c).replace(c.device_id,"b".repeat(64))
        expectFailure("IDENTITY_REJECTED") { runBlocking { engine.begin(PairCode.parse(wrong),"Phone") } }
        assertEquals(JsonNull,host.snapshot()["invitation"]!!.jsonObject["pending"])
    }
    @Test fun singleUseClaimCannotBeReplayed() = runBlocking<Unit> {
        val c=code();engine.begin(c,"Phone")
        val other=PairingEngine(MemoryTrust(),connector)
        expectFailure("INVITATION_USED") { runBlocking { other.begin(c,"Second phone") } }
        assertTrue(host.snapshot()["peers"]!!.jsonArray.isEmpty())
    }
    @Test fun pendingIdentityCannotAcknowledgeBeforeOwnerApproval() = runBlocking<Unit> {
        val p=engine.begin(code(),"Phone");engine.prove(p.id)
        connector.connect(Endpoint.parse(p.endpoint),PeerPin(p.fingerprint,p.id),store.key).use {
            expectFailure("OWNER_APPROVAL_REQUIRED") { it.post<Proof,PairState>("/pair/v1/ack",Proof(invitation_id=p.invitationId,attempt_id=p.attemptId)) }
        }
        assertTrue(host.snapshot()["peers"]!!.jsonArray.isEmpty())
    }
    @Test fun noClientCertificateCannotUseTheNormalApi() = runBlocking<Unit> {
        val p=pair()
        expectFailure { connector.connect(Endpoint.parse(p.endpoint),PeerPin(p.fingerprint,p.id),null).use { it.post<Hello,Health>("/api/v1/health",Hello()) } }
    }
    @Test fun wrongPrivateIdentityCannotReuseAnotherAttempt() = runBlocking<Unit> {
        val p=engine.begin(code(),"Phone")
        expectFailure { connector.connect(Endpoint.parse(p.endpoint),PeerPin(p.fingerprint,p.id),TestIdentities.create()).use { it.post<Proof,PairState>("/pair/v1/prove",Proof(invitation_id=p.invitationId,attempt_id=p.attemptId)) } }
    }
    @Test fun lostFinalReplyRecoversIdempotentlyAfterDesktopRestart() = runBlocking<Unit> {
        val p=engine.begin(code(),"Phone");engine.prove(p.id);host.approve(p.attemptId);engine.prove(p.id)
        var once=true
        val lossy=object:PeerConnector {
            override fun closeAll()=connector.closeAll()
            override fun connect(endpoint:Endpoint,pin:PeerPin,identity:TransportIdentity?):PeerSession {
                val real=connector.connect(endpoint,pin,identity)
                return object:PeerSession {
                    override val certificate=real.certificate
                    override fun close()=real.close()
                    override fun request(path:String,body:ByteArray):ByteArray { val data=real.request(path,body);if(path=="/pair/v1/ack"&&once){once=false;fail("PEER_TIMEOUT")};return data }
                }
            }
        }
        expectFailure("PEER_TIMEOUT") { runBlocking { PairingEngine(store,lossy).finish(p.id,true) } }
        assertEquals(TrustStatus.ACK_PENDING,store.document.peer(p.id).status)
        host.restart()
        val endpoint=host.snapshot()["endpoint"]!!.jsonPrimitive.content
        // A newly allocated test listener port is an explicit manual endpoint change, never a pin change.
        store.mutate { it.replace(it.peer(p.id).copy(endpoint=endpoint)) }
        assertEquals(TrustStatus.ACTIVE,engine.recoverAcknowledgement(p.id).status)
        val remote=host.snapshot()["peers"]!!.jsonArray.single().jsonObject
        assertEquals(1,remote["grant_revision"]!!.jsonPrimitive.int)
    }
    @Test fun desktopGrantChangeIsObservedAndRevocationStopsTheClient() = runBlocking<Unit> {
        val p=pair();val (captured,s)=engine.openActive(p.id);s.use { engine.health(captured,it) }
        val remote=host.snapshot()["peers"]!!.jsonArray.single().jsonObject
        host.call("update_peer_permissions",buildJsonObject { put("peer_id",store.key.deviceId);put("expected_revision",remote["grant_revision"]!!.jsonPrimitive.int);put("permissions",Wire.json.encodeToJsonElement(Permissions(chat=true))) })
        val (fresh,session)=engine.openActive(p.id);session.use { assertTrue(engine.health(fresh,it).remotePermissions.chat) }
        val current=host.snapshot()["peers"]!!.jsonArray.single().jsonObject
        host.call("revoke_peer",buildJsonObject { put("peer_id",store.key.deviceId);put("expected_revision",current["grant_revision"]!!.jsonPrimitive.int) })
        expectFailure { runBlocking { engine.openActive(p.id).let { (peer,connection)->connection.use { engine.health(peer,it) } } } }
    }
    @Test fun localRevocationSurvivesRepositoryReloadAndNeverRePairs() = runBlocking<Unit> {
        val p=pair();engine.revoke(p.id)
        val reopened=MemoryTrust(store.key,Wire.decode<TrustDocument>(Wire.encode(store.document,Wire.MAX_STORE),Wire.MAX_STORE))
        expectFailure("DEVICE_REVOKED") { runBlocking { PairingEngine(reopened,connector).openActive(p.id) } }
        assertEquals(TrustStatus.REVOKED,reopened.document.peer(p.id).status)
    }
}
internal class RealDesktop : AutoCloseable {
    private val root=File(System.getProperty("narada.interop.scratch")).apply { mkdirs() }
    private val directory=Files.createTempDirectory(root.toPath(),"desktop-").toFile()
    private val vault=sha256(UUID.randomUUID().toString().toByteArray())
    private var process:Process?=null
    init { try { start() } catch (failure:Exception) { stop();directory.deleteRecursively();throw failure } }
    private fun start() {
        val binary=System.getProperty("narada.agent.binary");check(File(binary).isFile)
        process=ProcessBuilder(binary,"--data-dir",directory.path).apply { environment()["NARADA_DEV_VAULT_KEY"]=vault;environment()["TOKIO_WORKER_THREADS"]="2";redirectErrorStream(true);redirectOutput(File(directory,"agent.log")) }.start()
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15)
        while(System.nanoTime()<deadline) {
            if(!process!!.isAlive) error("Fresh desktop process exited; inspect excluded fixture log")
            if(runCatching { call("get_snapshot") }.isSuccess)return
            Thread.sleep(30)
        }
        error("Desktop IPC startup timeout")
    }
    fun enable() { call("configure_connections",buildJsonObject { put("enabled",true);put("bind_ip","127.0.0.1");put("api_port",0);put("discovery",false) }) }
    fun snapshot()=call("get_snapshot").jsonObject["connections"]!!.jsonObject
    fun approve(attempt:String) { call("approve_pairing",buildJsonObject { put("attempt_id",attempt);put("permissions",Wire.json.encodeToJsonElement(Permissions())) }) }
    fun restart() { stop();start() }
    fun call(type:String,payload:JsonElement?=null):JsonElement {
        val id=UUID.randomUUID().toString()
        val request=buildJsonObject { put("version",1);put("id",id);put("command",buildJsonObject { put("type",type);if(payload!=null)put("payload",payload) }) }
        SocketChannel.open(StandardProtocolFamily.UNIX).use { socket ->
            val deadline=TlsConnector.deadlines.schedule({runCatching{socket.close()}},8,TimeUnit.SECONDS)
            try {
            socket.connect(UnixDomainSocketAddress.of(File(directory,"agent.sock").toPath()))
            socket.write(ByteBuffer.wrap((request.toString()+"\n").toByteArray()))
            val result=java.io.ByteArrayOutputStream();val buffer=ByteBuffer.allocate(4096)
            while(true) {
                val n=socket.read(buffer);check(n>0&&result.size()+n<=65536)
                result.write(buffer.array(),0,n);buffer.clear();if(result.toByteArray().last()==10.toByte())break
            }
            val response=Wire.json.parseToJsonElement(result.toString(Charsets.UTF_8.name())).jsonObject
            check(response["id"]!!.jsonPrimitive.content==id)
            check(response["ok"]!!.jsonPrimitive.boolean) { "Owner RPC failed: ${response["error"]!!.jsonObject["code"]!!.jsonPrimitive.content}" }
            return response["data"]!!
            } finally { deadline.cancel(false) }
        }
    }
    /** Test-only clock aging of an isolated stopped profile, never an owner/public protocol route.
     * Exercises real Rust expiry checks without pretending that 24 wall-clock hours elapsed. */
    fun ageSourceSnapshot(id:String) {
        canonicalUuid(id);stop()
        val script="import sqlite3,sys; c=sqlite3.connect(sys.argv[1]); n=c.execute('UPDATE transfer_sources SET expires_at=0 WHERE transfer_id=?',(sys.argv[2],)).rowcount; c.commit(); c.close(); assert n==1"
        val aging=ProcessBuilder("python3","-c",script,File(directory,"narada.db").path,id).redirectErrorStream(true).start()
        check(aging.waitFor(5,TimeUnit.SECONDS)&&aging.exitValue()==0){"Disposable snapshot clock aging failed"};start()
    }
    private fun stop() { process?.let { it.destroy();if(!it.waitFor(10,TimeUnit.SECONDS)){it.destroyForcibly();it.waitFor(5,TimeUnit.SECONDS)} };process=null }
    override fun close() { stop();directory.deleteRecursively() } // Disposable test profile only; never a user profile.
}
