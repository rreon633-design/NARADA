package com.narada.connection

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class SyncTicket(val peerId: String, val rootId: String, val work: SyncWork) { override fun toString() = "SyncTicket([protected cursor])" }
class SyncResult(val page: SyncPage, val scope: SyncScope)
interface SyncGateway { suspend fun page(ticket: SyncTicket): SyncResult; fun cancel() }
/** Read + SyncRead only. No owner reindex, raw path API, phone listener or filesystem writer. */
class SyncClient(private val repository: TrustRepository, private val pairing: PairingEngine) {
    private suspend fun fence(peer: TrustedPeer, ticket: SyncTicket, guard: () -> Unit) {
        currentCoroutineContext().ensureActive(); guard()
        val doc = repository.read() ?: fail("IDENTITY_NOT_READY"); ticket.work.scope.check(doc, ticket.rootId)
        demand(SyncScope.from(peer) == ticket.work.scope && ticket.peerId == peer.id && doc.syncCache(peer.id, ticket.rootId).work == ticket.work, "SYNC_SCOPE_CHANGED")
    }
    suspend fun page(peer: TrustedPeer, session: PeerSession, ticket: SyncTicket, guard: () -> Unit = {}): SyncResult {
        fence(peer, ticket, guard)
        val p = pairing.health(peer, session); fence(p, ticket, guard)
        val request = SyncRequest(ticket.work.kind, ticket.rootId, ticket.work.cursor)
        request.validate()
        val page: SyncPage = session.post(SyncRules.PATH, SyncWire(request = request))
        page.validate(request, ticket.work.fence)
        fence(p, ticket, guard); pairing.health(p, session); fence(p, ticket, guard)
        return SyncResult(page, SyncScope.from(p))
    }
}
class PacedSyncGateway(private val delegate: SyncGateway, private val admission: ContentAdmission) : SyncGateway {
    override suspend fun page(ticket: SyncTicket) = admission.admit { delegate.page(ticket) }
    override fun cancel() = delegate.cancel()
}
fun syncRemoteFailure(code: String): Nothing = fail(when (code) {
    "STORAGE_UNAVAILABLE" -> "SYNC_REMOTE_STORAGE"
    in setOf("CURSOR_EXPIRED", "SYNC_BUSY", "SYNC_SNAPSHOT_LIMIT", "ACCESS_DENIED", "ROOT_UNAVAILABLE", "ROOT_NOT_FOUND", "INDEX_UNAVAILABLE", "DEVICE_REVOKED", "PERMISSION_DENIED", "RATE_LIMITED", "NETWORK_DISABLED", "PROTOCOL_INCOMPATIBLE", "SESSION_INVALIDATED") -> code
    else -> "PEER_REJECTED"
})
