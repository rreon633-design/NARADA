package com.narada.connection

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.attribute.PosixFilePermissions
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface TransferChunks {
    fun reserve(storeId: String, record: DownloadRecord)
    fun read(storeId: String, record: DownloadRecord, index: Int): ByteArray?
    /** Must encrypt, fsync bytes and durably install the immutable fragment before returning. */
    fun write(storeId: String, record: DownloadRecord, index: Int, clear: ByteArray)
    fun remove(storeId: String, record: DownloadRecord)
}
data class ChunkContext(val storeId: String, val transferId: String, val manifestId: String, val index: Int, val length: Int) {
    fun aad(): ByteArray {
        canonicalUuid(storeId); canonicalUuid(transferId); TransferRules.hash(manifestId)
        demand(index in 0..255 && length in 1..TransferRules.CHUNK, "INVALID_CHUNK")
        return "NARADA-ANDROID-TRANSFER-CHUNK-1|$storeId|$transferId|$manifestId|$index|$length".toByteArray(Charsets.US_ASCII)
    }
}
interface ChunkCryptography { fun seal(context: ChunkContext, clear: ByteArray): ByteArray; fun unseal(context: ChunkContext, sealed: ByteArray): ByteArray }
/** Standard platform AES-GCM, fresh random IV each time; domain-separated from sealed trust.
 * No rolling encryption state/nonces are reconstructed on resume. */
object TransferCipher {
    private val magic = "NARADA-CHUNK-1\u0000".toByteArray(Charsets.US_ASCII)
    const val OVERHEAD_LIMIT = 128
    fun seal(context: ChunkContext, key: SecretKey, clear: ByteArray): ByteArray {
        demand(clear.size == context.length, "INVALID_CHUNK")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key); updateAAD(context.aad()) }
        demand(cipher.iv.size == 12, "KEYSTORE_UNAVAILABLE")
        return magic + cipher.iv + cipher.doFinal(clear)
    }
    fun unseal(context: ChunkContext, key: SecretKey, sealed: ByteArray): ByteArray {
        demand(sealed.size == magic.size + 28 + context.length && sealed.copyOfRange(0, magic.size).contentEquals(magic), "LOCAL_COPY_CHANGED")
        return try { Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed.copyOfRange(magic.size, magic.size + 12)))
            updateAAD(context.aad()); doFinal(sealed, magic.size + 12, sealed.size - magic.size - 12)
        } } catch (_: javax.crypto.AEADBadTagException) { fail("LOCAL_COPY_CHANGED") }
    }
}
/** Opaque app-private names only; no server name/path/URI participates in a filesystem operation.
 * Known uncheckpointed .pending fragments may be discarded on explicit retry. Installed chunks are
 * immutable: mismatched or unknown objects are preserved and block the operation, never replaced. */
