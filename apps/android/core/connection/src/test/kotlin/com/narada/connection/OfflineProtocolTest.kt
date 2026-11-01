package com.narada.connection

import java.util.UUID
import org.junit.Test
import org.junit.Assert.*

class OfflineProtocolTest {
    private val id = FileSamples.rootId
    private fun entry(name: String = "ordinary.bin") = SyncEntry(UUID.randomUUID().toString(), null, name, name, "file", "12", "a".repeat(64))
    @Test fun snapshotCompletesOnlyWithChangesCursor() { val p = SyncPage(id, "snapshot", listOf(entry()), emptyList(), null, UUID.randomUUID().toString(), 1); p.validate(SyncRequest("snapshot", id, null), null); expectFailure { p.copy(changes_cursor = null).validate(SyncRequest("snapshot", id, null), null) } }
    @Test fun partialSnapshotMustHaveAFullBoundedPageAndNoChangesCursor() { val values = (0..15).map { entry("$it.bin") }; val p = SyncPage(id, "snapshot", values, emptyList(), UUID.randomUUID().toString(), null, 5); p.validate(SyncRequest("snapshot", id, null), null); expectFailure { p.copy(entries = values.dropLast(1)).validate(SyncRequest("snapshot", id, null), null) } }
    @Test fun snapshotFenceCannotChangeAcrossPages() { val p = SyncPage(id, "snapshot", emptyList(), emptyList(), null, UUID.randomUUID().toString(), 2); expectFailure("INVALID_SYNC_RESPONSE") { p.validate(SyncRequest("snapshot", id, UUID.randomUUID().toString()), 1) } }
    @Test fun aCursorCannotBeReplayedAsItsOwnNextCursor() { val cursor = UUID.randomUUID().toString(); val p = SyncPage(id, "changes", emptyList(), listOf(SyncEvent(2, "deleted", UUID.randomUUID().toString(), null)), cursor, cursor, 2); expectFailure { p.validate(SyncRequest("changes", id, cursor), 1) } }
    @Test fun changesAreStrictlyOrderedAndFenceEqualsLastSequence() { val e = entry(); val cursor = UUID.randomUUID().toString(); val p = SyncPage(id, "changes", emptyList(), listOf(SyncEvent(2, "upsert", e.object_id, e), SyncEvent(3, "deleted", e.object_id, null)), null, UUID.randomUUID().toString(), 3); p.validate(SyncRequest("changes", id, cursor), 1); expectFailure { p.copy(events = p.events.reversed()).validate(SyncRequest("changes", id, cursor), 1) }; expectFailure { p.copy(fence = 4).validate(SyncRequest("changes", id, cursor), 1) } }
    @Test fun tombstonesCannotSmuggleEntriesOrRawPaths() { val e = entry(); expectFailure { SyncEvent(1, "deleted", e.object_id, e).validate() }; expectFailure { e.copy(relative_path = "../ordinary.bin").validate() } }
    @Test fun parentGraphAndDisplayPathsMustAgree() { val p = entry("folder").copy(kind = "directory"); val e = entry().copy(parent_id = p.object_id, relative_path = "folder/ordinary.bin"); SyncRules.entries(listOf(p, e), true); expectFailure { SyncRules.entries(listOf(e), true) }; expectFailure { SyncRules.entries(listOf(p, e.copy(relative_path = "other/ordinary.bin")), true) } }
    @Test fun duplicateObjectsAndExcessiveMetadataAreRefused() { val e = entry(); expectFailure { SyncRules.entries(listOf(e, e)) }; expectFailure { SyncRules.entries((0..128).map { entry("$it.bin") }) } }
    @Test fun rootAndModeAreBoundToTheRequest() { val p = SyncPage(id, "snapshot", emptyList(), emptyList(), null, UUID.randomUUID().toString(), 0); expectFailure { p.validate(SyncRequest("snapshot", UUID.randomUUID().toString(), null), null) }; expectFailure { p.copy(mode = "changes").validate(SyncRequest("snapshot", id, null), null) } }
    @Test fun strictJsonRejectsUnknownFieldsDuplicateKeysAndQuotedSequences() { val p = SyncPage(id, "snapshot", emptyList(), emptyList(), null, UUID.randomUUID().toString(), 1); val raw = Wire.encode(p).decodeToString(); listOf(raw.replaceFirst("{", "{\"owner_reindex\":true,"), raw.replaceFirst("{", "{\"fence\":1,"), raw.replace("\"fence\":1", "\"fence\":\"1\"")).forEach { expectFailure { Wire.decode<SyncPage>(it.toByteArray()) } } }
    @Test fun noRequestAcceptsAnOwnerReindexOrRawPathOperation() { listOf("reindex", "watch", "write", "delete").forEach { expectFailure { SyncRequest(it, id, null).validate() } }; expectFailure { SyncRequest("snapshot", "/private/root", null).validate() } }
    @Test fun keyScopesAndMetadataAreRedactedFromDebugStrings() { val e = entry("PRIVATE_NAME_SENTINEL.bin"); val cache = SyncCache("a".repeat(64), id); assertFalse(e.toString().contains(e.name)); assertFalse(cache.toString().contains(id)); assertFalse(cache.toString().contains("a".repeat(64))) }
    @Test fun aggregateByteQuotaCountsCommittedAndStagedMetadataTogether() {
        val f = OfflineFixture(); val scope = SyncScope.from(f.memory.document.peer(f.peer.id)); val entries = mutableListOf<SyncEntry>()
        repeat(10) { i -> val name = "dir-$i-" + "d".repeat(150); val parent = entries.lastOrNull(); entries += entry(name).copy(kind = "directory", parent_id = parent?.object_id, relative_path = listOfNotNull(parent?.relative_path, name).joinToString("/")) }
        val parent = entries.last(); repeat(118) { i -> val name = "file-$i-" + "f".repeat(150); entries += entry(name).copy(parent_id = parent.object_id, relative_path = parent.relative_path + "/" + name) }
        val cache = SyncCache(f.peer.id, f.root, entries, scope, UUID.randomUUID().toString(), 1, f.clock)
        f.memory.document.copy(syncCaches = listOf(cache)).validateOffline()
        val work = SyncWork(UUID.randomUUID().toString(), scope, "snapshot", UUID.randomUUID().toString(), 1, entries, f.clock)
        expectFailure { f.memory.document.copy(syncCaches = listOf(cache.copy(work = work))).validateOffline() }
        assertTrue(f.memory.document.syncCaches.isEmpty())
    }

}
