package com.narada.connection

import java.util.Locale

/** In-memory foundation ONLY. No wire/schema annotations, IO, receipts, grants or scheduling.
 * Adapters must supply complete logical snapshots and recheck authority/opaque parents at execution.
 * Display labels are never filesystem paths. Durable provenance and version publication are not yet
 * implemented: callers must not present this planner as functioning two-way folder sync. */
enum class FolderSide { PHONE, DESKTOP }
data class FolderScope(
    val linkId: String, val peerId: String, val pairingAttempt: String,
    val phoneRootId: String, val desktopRootId: String, val authorityRevision: Long,
) {
    fun validate() {
        listOf(linkId, pairingAttempt, phoneRootId, desktopRootId).forEach(FileRules::id)
        FileRules.version(peerId); demand(authorityRevision > 0, "FOLDER_SCOPE_CHANGED")
    }
    override fun toString() = "FolderScope([protected])"
}
data class FolderFile(val objectId: String, val relativeLabel: String, val version: String, val sha256: String, val size: Long) {
    internal fun validate() {
        FileRules.id(objectId); FileRules.version(version); FileRules.version(sha256)
        folderLabel(relativeLabel); demand(size >= 0, "INVALID_FOLDER_SNAPSHOT")
        if (size == 0L) demand(sha256 == com.narada.connection.sha256(byteArrayOf()), "INVALID_FOLDER_SNAPSHOT")
    }
    override fun toString() = "FolderFile([protected])"
}
data class FolderSnapshot(val scope: FolderScope, val side: FolderSide, val complete: Boolean, val files: List<FolderFile>) {
    override fun toString() = "FolderSnapshot([protected])"
}
data class FolderCommonFile(val relativeLabel: String, val phoneObjectId: String, val desktopObjectId: String, val sha256: String, val size: Long) {
    override fun toString() = "FolderCommonFile([protected])"
}
data class FolderCheckpoint(val scope: FolderScope, val files: List<FolderCommonFile>) {
    override fun toString() = "FolderCheckpoint([protected])"
}
/** Keys are scoped historical/pending intents, NOT evidence that bytes are currently available.
 * A future sealed ledger must retain and validate them, including uncertain publication outcomes. */
data class FolderRecordedIntents(val scope: FolderScope, val keys: Set<String> = emptySet()) {
    override fun toString() = "FolderRecordedIntents([protected])"
}
data class FolderCapacity(val slots: Int, val bytes: Long)
enum class FolderDecisionKind {
    CONTENT_EQUIVALENT, COPY_NEW, COPY_RETAINED_VERSION, REVIEW_CONFLICT, REVIEW_MISSING,
    REVIEW_RENAME, REVIEW_NAME_COLLISION, REVIEW_IDENTITY, REVIEW_FILE_LIMIT, WAIT_CAPACITY, ALREADY_RECORDED,
}
data class FolderDecision(
    val relativeLabel: String, val kind: FolderDecisionKind,
    val from: FolderSide? = null, val source: FolderFile? = null, val operationKey: String? = null,
) {
    val proposesCopy get() = kind == FolderDecisionKind.COPY_NEW || kind == FolderDecisionKind.COPY_RETAINED_VERSION
    override fun toString() = "FolderDecision($kind, [protected])"
}
data class FolderPlan(val decisions: List<FolderDecision>, val admittedBytes: Long, val admittedFiles: Int) {
    override fun toString() = "FolderPlan($admittedFiles proposals, [protected])"
}
private fun folderLabel(label: String) {
    FileRules.text(label, 2048)
    val parts = label.split('/')
    demand(parts.size in 1..32, "INVALID_FOLDER_SNAPSHOT")
    parts.forEach(FileRules::newName)
}
private fun folderKey(label: String) = label.lowercase(Locale.ROOT)

object FolderReconciler {
    const val MAX_ENTRIES = 512
    const val MAX_RECORDED = 1024