class EncryptedChunkFiles(
    private val base: Path, private val crypto: ChunkCryptography,
    private val freeBytes: () -> Long = { Files.getFileStore(base.parent).usableSpace },
    private val syncDirectory: (Path) -> Unit = { p -> FileChannel.open(p, StandardOpenOption.READ).use { it.force(true) } },
) : TransferChunks {
    private val noFollow = LinkOption.NOFOLLOW_LINKS
    private fun existing(path: Path) = Files.exists(path, noFollow)
    private fun directory(path: Path, create: Boolean) {
        if (!existing(path) && create) { Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))); syncDirectory(path.parent) }
        demand(Files.isDirectory(path, noFollow) && !Files.isSymbolicLink(path), "LOCAL_COPY_CHANGED")
    }
    private fun folder(storeId: String, r: DownloadRecord, create: Boolean): Path {
        canonicalUuid(storeId); canonicalUuid(r.id)
        demand(Files.isDirectory(base.parent, noFollow) && !Files.isSymbolicLink(base.parent), "LOCAL_COPY_CHANGED")
        directory(base, create); val owner = base.resolve(storeId); directory(owner, create)
        return owner.resolve(r.id).also { directory(it, create) }
    }
    private fun path(folder: Path, index: Int): Path { demand(index in 0..255, "INVALID_CHUNK"); return folder.resolve("chunk-$index.enc") }
    private fun context(storeId: String, r: DownloadRecord, index: Int): ChunkContext { val m = r.manifest ?: fail("INVALID_MANIFEST"); return ChunkContext(storeId, r.id, m.manifest_id, index, m.length(index)) }
    private fun regular(path: Path, max: Int) {
        demand(Files.isRegularFile(path, noFollow) && !Files.isSymbolicLink(path) && Files.size(path) in 1..max.toLong(), "LOCAL_COPY_CHANGED")
    }
    private fun space(needed: Long) { demand(freeBytes() >= needed + 16L * 1024 * 1024, "LOCAL_STORAGE_FULL") }
    @Synchronized override fun reserve(storeId: String, record: DownloadRecord) = io {
        record.validate(); space((record.size - record.verifiedBytes).coerceAtLeast(0) + 256L * TransferCipher.OVERHEAD_LIMIT)
        folder(storeId, record, true); Unit
    }
    @Synchronized override fun read(storeId: String, record: DownloadRecord, index: Int): ByteArray? = io {
        val c = context(storeId, record, index)
        // Missing fragments are not progress. A previously verified receipt is checked by the caller.
        val unchecked = base.resolve(storeId).resolve(record.id)
        if (!existing(base) || !existing(base.resolve(storeId)) || !existing(unchecked)) return@io null
        val folder = folder(storeId, record, false); val file = path(folder, index)
        if (!existing(file)) return@io null
        regular(file, c.length + TransferCipher.OVERHEAD_LIMIT)
        val encrypted = FileChannel.open(file, StandardOpenOption.READ, noFollow).use { channel ->
            val length = channel.size().toInt(); demand(length <= c.length + TransferCipher.OVERHEAD_LIMIT, "LOCAL_COPY_CHANGED")
            val bytes = ByteArray(length); val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) demand(channel.read(buffer) > 0, "LOCAL_COPY_CHANGED")
            demand(channel.read(ByteBuffer.allocate(1)) == -1, "LOCAL_COPY_CHANGED")
            channel.force(true); bytes
        }
        syncDirectory(folder)
        try { crypto.unseal(c, encrypted).also { bytes ->
            if (bytes.size != c.length || sha256(bytes) != record.hashes[index]) { bytes.fill(0); fail("LOCAL_COPY_CHANGED") }
        } } finally { encrypted.fill(0) }
    }
    @Synchronized override fun write(storeId: String, record: DownloadRecord, index: Int, clear: ByteArray) = io {
        val c = context(storeId, record, index); demand(clear.size == c.length && sha256(clear) == record.hashes[index], "HASH_MISMATCH")
        space(clear.size.toLong() + TransferCipher.OVERHEAD_LIMIT)
        val folder = folder(storeId, record, true); val installed = path(folder, index)
        if (existing(installed)) {
            val old = read(storeId, record, index) ?: fail("LOCAL_COPY_CHANGED")
            try { demand(old.contentEquals(clear), "LOCAL_COPY_CHANGED") } finally { old.fill(0) }
            return@io
        }
        val pending = folder.resolve("chunk-$index.pending")
        if (existing(pending)) {
            // This exact staging name is owned by the sealed transfer. It is never an acknowledged copy.
            demand(index !in record.chunks && Files.isRegularFile(pending, noFollow) && !Files.isSymbolicLink(pending), "LOCAL_COPY_CHANGED")
            Files.delete(pending); syncDirectory(folder)
        }
        val encrypted = crypto.seal(c, clear)
        try {
            FileChannel.open(pending, setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, noFollow), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))).use { channel ->
                val buffer = ByteBuffer.wrap(encrypted); while (buffer.hasRemaining()) demand(channel.write(buffer) > 0, "LOCAL_STORAGE_UNAVAILABLE")
                channel.force(true)
            }
            // Hard-link install is no-replace even if a conflicting name appears after the check.
            Files.createLink(installed, pending); syncDirectory(folder)
            Files.delete(pending); syncDirectory(folder)
        } finally { encrypted.fill(0) }
    }
    @Synchronized override fun remove(storeId: String, record: DownloadRecord) = io {
        val unchecked = base.resolve(storeId).resolve(record.id)
        if (!existing(base) || !existing(base.resolve(storeId)) || !existing(unchecked)) return@io
        val folder = folder(storeId, record, false)
        val allowed = (0 until (record.manifest?.chunk_count ?: 0)).flatMap { listOf("chunk-$it.enc", "chunk-$it.pending") }.toSet()
        Files.newDirectoryStream(folder).use { stream -> stream.forEach { f -> demand(f.fileName.toString() in allowed && Files.isRegularFile(f, noFollow) && !Files.isSymbolicLink(f), "LOCAL_COPY_CHANGED") } }
        for (name in allowed) Files.deleteIfExists(folder.resolve(name))
        syncDirectory(folder); Files.delete(folder); syncDirectory(folder.parent)
    }
    private inline fun <T> io(block: () -> T): T = try { block() }
        catch (e: ConnectionFailure) { throw e }
        catch (_: java.security.GeneralSecurityException) { fail("KEYSTORE_UNAVAILABLE") }
        catch (e: FileSystemException) { fail(if (e.reason?.contains("space", true) == true) "LOCAL_STORAGE_FULL" else "LOCAL_STORAGE_UNAVAILABLE") }
        catch (_: Exception) { fail("LOCAL_STORAGE_UNAVAILABLE") }
}
