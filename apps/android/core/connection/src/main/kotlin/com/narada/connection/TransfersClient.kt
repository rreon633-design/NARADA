package com.narada.connection

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

data class ManifestBundle(val manifest: TransferManifest, val hashes: List<String>)
interface TransfersGateway {
    suspend fun prepare(record: DownloadRecord): ManifestBundle
    suspend fun chunk(record: DownloadRecord, index: Int): ByteArray
    suspend fun finish(record: DownloadRecord)
    fun cancel()
}
/** Borrows the existing coordinator's scoped mTLS session; does not own a socket or a worker. */
class TransfersClient(private val repository: TrustRepository, private val pairing: PairingEngine) {
    private suspend fun fence(peer: TrustedPeer, record: DownloadRecord, guard: () -> Unit) {
        currentCoroutineContext().ensureActive(); guard()
        val doc = repository.read() ?: fail("IDENTITY_NOT_READY"); val p = doc.peer(peer.id)
        val saved = doc.downloads.singleOrNull { it.id == record.id } ?: fail("TRANSFER_INTENT_MISSING")
        demand(saved.syncRequired == record.syncRequired && saved.peerId == record.peerId && saved.pairingAttempt == record.pairingAttempt && saved.source == record.source && saved.name == record.name && saved.size == record.size && saved.manifest == record.manifest && saved.hashes == record.hashes && saved.state == record.state && saved.runId == record.runId && saved.receipt == record.receipt, "TRANSFER_STOPPED")
        demand(record.state == DownloadState.RUNNING || record.state == DownloadState.VERIFIED && record.receipt != null, "TRANSFER_STOPPED")
        demand(doc.enabled && p.status == TrustStatus.ACTIVE && !p.blocked && p.failures < 6 && p.id == record.peerId && p.attemptId == record.pairingAttempt && p.localEpoch == peer.localEpoch && p.fingerprint == peer.fingerprint, "TRANSFER_SCOPE_CHANGED")
        demand(p.remoteRevision == peer.remoteRevision && p.remoteEpoch == peer.remoteEpoch && p.remotePermissions == peer.remotePermissions && p.remoteOperations == peer.remoteOperations, "TRANSFER_ACCESS_CHANGED")
    }
    private fun authorize(peer: TrustedPeer, record: DownloadRecord) {
        demand("transfers.v1" in peer.remoteOperations, "TRANSFERS_UNSUPPORTED")
        if (record.syncRequired) demand("sync.v1" in peer.remoteOperations && peer.remotePermissions.roots.singleOrNull { it.root_id == record.source.root_id }?.capabilities?.contains("sync_read") == true, "SYNC_ACCESS_DENIED")
        demand(peer.remotePermissions.roots.singleOrNull { it.root_id == record.source.root_id }?.capabilities?.containsAll(listOf("browse", "read")) == true, "ACCESS_DENIED")
    }
    private suspend fun before(peer: TrustedPeer, record: DownloadRecord, session: PeerSession, guard: () -> Unit): TrustedPeer {
        record.validate(); fence(peer, record, guard)
        val fresh = pairing.health(peer, session); fence(fresh, record, guard); authorize(fresh, record); return fresh
    }
    private suspend fun after(peer: TrustedPeer, record: DownloadRecord, session: PeerSession, guard: () -> Unit) {
        fence(peer, record, guard); pairing.health(peer, session); fence(peer, record, guard); authorize(peer, record)
    }
    suspend fun prepare(peer: TrustedPeer, session: PeerSession, record: DownloadRecord, guard: () -> Unit = {}): ManifestBundle {
        demand(record.state == DownloadState.RUNNING, "TRANSFER_STOPPED")
        val p = before(peer, record, session, guard)
        val status: TransferStatusReply = session.post(TransferRules.PATH, TransferWire(action = buildJsonObject {
            put("kind", if (record.syncRequired) "prepare_sync" else "prepare"); put("transfer_id", record.id); put("source", Wire.json.encodeToJsonElement(TransferSource.serializer(), record.source))
        }))
        val m = status.source(record); val hashes = mutableListOf<String>()
        // At most eight pages + prepare + two health calls (11 requests) on a fresh lease.
        var offset = 0
        do {
            fence(p, record, guard)
            val page: TransferManifestPage = session.post(TransferRules.PATH, TransferWire(action = buildJsonObject {
                put("kind", "manifest"); put("transfer_id", record.id); put("offset", offset)
            }))
            page.validate(record.id, m, offset); hashes.addAll(page.hashes)
            val next = page.next_offset ?: break; offset = next
        } while (true)
        m.verify(hashes); after(p, record, session, guard)
        return ManifestBundle(m, hashes.toList())
    }
    suspend fun chunk(peer: TrustedPeer, session: PeerSession, record: DownloadRecord, index: Int, guard: () -> Unit = {}): ByteArray {
        demand(record.state == DownloadState.RUNNING, "TRANSFER_STOPPED")
        val m = record.manifest ?: fail("INVALID_MANIFEST"); val length = m.length(index)
        val p = before(peer, record, session, guard)
        val bytes = session.getChunk(record.id, index, length)
        try {
            demand(bytes.size == length && sha256(bytes) == record.hashes[index], "HASH_MISMATCH")
            after(p, record, session, guard); return bytes
        } catch (e: Exception) { bytes.fill(0); throw e }
    }
    suspend fun finish(peer: TrustedPeer, session: PeerSession, record: DownloadRecord, guard: () -> Unit = {}) {
        val receipt = record.receipt ?: fail("TRANSFER_NOT_VERIFIED")
        demand(record.state == DownloadState.VERIFIED, "TRANSFER_NOT_VERIFIED")
        val p = before(peer, record, session, guard)
        val result: TransferFinished = session.post(TransferRules.PATH, TransferWire(action = buildJsonObject {
            put("kind", "finish"); put("transfer_id", record.id); put("sha256", receipt.sha256)
        }))
        demand(result.acknowledged, "INVALID_TRANSFER_RESPONSE"); after(p, record, session, guard)
    }
}
