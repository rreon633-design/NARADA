package com.narada.connection

import kotlinx.serialization.Serializable

object SyncRules {
    const val PATH = "/api/v1/sync"
    const val MAX_ROOTS = 2
    const val MAX_ENTRIES = 128
    const val METADATA_BYTES = 393_216
    const val MAX_PINS = 4
    fun authority(doc: TrustDocument, peerId: String, attempt: String, rootId: String): TrustedPeer {
        FileRules.id(rootId); val p = doc.peer(peerId)
        demand(doc.enabled && p.status == TrustStatus.ACTIVE && !p.blocked && p.failures < 6 && p.attemptId == attempt, "SYNC_SCOPE_CHANGED")
        demand(doc.peers.all { it.status in setOf(TrustStatus.ACTIVE, TrustStatus.REVOKED) }, "PAIRING_IN_PROGRESS")
        demand("sync.v1" in p.remoteOperations && p.remotePermissions.roots.singleOrNull { it.root_id == rootId }?.capabilities?.containsAll(listOf("read", "sync_read")) == true, "SYNC_ACCESS_DENIED")
        return p
    }
    fun entries(entries: List<SyncEntry>, complete: Boolean = false) {
        demand(entries.size <= MAX_ENTRIES && entries.map { it.object_id }.distinct().size == entries.size && entries.map { it.relative_path }.distinct().size == entries.size, "SYNC_LOCAL_LIMIT")
        entries.forEach(SyncEntry::validate)
        if (complete) entries.forEach { e ->
            if (e.parent_id == null) demand('/' !in e.relative_path, "INVALID_SYNC_RESPONSE")
            else {
                val parent = entries.singleOrNull { it.object_id == e.parent_id } ?: fail("INVALID_SYNC_RESPONSE")
                demand(parent.kind == "directory" && e.relative_path.substringBeforeLast('/') == parent.relative_path && e.relative_path != parent.relative_path, "INVALID_SYNC_RESPONSE")
            }
        }
    }
}
@Serializable data class SyncScope(val peerId: String, val attempt: String, val fingerprint: String, val localEpoch: Long, val revision: Long, val epoch: Long, val permissions: Permissions, val operations: List<String>) {
    fun check(doc: TrustDocument, rootId: String) { val p = SyncRules.authority(doc, peerId, attempt, rootId); demand(this == from(p), "SYNC_SCOPE_CHANGED") }
    fun validate() { validHash(peerId); validHash(fingerprint); canonicalUuid(attempt); permissions.validate(); demand(localEpoch > 0 && revision > 0 && epoch > 0, "TRUST_STORE_INVALID"); Health(Hello(), peerId, permissions, revision, epoch, operations).validate(peerId) }
    companion object { fun from(p: TrustedPeer) = SyncScope(p.id, p.attemptId, p.fingerprint, p.localEpoch, p.remoteRevision, p.remoteEpoch, p.remotePermissions, p.remoteOperations) }
    override fun toString() = "SyncScope([protected authority])"
}
@Serializable data class SyncEntry(val object_id: String, val parent_id: String?, val name: String, val relative_path: String, val kind: String, val size: String, val version: String) {
    fun validate() { FileRules.id(object_id); parent_id?.let(FileRules::id); FileRules.name(name); FileRules.relativeLabel(relative_path, name); FileRules.version(version); FileRules.decimal(size); demand(parent_id != object_id && kind in setOf("file", "directory"), "INVALID_SYNC_RESPONSE") }
    override fun toString() = "SyncEntry([protected display-only metadata])"
}
@Serializable data class SyncEvent(val sequence: Long, val kind: String, val object_id: String, val entry: SyncEntry?) {
    fun validate() {
        FileRules.id(object_id); demand(sequence > 0 && kind in setOf("upsert", "deleted"), "INVALID_SYNC_RESPONSE")
        if (kind == "upsert") { val e = entry ?: fail("INVALID_SYNC_RESPONSE"); e.validate(); demand(e.object_id == object_id, "INVALID_SYNC_RESPONSE") }
        else demand(entry == null, "INVALID_SYNC_RESPONSE")
    }
}
@Serializable data class SyncRequest(val kind: String, val root_id: String, val cursor: String?) {
    fun validate() { FileRules.id(root_id); cursor?.let(::canonicalUuid); demand(kind in setOf("snapshot", "changes") && (kind != "changes" || cursor != null), "INVALID_SYNC_REQUEST") }
}
@Serializable data class SyncWire(val hello: Hello = Hello(), val version: Int = 1, val request: SyncRequest)
@Serializable data class SyncPage(val root_id: String, val mode: String, val entries: List<SyncEntry>, val events: List<SyncEvent>, val next_cursor: String?, val changes_cursor: String?, val fence: Long) {
    fun validate(request: SyncRequest, previousFence: Long?) {
        request.validate(); demand(root_id == request.root_id && mode == request.kind && fence >= 0 && entries.size <= 16 && events.size <= 16, "INVALID_SYNC_RESPONSE")
        next_cursor?.let(::canonicalUuid); changes_cursor?.let(::canonicalUuid)
        demand(next_cursor == null || next_cursor != request.cursor, "INVALID_SYNC_RESPONSE")
        if (mode == "snapshot") {
            demand(events.isEmpty() && (previousFence == null || fence == previousFence) && (next_cursor == null) == (changes_cursor != null) && (next_cursor == null || entries.size == 16), "INVALID_SYNC_RESPONSE")
            SyncRules.entries(entries)
        } else {
            demand(entries.isEmpty() && changes_cursor != null && (next_cursor == null || next_cursor == changes_cursor && events.isNotEmpty()), "INVALID_SYNC_RESPONSE")
            var last = previousFence ?: fail("INVALID_SYNC_RESPONSE")
            events.forEach { it.validate(); demand(it.sequence > last, "INVALID_SYNC_RESPONSE"); last = it.sequence }
            demand(fence == last, "INVALID_SYNC_RESPONSE")
        }
    }
}
@Serializable data class SyncWork(val id: String, val scope: SyncScope, val kind: String, val cursor: String?, val fence: Long?, val entries: List<SyncEntry>, val startedAt: Long) {
    fun validate() { canonicalUuid(id); scope.validate(); demand(kind in setOf("snapshot", "changes") && startedAt > 0, "TRUST_STORE_INVALID"); cursor?.let(::canonicalUuid); demand(kind != "changes" || cursor != null && fence != null, "TRUST_STORE_INVALID"); demand(fence == null || fence >= 0, "TRUST_STORE_INVALID"); SyncRules.entries(entries) }
    override fun toString() = "SyncWork([protected partial snapshot])"
}
@Serializable data class SyncCache(val peerId: String, val rootId: String, val entries: List<SyncEntry> = emptyList(), val scope: SyncScope? = null, val cursor: String? = null, val fence: Long = 0, val refreshedAt: Long? = null, val work: SyncWork? = null, val error: String? = null) {
    fun validate() {
        validHash(peerId); FileRules.id(rootId); SyncRules.entries(entries, complete = true); scope?.validate(); cursor?.let(::canonicalUuid); work?.validate()
        demand(fence >= 0 && (refreshedAt == null || refreshedAt > 0) && (refreshedAt == null) == (scope == null) && (scope == null || scope.peerId == peerId) && (work == null || work.scope.peerId == peerId), "TRUST_STORE_INVALID")
        demand(refreshedAt != null || entries.isEmpty() && cursor == null, "TRUST_STORE_INVALID")
        demand(error == null || error.matches(Regex("[A-Z][A-Z0-9_]{0,63}")), "TRUST_STORE_INVALID")
    }
    override fun toString() = "SyncCache([protected metadata; NOT byte availability])"
}
@Serializable data class OfflinePin(
    val id: String, val peerId: String, val pairingAttempt: String, val rootId: String, val objectId: String, val createdAt: Long,
    val observed: SyncEntry?, val observedAt: Long, val tombstone: Boolean = false,
    val current: String? = null, val candidate: String? = null, val previous: List<String> = emptyList(),
) {
    fun references(): List<String> = listOfNotNull(current, candidate) + previous
    fun validate(doc: TrustDocument) {
        canonicalUuid(id); validHash(peerId); canonicalUuid(pairingAttempt); FileRules.id(rootId); FileRules.id(objectId)
        demand(createdAt > 0 && observedAt > 0 && tombstone == (observed == null) && previous.size <= 1, "PIN_STATE_INVALID")
        observed?.let { it.validate(); demand(it.object_id == objectId, "PIN_STATE_INVALID") }
        val refs = references(); demand(refs.distinct().size == refs.size, "PIN_STATE_INVALID")
        refs.forEach { id -> canonicalUuid(id); val d = doc.download(id); demand(d.peerId == peerId && d.pairingAttempt == pairingAttempt && d.syncRequired && d.source.root_id == rootId && d.source.object_id == objectId, "PIN_STATE_INVALID") }
        (listOfNotNull(current) + previous).forEach { id -> demand(doc.download(id).receipt != null, "PIN_STATE_INVALID") }
    }
    override fun toString() = "OfflinePin([protected references])"
}
@Serializable private data class MetadataBudget(val caches: List<SyncCache>, val pins: List<OfflinePin>)
fun TrustDocument.validateOffline() {
    demand(syncCaches.size <= SyncRules.MAX_ROOTS && syncCaches.map { it.peerId to it.rootId }.distinct().size == syncCaches.size && pins.size <= SyncRules.MAX_PINS && pins.map { it.id }.distinct().size == pins.size && pins.map { Triple(it.peerId, it.rootId, it.objectId) }.distinct().size == pins.size, "TRUST_STORE_INVALID")
    syncCaches.forEach { it.validate(); peer(it.peerId) }; pins.forEach { it.validate(this); peer(it.peerId) }
    val bytes = Wire.encode(MetadataBudget(syncCaches, pins), SyncRules.METADATA_BYTES); bytes.fill(0)
}
fun TrustDocument.syncCache(peerId: String, rootId: String) = syncCaches.singleOrNull { it.peerId == peerId && it.rootId == rootId } ?: fail("SYNC_NOT_FOUND")
fun TrustDocument.replaceSync(cache: SyncCache): TrustDocument = copy(version = maxOf(version, 6), syncCaches = syncCaches.filterNot { it.peerId == cache.peerId && it.rootId == cache.rootId } + cache)
fun TrustDocument.pin(id: String) = pins.singleOrNull { it.id == id } ?: fail("PIN_NOT_FOUND")
fun TrustDocument.replacePin(pin: OfflinePin): TrustDocument = copy(version = maxOf(version, 6), pins = pins.map { if (it.id == pin.id) pin else it })
fun TrustDocument.downloadReferenced(id: String): Boolean = pins.any { id in it.references() }
