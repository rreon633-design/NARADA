package com.narada.connection

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.text.Normalizer
import java.time.OffsetDateTime
import java.util.Base64

/** The peer file protocol is separate from owner IPC and from a future byte-transfer API. */
object FileRules {
    const val PATH = "/api/v1/files"
    const val MAX_RESPONSE = 61_024
    const val PAGE_SIZE = 16
    const val MAX_PREVIEW = 32_768
    private val caps = setOf("browse", "read", "create", "modify", "trash", "sync_read")
    fun id(value: String) { demand(value.length == 36 && runCatching { java.util.UUID.fromString(value).toString() == value }.getOrDefault(false), "INVALID_FILE_ID") }
    fun version(value: String) { demand(value.matches(Regex("[0-9a-f]{64}")), "INVALID_FILE_VERSION") }
    fun text(value: String, maxBytes: Int, empty: Boolean = false) {
        demand((empty || value.isNotEmpty()) && value.toByteArray(Charsets.UTF_8).size <= maxBytes &&
            value.codePoints().noneMatch { it in 0xd800..0xdfff } &&
            value.none { Character.isISOControl(it) || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' }, "INVALID_FILE_RESPONSE")
    }
    fun name(value: String) {
        text(value, 180)
        demand(Normalizer.isNormalized(value, Normalizer.Form.NFC) && value != "." && value != ".." &&
            value.none { it in "/\\:*?\"<>|" } && !value.endsWith('.') && !value.endsWith(' '), "INVALID_FILE_NAME")
        val stem = value.substringBefore('.').uppercase(java.util.Locale.ROOT)
        demand(stem !in setOf("CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$") &&
            !stem.matches(Regex("(COM|LPT)[1-9¹²³]")), "INVALID_FILE_NAME")
    }
    fun newName(value: String) {
        name(value)
        val upper = value.uppercase(java.util.Locale.ROOT)
        demand(!value.startsWith('.') && upper !in setOf("DESKTOP.INI", "THUMBS.DB", "$" + "RECYCLE.BIN", "SYSTEM VOLUME INFORMATION"), "INVALID_FILE_NAME")
    }
    /** Display-only wire field. Never passed to the OS, a URI, an intent, or an outgoing request. */
    fun relativeLabel(value: String, leaf: String) {
        text(value, 2048)
        val parts = value.split('/')
        demand(parts.size in 1..32 && parts.last() == leaf, "INVALID_FILE_RESPONSE")
        parts.forEach(::name)
    }
    fun search(value: String) {
        demand(value.isNotBlank() && value.toByteArray(Charsets.UTF_8).size in 1..128 && value.none(Character::isISOControl), "INVALID_FILE_SEARCH")
        text(value, 128)
    }
    fun decimal(value: String) { demand(value.matches(Regex("0|[1-9][0-9]{0,19}")) && value.toULongOrNull() != null, "INVALID_FILE_RESPONSE") }
    fun instant(value: String) {
        text(value, 48)
        demand(runCatching { OffsetDateTime.parse(value) }.isSuccess, "INVALID_FILE_RESPONSE")
    }
    fun capabilities(values: List<String>) { demand(values.size <= 6 && values.distinct().size == values.size && values.all { it in caps }, "INVALID_FILE_RESPONSE") }
    fun notice(value: String) { text(value, 1024, empty = true) }
}

enum class FileSort(val wire: String, val label: String) { NAME("name", "Name A–Z"), NAME_DESC("name_desc", "Name Z–A"), MODIFIED("modified", "Modified"), SIZE("size", "Size") }

sealed interface FileQuery {
    val rootId: String? get() = null
    fun payload(): JsonObject
    data object Roots : FileQuery { override fun payload() = buildJsonObject { put("kind", "roots") } }
    data class Browse(override val rootId: String, val directoryId: String? = null, val cursor: String? = null, val sort: FileSort = FileSort.NAME) : FileQuery {
        override fun payload() = buildJsonObject {
            validateIds(rootId, directoryId, cursor); put("kind", "browse"); put("root_id", rootId); put("directory_id", directoryId); put("cursor", cursor); put("sort", sort.wire)
        }
    }
    data class Search(override val rootId: String, val query: String, val cursor: String? = null, val sort: FileSort = FileSort.NAME) : FileQuery {
        override fun payload() = buildJsonObject {
            validateIds(rootId, cursor); FileRules.search(query); put("kind", "search"); put("root_id", rootId); put("query", query); put("cursor", cursor); put("sort", sort.wire)
        }
    }
    data class Details(override val rootId: String, val objectId: String) : FileQuery {
        override fun payload() = buildJsonObject { validateIds(rootId, objectId); put("kind", "details"); put("root_id", rootId); put("object_id", objectId) }
    }
    data class Preview(override val rootId: String, val objectId: String, val expectedVersion: String) : FileQuery {
        override fun payload() = buildJsonObject { validateIds(rootId, objectId); FileRules.version(expectedVersion); put("kind", "preview"); put("root_id", rootId); put("object_id", objectId); put("expected_version", expectedVersion) }
    }
    data class Trash(override val rootId: String, val cursor: String? = null) : FileQuery {
        override fun payload() = buildJsonObject { validateIds(rootId, cursor); put("kind", "trash"); put("root_id", rootId); put("cursor", cursor) }
    }
}
private fun validateIds(vararg ids: String?) { ids.filterNotNull().forEach(FileRules::id) }

@Serializable data class RemoteRoot(
    val root_id: String, val label: String, val revision: Long, val index_epoch: String, val state: String,
    val error_code: String?, val indexed_at: String?, val entry_count: Long, val omitted_count: Long, val capabilities: List<String>,
) {
    fun validate(grants: Permissions) {
        FileRules.id(root_id); FileRules.text(label, 192); demand(label.codePointCount(0, label.length) <= 48, "INVALID_FILE_RESPONSE")
        demand(revision in 1..0xffff_ffffL && state in setOf("ready", "indexing", "blocked", "recovery_required"), "INVALID_FILE_RESPONSE")
        FileRules.decimal(index_epoch); indexed_at?.let(FileRules::instant)
        demand(entry_count in 0..5000 && omitted_count >= 0, "INVALID_FILE_RESPONSE")
        error_code?.let { demand(it.matches(Regex("[A-Z][A-Z0-9_]{0,63}")), "INVALID_FILE_RESPONSE") }
        FileRules.capabilities(capabilities)
        val grant = grants.roots.singleOrNull { it.root_id == root_id } ?: fail("FILE_ACCESS_CHANGED")
        demand("browse" in capabilities && capabilities.toSet() == grant.capabilities.toSet(), "FILE_ACCESS_CHANGED")
    }
    override fun toString() = "RemoteRoot([redacted])"
}
@Serializable data class RemoteEntry(
    val object_id: String, val name: String, val kind: String, val version: String, val size: String, val modified: String,
    val relative_path: String, val parent_id: String?, val preview_candidate: Boolean,
) {
    fun validate() {
        validateIds(object_id, parent_id); FileRules.name(name); FileRules.version(version); FileRules.decimal(size); FileRules.instant(modified)
        FileRules.relativeLabel(relative_path, name); demand(kind in setOf("file", "directory") && (!preview_candidate || kind == "file"), "INVALID_FILE_RESPONSE")
        demand(parent_id != object_id, "INVALID_FILE_RESPONSE")
    }
    override fun toString() = "RemoteEntry([redacted])"
}
@Serializable data class Breadcrumb(val name: String, val object_id: String?)
@Serializable data class RootsReply(val roots: List<RemoteRoot>, val file_protocol: Int) {
    fun validate(grants: Permissions) {
        demand(file_protocol == 1 && roots.size <= 8 && roots.map { it.root_id }.distinct().size == roots.size, "INVALID_FILE_RESPONSE")
        roots.forEach { it.validate(grants) }
        // Owner may have removed a granted root; absence is not permission to invent it locally.
    }
}
@Serializable data class PageReply(
    val root: RemoteRoot, val entries: List<RemoteEntry>, val breadcrumbs: List<Breadcrumb>, val next_cursor: String?,
    val index_epoch: String, val indexed_matches: Long, val notice: String,
) {
    fun validate(query: FileQuery, grants: Permissions) {
        root.validate(grants); demand(root.root_id == query.rootId && root.index_epoch == index_epoch, "INVALID_FILE_RESPONSE")
        FileRules.decimal(index_epoch); next_cursor?.let(FileRules::id); FileRules.notice(notice)
        demand(entries.size <= FileRules.PAGE_SIZE && entries.map { it.object_id }.distinct().size == entries.size && indexed_matches in entries.size.toLong()..5000L, "INVALID_FILE_RESPONSE")
        entries.forEach(RemoteEntry::validate)
        demand(breadcrumbs.size in 1..33 && breadcrumbs.first() == Breadcrumb(root.label, null), "INVALID_FILE_RESPONSE")
        val directory = (query as? FileQuery.Browse)?.directoryId
        demand(breadcrumbs.last().object_id == directory, "INVALID_FILE_RESPONSE")
        val children = breadcrumbs.drop(1)
        children.forEach { FileRules.name(it.name); FileRules.id(it.object_id ?: fail("INVALID_FILE_RESPONSE")) }
        demand(children.map { it.object_id }.distinct().size == children.size, "INVALID_FILE_RESPONSE")
        if (query is FileQuery.Browse) demand(entries.all { it.parent_id == directory }, "INVALID_FILE_RESPONSE")
    }
}
@Serializable data class TrashEntry(
    val object_id: String, val name: String, val original_path: String, val kind: String, val version: String, val size: String,
    val trashed_at: String, val retain_until: String,
) {
    fun validate() {
        FileRules.id(object_id); FileRules.name(name); FileRules.relativeLabel(original_path, name); FileRules.version(version); FileRules.decimal(size)
        FileRules.instant(trashed_at); FileRules.instant(retain_until); demand(kind in setOf("file", "directory"), "INVALID_FILE_RESPONSE")
        demand(!OffsetDateTime.parse(retain_until).isBefore(OffsetDateTime.parse(trashed_at)), "INVALID_FILE_RESPONSE")
    }
    override fun toString() = "TrashEntry([redacted])"
}
@Serializable data class TrashReply(val root: RemoteRoot, val entries: List<TrashEntry>, val unavailable_count: Int, val next_cursor: String?, val notice: String) {
    fun validate(query: FileQuery.Trash, grants: Permissions) {
        root.validate(grants); demand(root.root_id == query.rootId && "trash" in root.capabilities, "FILE_ACCESS_CHANGED")
        demand(entries.size <= FileRules.PAGE_SIZE && entries.map { it.object_id }.distinct().size == entries.size && unavailable_count in 0..16, "INVALID_FILE_RESPONSE")
        entries.forEach(TrashEntry::validate); next_cursor?.let(FileRules::id); FileRules.notice(notice)
    }
}
@Serializable data class PreviewReply(val object_id: String, val version: String, val mime: String, val width: Int, val height: Int, val data: String, val notice: String) {
    fun pixels(query: FileQuery.Preview): PixelPreview {
        demand(object_id == query.objectId && version == query.expectedVersion, "STALE_VERSION")
        demand(mime == "image/jpeg" && width in 1..256 && height in 1..256 && data.length in 4..43_692, "INVALID_PREVIEW")
        FileRules.notice(notice)
        val bytes = try { Base64.getDecoder().decode(data) } catch (_: Exception) { fail("INVALID_PREVIEW") }
        demand(bytes.size <= FileRules.MAX_PREVIEW && Base64.getEncoder().encodeToString(bytes) == data, "INVALID_PREVIEW")
        JpegBounds.validate(bytes, width, height)
        return PixelPreview(object_id, version, width, height, bytes)
    }
    override fun toString() = "PreviewReply([redacted pixels])"
}
class PixelPreview(val objectId: String, val version: String, val width: Int, val height: Int, bytes: ByteArray) {
    private val pixels = bytes.copyOf()
    fun encodedCopy() = pixels.copyOf()
    override fun toString() = "PixelPreview([bounded, memory only])"
}

/** Reject active formats, dimensions inconsistent with the envelope, ancillary metadata and trailing payloads before a native decoder allocates. */
object JpegBounds {
    fun validate(bytes: ByteArray, width: Int, height: Int) {
        fun u(i: Int): Int { demand(i in bytes.indices, "INVALID_PREVIEW"); return bytes[i].toInt() and 255 }
        fun word(i: Int) = (u(i) shl 8) or u(i + 1)
        demand(bytes.size in 20..FileRules.MAX_PREVIEW && word(0) == 0xffd8 && width in 1..256 && height in 1..256, "INVALID_PREVIEW")
        var i = 2; var frame = false; var jfif = false
        while (i < bytes.size) {
            demand(u(i++) == 255, "INVALID_PREVIEW")
            val marker = u(i++)
            demand(marker in setOf(0xe0, 0xdb, 0xc4, 0xdd, 0xc0, 0xda), "INVALID_PREVIEW")
            val length = word(i); demand(length >= 2 && i + length <= bytes.size, "INVALID_PREVIEW")
            if (marker == 0xe0) {
                demand(!jfif && length == 16 && bytes.copyOfRange(i + 2, i + 7).contentEquals(byteArrayOf(74, 70, 73, 70, 0)) && u(i + 14) == 0 && u(i + 15) == 0, "INVALID_PREVIEW")
                jfif = true
            }
            if (marker == 0xc0) {
                demand(!frame && length == 17 && u(i + 2) == 8 && word(i + 3) == height && word(i + 5) == width && u(i + 7) == 3, "INVALID_PREVIEW")
                frame = true
            }
            i += length
            if (marker == 0xda) {
                demand(frame && length == 12, "INVALID_PREVIEW")
                while (i < bytes.size) {
                    if (u(i++) != 255) continue
                    val end = u(i++)
                    if (end == 0 || end in 0xd0..0xd7) continue
                    demand(end == 0xd9 && i == bytes.size, "INVALID_PREVIEW")
                    return
                }
                fail("INVALID_PREVIEW")
            }
        }
        fail("INVALID_PREVIEW")
    }
}

@Serializable data class FileAction(val kind: String, val name: String? = null) {
    fun validate() { demand(kind in setOf("rename", "trash", "restore"), "INVALID_FILE_ACTION"); if (kind == "rename") demand(name != null, "INVALID_FILE_NAME"); if (kind == "trash") demand(name == null, "INVALID_FILE_ACTION"); name?.let(FileRules::newName) }
    override fun toString() = "FileAction($kind,[redacted])"
}
@Serializable data class FileMutationInput(val rootId: String, val operationId: String, val objectId: String, val expectedVersion: String, val action: FileAction) {
    fun validate() { validateIds(rootId, operationId, objectId); FileRules.version(expectedVersion); action.validate() }
    fun payload() = buildJsonObject {
        validate(); put("root_id", rootId); put("operation_id", operationId); put("object_id", objectId); put("expected_version", expectedVersion)
        put("action", buildJsonObject { put("kind", action.kind); if (action.kind != "trash") put("name", action.name) })
    }
    override fun toString() = "FileMutationInput([redacted])"
}
@Serializable data class FileReceipt(val operation_id: String, val state: String, val action: String, val object_id: String, val version: String, val trash_id: String?) {
    fun validate(request: FileMutationInput) {
        demand(operation_id == request.operationId && state == "committed" && action == request.action.kind, "INVALID_FILE_RECEIPT")
        FileRules.id(object_id); FileRules.version(version)
        if (action != "restore") demand(object_id == request.objectId, "INVALID_FILE_RECEIPT") // Restore returns original object ID, not trash ID.
        demand(trash_id == if (action == "trash") operation_id else null, "INVALID_FILE_RECEIPT")
    }
}
@Serializable enum class FileOperationState { UNCONFIRMED, COMMITTED, DISMISSED_UNCONFIRMED }
@Serializable data class FileOperation(
    val peerId: String, val pairingAttempt: String, val request: FileMutationInput, val createdAt: Long,
    val state: FileOperationState = FileOperationState.UNCONFIRMED, val receipt: FileReceipt? = null,
) {
    fun validate() {
        validHash(peerId); FileRules.id(pairingAttempt); request.validate(); demand(createdAt > 0, "TRUST_STORE_INVALID")
        demand((state == FileOperationState.COMMITTED) == (receipt != null), "TRUST_STORE_INVALID"); receipt?.validate(request)
    }
    override fun toString() = "FileOperation($state,[redacted])"
}
@Serializable internal data class FileWireAction(val type: String, val payload: JsonObject)
@Serializable internal data class FileWire(val hello: Hello, val version: Int = 1, val action: FileWireAction)

sealed interface FileResult {
    data class Roots(val value: RootsReply) : FileResult
    data class Page(val value: PageReply) : FileResult
    data class Details(val value: RemoteEntry) : FileResult
    data class Preview(val value: PixelPreview) : FileResult
    data class Trash(val value: TrashReply) : FileResult
}
