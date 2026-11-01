package com.narada.connection

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class PeerFixture(val repository: MemoryTrust = MemoryTrust()) : PeerConnector {
    val host = TestIdentities.create(server = true)
    val code = TestIdentities.code(host)
    var approved = false
    var active = false
    var ackCount = 0
    var loseAck = false
    var health = Health(Hello(),host.deviceId,Permissions(),1,1,listOf("health","permissions"))
    var onConnect: (() -> Unit)? = null
    var requestCount = 0
    var closed = 0
    val engine = PairingEngine(repository,this)
    override fun closeAll() { closed++ }
    override fun connect(endpoint: Endpoint, pin: PeerPin, identity: TransportIdentity?): PeerSession {
        onConnect?.invoke()
        return object : PeerSession {
            override val certificate=host.certificate.encoded
            override fun close() {}
            override fun request(path: String, body: ByteArray): ByteArray {
                requestCount++
                return when(path) {
                    "/pair/v1/claim" -> {
                        val claim=Wire.decode<Claim>(body)
                        assertEquals(TrustStatus.CLAIMING,repository.document.peers.single().status)
                        Wire.encode(Envelope(true,ClaimReply(Hello(),claim.attempt_id,12346,"Test desktop","linux",Base64.getEncoder().encodeToString(certificate)),null))
                    }
                    "/pair/v1/prove" -> Wire.encode(Envelope(true,PairState(Hello(),if(approved) "approved" else "awaiting_owner",Permissions()),null))
                    "/pair/v1/ack" -> {
                        assertEquals(TrustStatus.ACK_PENDING,repository.document.peers.single().status)
                        demand(approved,"OWNER_APPROVAL_REQUIRED");active=true;ackCount++
                        if(loseAck){loseAck=false;fail("PEER_TIMEOUT")}
                        Wire.encode(Envelope(true,PairState(Hello(),"active",Permissions()),null))
                    }
                    else -> Wire.encode(Envelope(true,health,null))
                }
            }
        }
    }
    suspend fun paired(): TrustedPeer { val p=engine.begin(code,"Phone");approved=true;engine.prove(p.id);return engine.finish(p.id,true) }
}
class PairingEngineTest {
    @Test fun claimAndProofDoNotActivateTrust() = runBlocking<Unit> { val f=PeerFixture();val p=f.engine.begin(f.code,"Phone");assertEquals(TrustStatus.WAITING,p.status);assertEquals(TrustStatus.WAITING,f.engine.prove(p.id).status);assertFalse(f.active);assertEquals(0,f.ackCount) }
    @Test fun ownerApprovalAndPhoneConfirmationAreSeparate() = runBlocking<Unit> { val f=PeerFixture();val p=f.engine.begin(f.code,"Phone");expectFailure { runBlocking { f.engine.finish(p.id,true) } };f.approved=true;f.engine.prove(p.id);expectFailure("LOCAL_CONFIRMATION_REQUIRED") { runBlocking { f.engine.finish(p.id,false) } };assertEquals(0,f.ackCount);assertEquals(TrustStatus.ACTIVE,f.engine.finish(p.id,true).status) }
    @Test fun acknowledgedTrustStillNeedsAuthenticatedHealth() = runBlocking<Unit> { val f=PeerFixture();val p=f.paired();assertEquals(0L,p.remoteRevision);val (captured,s)=f.engine.openActive(p.id);s.use { val checked=f.engine.health(captured,it);assertEquals(1L,checked.remoteRevision) } }
    @Test fun ackIsPersistedBeforeSendingAndLostReplyCanRecover() = runBlocking<Unit> { val f=PeerFixture();val p=f.engine.begin(f.code,"Phone");f.approved=true;f.engine.prove(p.id);f.loseAck=true;expectFailure("PEER_TIMEOUT") { runBlocking { f.engine.finish(p.id,true) } };assertTrue(f.active);assertEquals(TrustStatus.ACK_PENDING,f.repository.document.peer(p.id).status);assertEquals(TrustStatus.ACTIVE,f.engine.recoverAcknowledgement(p.id).status);assertEquals(2,f.ackCount) }
    @Test fun failedPendingWriteCannotSendAcknowledgement() = runBlocking<Unit> { val f=PeerFixture();val p=f.engine.begin(f.code,"Phone");f.approved=true;f.engine.prove(p.id);f.repository.failWrite=true;expectFailure("STORAGE_UNAVAILABLE") { runBlocking { f.engine.finish(p.id,true) } };assertEquals(0,f.ackCount) }
    @Test fun tokenNeverEntersSerializableTrustState() = runBlocking<Unit> { val f=PeerFixture();f.paired();assertFalse(Wire.json.encodeToString(TrustDocument.serializer(),f.repository.document).contains(f.code.token));assertTrue(f.repository.receipts.all { !Wire.json.encodeToString(TrustDocument.serializer(),it).contains(f.code.token) }) }
    @Test fun cancellationTombstoneBlocksLateAcknowledgement() = runBlocking<Unit> { val f=PeerFixture();val p=f.engine.begin(f.code,"Phone");f.approved=true;f.engine.prove(p.id);f.loseAck=true;expectFailure { runBlocking { f.engine.finish(p.id,true) } };f.engine.revoke(p.id);expectFailure { runBlocking { f.engine.recoverAcknowledgement(p.id) } };assertEquals(TrustStatus.REVOKED,f.repository.document.peer(p.id).status) }
    @Test fun revokedPeerCannotOpenAnotherSession() = runBlocking<Unit> { val f=PeerFixture();val p=f.paired();f.engine.revoke(p.id);expectFailure("DEVICE_REVOKED") { runBlocking { f.engine.openActive(p.id) } };assertTrue(f.closed>0) }
    @Test fun revokeFencesAHandshakeCompletingAfterCancellation() = runBlocking<Unit> { val f=PeerFixture();val p=f.paired();val before=f.requestCount;f.onConnect={runBlocking { f.engine.revoke(p.id) }};expectFailure("PAIRING_STATE_CHANGED") { runBlocking { f.engine.openActive(p.id) } };assertEquals(before,f.requestCount) }
    @Test fun lateHealthCannotRestoreRevokedPermissions() = runBlocking<Unit> { val f=PeerFixture();val p=f.paired();val (captured,s)=f.engine.openActive(p.id);f.engine.revoke(p.id);expectFailure { runBlocking { f.engine.health(captured,s) } };s.close();assertEquals(Permissions(),f.repository.document.peer(p.id).remotePermissions) }
    @Test fun endpointChangesPreserveBothIdentityPinsAndFenceOldSessions() = runBlocking<Unit> { val f=PeerFixture();val p=f.paired();val (captured,s)=f.engine.openActive(p.id);f.engine.setEndpoint(p.id,"192.168.0.10:12345");val changed=f.repository.document.peer(p.id);assertEquals(p.fingerprint,changed.fingerprint);assertEquals(p.id,changed.id);assertTrue(changed.localEpoch>p.localEpoch);expectFailure { runBlocking { f.engine.health(captured,s) } };s.close() }
    @Test fun remoteRevisionRollbackIsRejected() = runBlocking<Unit> { val f=PeerFixture();val p=f.paired();val (captured,s)=f.engine.openActive(p.id);s.use { f.health=f.health.copy(grant_revision=2,session_epoch=2);f.engine.health(captured,it);f.health=f.health.copy(grant_revision=1,session_epoch=1);expectFailure("REMOTE_STATE_ROLLBACK") { runBlocking { f.engine.health(captured,it) } } } }
    @Test fun grantsCannotChangeWithoutAuthorizationCounters() = runBlocking<Unit> { val f=PeerFixture();val p=f.paired();val (captured,s)=f.engine.openActive(p.id);s.use { f.engine.health(captured,it);f.health=f.health.copy(permissions=Permissions(chat=true));expectFailure("INVALID_GRANTS") { runBlocking { f.engine.health(captured,it) } } } }
    @Test fun onlyOnePendingPairIsAllowed() = runBlocking<Unit> { val f=PeerFixture();f.engine.begin(f.code,"Phone");val second=TestIdentities.code(TestIdentities.create(true));expectFailure("PAIRING_IN_PROGRESS") { runBlocking { f.engine.begin(second,"Phone") } } }
    @Test fun actualSixteenRecordLimitCountsRevocationTombstones() = runBlocking<Unit> {
        val f=PeerFixture()
        f.repository.document=f.repository.document.copy(peers=List(16) { index ->
            TrustedPeer(sha256("peer-$index".toByteArray()),"a".repeat(64),name="Old device",endpoint="192.168.0.1:12345",invitationId=java.util.UUID.randomUUID().toString(),attemptId=java.util.UUID.randomUUID().toString(),expiresAt=System.currentTimeMillis(),status=TrustStatus.REVOKED)
        })
        f.repository.document.validate()
        expectFailure("PEER_LIMIT") { runBlocking { f.engine.begin(f.code,"Phone") } }
        assertEquals(0,f.requestCount);assertEquals(16,f.repository.document.peers.size)
    }
    @Test fun disableDuringHandshakeFencesTheClaimBody() = runBlocking<Unit> {
        val f=PeerFixture();f.onConnect={f.repository.document=f.repository.document.copy(enabled=false)}
        expectFailure("NETWORK_DISABLED") { runBlocking { f.engine.begin(f.code,"Phone") } }
        assertEquals(0,f.requestCount)
    }
    @Test fun disabledConnectionsNeverSubmitClaim() = runBlocking<Unit> { val f=PeerFixture();f.repository.document=f.repository.document.copy(enabled=false);expectFailure("NETWORK_DISABLED") { runBlocking { f.engine.begin(f.code,"Phone") } };assertEquals(0,f.requestCount) }
    @Test fun recoveredStoreValidatesIdentityAndDoesNotPersistOnlineState() { val f=PeerFixture();val restored=Wire.decode<TrustDocument>(Wire.encode(f.repository.document,Wire.MAX_STORE),Wire.MAX_STORE);restored.validate();assertFalse(Wire.json.encodeToString(TrustDocument.serializer(),restored).contains("connected"));expectFailure("IDENTITY_CHANGED") { restored.copy(deviceId="a".repeat(64)).validate() } }
}