    fun plan(scope: FolderScope, phone: FolderSnapshot, desktop: FolderSnapshot, checkpoint: FolderCheckpoint,
             recorded: FolderRecordedIntents, capacity: FolderCapacity): FolderPlan {
        scope.validate()
        demand(phone.scope == scope && desktop.scope == scope && checkpoint.scope == scope && recorded.scope == scope,
            "FOLDER_SCOPE_CHANGED")
        demand(phone.side == FolderSide.PHONE && desktop.side == FolderSide.DESKTOP, "FOLDER_SCOPE_CHANGED")
        demand(phone.complete && desktop.complete, "FOLDER_SCAN_INCOMPLETE")
        demand(capacity.slots in 0..TransferRules.MAX_JOBS && capacity.bytes in 0..TransferRules.QUOTA, "FOLDER_CAPACITY_INVALID")
        demand(recorded.keys.size <= MAX_RECORDED, "FOLDER_RECORD_LIMIT")
        recorded.keys.forEach(FileRules::version)
        listOf(phone, desktop).forEach { s ->
            demand(s.files.size <= MAX_ENTRIES, "FOLDER_ENTRY_LIMIT")
            s.files.forEach { it.validate() }
            demand(s.files.map { it.objectId }.distinct().size == s.files.size, "INVALID_FOLDER_SNAPSHOT")
        }
        demand(checkpoint.files.size <= MAX_ENTRIES, "FOLDER_ENTRY_LIMIT")
        checkpoint.files.forEach { b ->
            folderLabel(b.relativeLabel); FileRules.id(b.phoneObjectId); FileRules.id(b.desktopObjectId); FileRules.version(b.sha256)
            demand(b.size >= 0 && (b.size != 0L || b.sha256 == sha256(byteArrayOf())), "INVALID_FOLDER_CHECKPOINT")
        }
        demand(checkpoint.files.map { folderKey(it.relativeLabel) }.distinct().size == checkpoint.files.size &&
            checkpoint.files.map { it.phoneObjectId }.distinct().size == checkpoint.files.size &&
            checkpoint.files.map { it.desktopObjectId }.distinct().size == checkpoint.files.size, "INVALID_FOLDER_CHECKPOINT")
        // A valid digest cannot describe two different lengths. Refuse inconsistent evidence even
        // across different labels, instead of allowing an apparent duplicate to suppress work.
        val sizes = mutableMapOf<String, Long>()
        fun remember(hash: String, size: Long) { demand(sizes.putIfAbsent(hash, size)?.let { it == size } != false, "INVALID_FOLDER_SNAPSHOT") }
        (phone.files + desktop.files).forEach { remember(it.sha256, it.size) }
        checkpoint.files.forEach { remember(it.sha256, it.size) }
        val left = phone.files.groupBy { folderKey(it.relativeLabel) }
        val right = desktop.files.groupBy { folderKey(it.relativeLabel) }
        val base = checkpoint.files.associateBy { folderKey(it.relativeLabel) }
        val oldPhone = checkpoint.files.associate { it.phoneObjectId to folderKey(it.relativeLabel) }
        val oldDesktop = checkpoint.files.associate { it.desktopObjectId to folderKey(it.relativeLabel) }
        val renamedKeys = mutableSetOf<String>()
        fun renames(files: List<FolderFile>, old: Map<String, String>) { files.forEach { f ->
            val key = folderKey(f.relativeLabel); old[f.objectId]?.takeIf { it != key }?.let { renamedKeys.add(it); renamedKeys.add(key) }
        } }
        renames(phone.files, oldPhone); renames(desktop.files, oldDesktop)
        // A file cannot also be a destination parent (including case-folded variants).
        val allKeys = left.keys + right.keys + base.keys
        val hierarchyConflicts = mutableSetOf<String>()
        allKeys.forEach { key ->
            var parent = key.substringBeforeLast('/', "")
            while (parent.isNotEmpty()) {
                if (parent in allKeys) { hierarchyConflicts.add(parent); hierarchyConflicts.add(key) }
                parent = parent.substringBeforeLast('/', "")
            }
        }
        var remaining = capacity.bytes
        var slots = capacity.slots
        val decisions = allKeys.sorted().map { key ->
            val a = left[key].orEmpty(); val b = right[key].orEmpty(); val previous = base[key]
            val p = a.singleOrNull(); val d = b.singleOrNull()
            val label = (a + b).minOfOrNull { it.relativeLabel } ?: previous!!.relativeLabel
            fun review(kind: FolderDecisionKind) = FolderDecision(label, kind)
            fun copy(side: FolderSide, file: FolderFile, kind: FolderDecisionKind): FolderDecision {
                val op = operationKey(scope, side, file)
                val result = when {
                    op in recorded.keys -> FolderDecisionKind.ALREADY_RECORDED
                    file.size > TransferRules.MAX_BYTES -> FolderDecisionKind.REVIEW_FILE_LIMIT
                    slots == 0 || file.size > remaining -> FolderDecisionKind.WAIT_CAPACITY
                    else -> { slots--; remaining -= file.size; kind }
                }
                return FolderDecision(label, result, side, file, op)
            }
            when {
                key in hierarchyConflicts || a.size > 1 || b.size > 1 || (p != null && d != null && p.relativeLabel != d.relativeLabel) ||
                    (previous != null && listOfNotNull(p, d).any { it.relativeLabel != previous.relativeLabel }) -> review(FolderDecisionKind.REVIEW_NAME_COLLISION)
                key in renamedKeys -> review(FolderDecisionKind.REVIEW_RENAME)
                p == null && d == null -> review(FolderDecisionKind.REVIEW_MISSING)
                p == null || d == null -> if (previous != null) review(FolderDecisionKind.REVIEW_MISSING)
                    else if (p != null) copy(FolderSide.PHONE, p, FolderDecisionKind.COPY_NEW) else copy(FolderSide.DESKTOP, d!!, FolderDecisionKind.COPY_NEW)
                previous != null && (previous.phoneObjectId != p.objectId || previous.desktopObjectId != d.objectId) -> review(FolderDecisionKind.REVIEW_IDENTITY)
                p.sha256 == d.sha256 -> review(FolderDecisionKind.CONTENT_EQUIVALENT)
                previous == null -> review(FolderDecisionKind.REVIEW_CONFLICT)
                p.sha256 == previous.sha256 -> copy(FolderSide.DESKTOP, d, FolderDecisionKind.COPY_RETAINED_VERSION)
                d.sha256 == previous.sha256 -> copy(FolderSide.PHONE, p, FolderDecisionKind.COPY_RETAINED_VERSION)
                else -> review(FolderDecisionKind.REVIEW_CONFLICT)
            }
        }
        return FolderPlan(decisions, capacity.bytes - remaining, capacity.slots - slots)
    }
    /** Stable across scan order/retries; changes with scope or immutable source. Not a receipt. */
    fun operationKey(scope: FolderScope, from: FolderSide, source: FolderFile): String {
        scope.validate(); source.validate()
        return sha256(listOf("narada-folder-plan-1", scope.linkId, scope.peerId, scope.pairingAttempt,
            scope.phoneRootId, scope.desktopRootId, scope.authorityRevision.toString(), from.name,
            source.objectId, source.relativeLabel, source.version, source.sha256, source.size.toString())
            .joinToString("\u0000").toByteArray(Charsets.UTF_8))
    }
}
