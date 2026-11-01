package com.narada.connection

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

object FileSamples {
    val rootId = "11111111-1111-4111-8111-111111111111"
    val objectId = "22222222-2222-4222-8222-222222222222"
    val directoryId = "33333333-3333-4333-8333-333333333333"
    val cursor = "44444444-4444-4444-8444-444444444444"
    val version = "a".repeat(64)
    val capabilities = listOf("browse", "read", "modify", "trash", "create")
    val grants = Permissions(roots = listOf(RootGrant(rootId, capabilities)))
    val root = RemoteRoot(rootId, "Shared", 1, "1", "ready", null, "2026-10-31T09:00:00+00:00", 2, 0, capabilities)
    val entry = RemoteEntry(objectId, "sample.jpg", "file", version, "1024", "2026-10-31T09:00:00+00:00", "sample.jpg", null, true)
    val folder = entry.copy(object_id = directoryId, name = "Documents", kind = "directory", relative_path = "Documents", preview_candidate = false)
    val page = PageReply(root, listOf(folder, entry), listOf(Breadcrumb(root.label, null)), null, "1", 2, "Live revalidation")
    val trash = TrashEntry(cursor, "sample.jpg", "sample.jpg", "file", version, "1024", "2026-10-31T09:00:00+00:00", "2026-11-14T09:00:00+00:00")
    fun jpeg(width: Int = 20, height: Int = 14): ByteArray = ByteArrayOutputStream().also {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until width) for (y in 0 until height) image.setRGB(x, y, ((x * 17) shl 16) or ((y * 13) shl 8) or 80)
        check(ImageIO.write(image, "jpeg", it))
    }.toByteArray()
    fun preview(bytes: ByteArray = jpeg()) = PreviewReply(objectId, version, "image/jpeg", 20, 14, java.util.Base64.getEncoder().encodeToString(bytes), "Pixel preview only")
    fun receipt(input: FileMutationInput) = FileReceipt(input.operationId, "committed", input.action.kind, if (input.action.kind == "restore") objectId else input.objectId, "b".repeat(64), input.operationId.takeIf { input.action.kind == "trash" })
    fun input(kind: String = "rename", name: String? = if (kind == "rename") "changed.jpg" else null) = FileMutationInput(rootId, UUID.randomUUID().toString(), objectId, version, FileAction(kind, name))
}

/** Software-key / in-memory tests only; real Rust + TLS has a separate opt-in suite. */
class FilePeerFixture {
    val pairingFixture = PeerFixture()
    val memory = pairingFixture.repository
    val peer = runBlocking { pairingFixture.paired() }
    var beforeWrite: (TrustDocument) -> Unit = {}
    val repository = object : TrustRepository by memory {
        override suspend fun mutate(change: (TrustDocument) -> TrustDocument) = memory.mutate { change(it).also(beforeWrite) }
    }
    val engine = PairingEngine(repository, pairingFixture)
    val client = FilesClient(repository, engine)
    var health = Health(Hello(), peer.id, FileSamples.grants, 1, 1, listOf("health", "permissions", "files.v1"))
    var healthCount = 0
    var fileCount = 0
    var beforeFile: (JsonObject) -> Unit = {}
    var afterFile: () -> Unit = {}
    var responseTransform: (JsonElement) -> JsonElement = { it }
    var onHealth: (Int) -> Unit = {}
    var error: String? = null
    val sent = mutableListOf<JsonObject>()
    val session = object : PeerSession {
        override val certificate = pairingFixture.host.certificate.encoded
        override fun close() {}
        override fun request(path: String, body: ByteArray): ByteArray {
            if (path == "/api/v1/health") { healthCount++; onHealth(healthCount); return Wire.encode(Envelope(true, health, null)) }
            check(path == FileRules.PATH); fileCount++
            val wire = Wire.decode<FileWire>(body); val payload = wire.action.payload
            sent += payload; beforeFile(payload)
            val result = if (wire.action.type == "mutate") {
                val action = payload["action"]!!.jsonObject
                val input = FileMutationInput(payload["root_id"]!!.jsonPrimitive.content, payload["operation_id"]!!.jsonPrimitive.content,
                    payload["object_id"]!!.jsonPrimitive.content, payload["expected_version"]!!.jsonPrimitive.content, FileAction(action["kind"]!!.jsonPrimitive.content, action["name"]?.jsonPrimitive?.contentOrNull))
                Wire.json.encodeToJsonElement(FileSamples.receipt(input))
            } else when (payload["kind"]!!.jsonPrimitive.content) {
                "roots" -> Wire.json.encodeToJsonElement(RootsReply(listOf(FileSamples.root.copy(capabilities = health.permissions.roots.firstOrNull()?.capabilities.orEmpty())), 1))
                "browse", "search" -> Wire.json.encodeToJsonElement(FileSamples.page)
                "details" -> Wire.json.encodeToJsonElement(FileSamples.entry)
                "preview" -> Wire.json.encodeToJsonElement(FileSamples.preview())
                "trash" -> Wire.json.encodeToJsonElement(TrashReply(FileSamples.root, listOf(FileSamples.trash), 0, null, "Retained"))
                else -> kotlin.error("Unsupported fixture query")
            }
            afterFile()
            return if (error != null) Wire.encode(Envelope<JsonElement>(false, null, RemoteError(error!!, "UNTRUSTED REMOTE PROSE MUST NOT BE DISPLAYED")))
                else Wire.encode(Envelope(true, responseTransform(result), null), FileRules.MAX_RESPONSE)
        }
    }
    fun currentPeer() = memory.document.peer(peer.id)
    suspend fun query(query: FileQuery = FileQuery.Roots) = client.query(currentPeer(), session, query)
    suspend fun mutate(confirmed: Boolean = true) = client.mutate(currentPeer(), session, FileSamples.rootId, FileSamples.objectId, FileSamples.version, FileAction("rename", "changed.jpg"), confirmed)
}
