package com.narada.android.transfers

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import android.os.StatFs
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.narada.android.connection.AndroidConnectionKeys
import com.narada.android.connection.ConnectionsCoordinator
import com.narada.connection.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

@Singleton
class RemoteTransfersGateway @Inject constructor(private val repository: TrustRepository, private val connections: ConnectionsCoordinator) : TransfersGateway {
    private suspend fun <T> lease(r: DownloadRecord, action: suspend (TransfersClient, TrustedPeer, PeerSession, () -> Unit) -> T): T = try {
        connections.withTransfer(r.peerId) { engine, peer, session, guard -> action(TransfersClient(repository, engine), peer, session, guard) }
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { if (isProtectedStorageFailure(e)) connections.fileStorageFailure(e); throw e }
    override suspend fun prepare(record: DownloadRecord) = lease(record) { client, peer, session, guard -> client.prepare(peer, session, record, guard) }
    override suspend fun chunk(record: DownloadRecord, index: Int) = lease(record) { client, peer, session, guard -> client.chunk(peer, session, record, index, guard) }
    override suspend fun finish(record: DownloadRecord) = lease(record) { client, peer, session, guard -> client.finish(peer, session, record, guard) }
    override fun cancel() = connections.cancelTransfers()
}
fun isProtectedStorageFailure(e: Exception): Boolean = e is ConnectionFailure && e.code in setOf("IDENTITY_KEY_MISSING", "IDENTITY_CHANGED", "IDENTITY_ANCHOR_MISSING", "TRUST_STORE_MISSING", "TRUST_STORE_INVALID", "KEYSTORE_UNAVAILABLE", "STORAGE_UNAVAILABLE", "CRYPTO_STATE_INVALID", "CHAT_STATE_INVALID", "ATTACHMENT_STATE_INVALID", "PIN_STATE_INVALID")

fun interface ExportProviderFactory { fun create(tree: String, flags: Int): NewDocumentProvider }
@Module @InstallIn(SingletonComponent::class)
object TransferModule {
    @Provides @Singleton fun gateway(implementation: RemoteTransfersGateway, admission: ContentAdmission): TransfersGateway = PacedTransfersGateway(implementation, admission)
    @Provides @Singleton fun contentAdmission() = ContentAdmission()
    @Provides @Singleton fun storage(@ApplicationContext context: Context, keys: AndroidConnectionKeys): TransferChunks = EncryptedChunkFiles(
        File(context.noBackupFilesDir, "received-v1").toPath(),
        object : ChunkCryptography {
            override fun seal(context: ChunkContext, clear: ByteArray) = TransferCipher.seal(context, keys.transferKey(context.storeId), clear)
            override fun unseal(context: ChunkContext, sealed: ByteArray) = TransferCipher.unseal(context, keys.transferKey(context.storeId), sealed)
        },
        // Android's NIO getFileStore is deliberately denied. Query only our
        // existing app-private volume; errors still fail closed in the engine.
        freeBytes = { StatFs(context.noBackupFilesDir.absolutePath).availableBytes },
        atomicMoveNoReplace = { pending, installed -> NativeStorageBridge.publish(pending, installed) },
        syncDirectory = { path -> val fd = Os.open(path.toString(), OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
            try { Os.fsync(fd) } finally { Os.close(fd) } },
    )
    @Provides @Singleton fun providers(@ApplicationContext context: Context): ExportProviderFactory = ExportProviderFactory { tree, flags -> DocumentsExportProvider(context, tree, flags) }
    @Provides @Singleton fun boundedProviderIo() = BoundedProviderIo()
}

/** Temporary, explicit tree permission only; does NOT acquire persisted/broad phone write access.
 * Create-new + complete child snapshot + no-truncate output + independent readback. SAF has no
 * atomic publication contract: a failed/interrupted new document is preserved, not auto-deleted. */
class DocumentsExportProvider(private val context: Context, private val selectedTree: String, private val offeredFlags: Int) : NewDocumentProvider {
    private val resolver = context.contentResolver
    private val signal = CancellationSignal()
    private val stopped = AtomicBoolean()
    private val handles = CopyOnWriteArrayList<Closeable>()
    private var parent: Uri? = null
    private var approvedName: String? = null
    private var priorIds: Set<String> = emptySet()
    private var created: Uri? = null
    private var createdName: String? = null
    private fun check() { demand(!stopped.get() && !Thread.currentThread().isInterrupted, "EXPORT_INTERRUPTED") }
    private inline fun <T> provider(block: () -> T): T = try { check(); block() }
        catch (e: ConnectionFailure) { throw e }
        catch (_: SecurityException) { fail("PROVIDER_PERMISSION_LOST") }
        catch (_: android.os.OperationCanceledException) { fail("EXPORT_INTERRUPTED") }
        catch (_: Exception) { fail("PROVIDER_UNAVAILABLE") }
    private data class Info(val id: String, val name: String, val mime: String, val flags: Long, val size: Long?)
    private val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.COLUMN_SIZE)
    private fun info(uri: Uri): Info = provider {
        resolver.query(uri, columns, null, null, null, signal)?.use { cursor ->
            demand(cursor.moveToFirst(), "PROVIDER_UNAVAILABLE")
            val item = Info(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3), if (cursor.isNull(4)) null else cursor.getLong(4))
            demand(!cursor.moveToNext() && item.id.length in 1..2048 && item.name.toByteArray().size <= 180, "INVALID_EXPORT_TARGET")
            item
        } ?: fail("PROVIDER_UNAVAILABLE")
    }
    override fun preflight(tree: String, name: String) = provider {
        demand(tree == selectedTree, "INVALID_EXPORT_TARGET"); TransferRules.uri(tree); FileRules.newName(name)
        val needed = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        demand(offeredFlags and needed == needed, "PROVIDER_PERMISSION_LOST")
        val uri = tree.toUri()
        demand(DocumentsContract.isTreeUri(uri) && uri.authority?.isNotBlank() == true && uri.authority?.startsWith("${context.packageName}.") == false && uri.query == null && uri.fragment == null, "INVALID_EXPORT_TARGET")
        val id = DocumentsContract.getTreeDocumentId(uri); demand(id.length in 1..2048, "INVALID_EXPORT_TARGET")
        val directory = DocumentsContract.buildDocumentUriUsingTree(uri, id); val details = info(directory)
        demand(details.id == id && details.mime == DocumentsContract.Document.MIME_TYPE_DIR && details.flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE.toLong() != 0L, "PROVIDER_READ_ONLY")
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, id)
        val ids = mutableSetOf<String>(); val names = mutableSetOf<String>()
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null, signal)?.use { cursor ->
            demand(!cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false) && cursor.extras.getString(DocumentsContract.EXTRA_ERROR).isNullOrEmpty(), "PROVIDER_DIRECTORY_INCOMPLETE")
            while (cursor.moveToNext()) {
                check(); demand(ids.size < 256, "PROVIDER_DIRECTORY_LIMIT")
                val child = cursor.getString(0); val label = cursor.getString(1)
                demand(child.length in 1..2048 && label.toByteArray().size <= 2048 && ids.add(child), "INVALID_EXPORT_TARGET")
                names.add(Normalizer.normalize(label, Normalizer.Form.NFC).lowercase(Locale.ROOT))
            }
        } ?: fail("PROVIDER_UNAVAILABLE")
        demand(Normalizer.normalize(name, Normalizer.Form.NFC).lowercase(Locale.ROOT) !in names, "EXPORT_NAME_COLLISION")
        parent = directory; priorIds = ids.toSet(); approvedName = name
    }
    override fun create(tree: String, name: String): String = provider {
        demand(tree == selectedTree && name == approvedName && created == null, "INVALID_EXPORT_TARGET")
        val folder = parent ?: fail("INVALID_EXPORT_TARGET")
        // Mark single-use before the provider call, including null/lost replies.
        approvedName = null
        val result = DocumentsContract.createDocument(resolver, folder, "application/octet-stream", name) ?: fail("PROVIDER_CREATE_UNCONFIRMED")
        demand(result.scheme == "content" && result.authority == folder.authority && result.query == null && result.fragment == null, "INVALID_EXPORT_TARGET")
        val id = DocumentsContract.getDocumentId(result)
        demand(id.length in 1..2048 && id !in priorIds && id != DocumentsContract.getDocumentId(folder), "PROVIDER_REUSED_DOCUMENT")
        val scoped = DocumentsContract.buildDocumentUriUsingTree(tree.toUri(), id)
        demand(DocumentsContract.isChildDocument(resolver, folder, scoped), "INVALID_EXPORT_TARGET")
        created = scoped; createdName = name; scoped.toString()
    }
    override fun writer(document: String): OutputStream = provider {
        val uri = created ?: fail("INVALID_EXPORT_TARGET"); demand(uri.toString() == document, "INVALID_EXPORT_TARGET")
        val meta = info(uri)
        demand(meta.name == createdName, "PROVIDER_NAME_CHANGED")
        demand(meta.id == DocumentsContract.getDocumentId(uri) && meta.mime == "application/octet-stream" && meta.flags and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT.toLong() == 0L && meta.size == 0L, "PROVIDER_NOT_EMPTY")
        // Independently confirm empty contents. Never open an arbitrary existing URI in truncate mode.
        reader(document).use { demand(it.read() == -1, "PROVIDER_NOT_EMPTY") }
        val fd = resolver.openFileDescriptor(uri, "wa", signal) ?: fail("PROVIDER_UNAVAILABLE")
        if (fd.statSize > 0) { fd.close(); fail("PROVIDER_NOT_EMPTY") }
        val stream = ParcelFileDescriptor.AutoCloseOutputStream(fd)
        handles.add(stream)
        object : OutputStream() {
            override fun write(value: Int) { check(); stream.write(value) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { check(); stream.write(bytes, offset, length) }
            override fun flush() { check(); stream.flush() }
            override fun close() {
                try {
                    stream.flush()
                    try { Os.fsync(fd.fileDescriptor) } catch (e: ErrnoException) {
                        // Pipes/cloud providers cannot promise local fsync. Readback still must match;
                        // no physical durability/atomicity of the external provider is claimed.
                        if (e.errno != OsConstants.EINVAL && e.errno != OsConstants.ENOTSUP) throw e
                    }
                } finally { stream.close(); handles.remove(stream) }
            }
        }
    }
    override fun reader(document: String): InputStream = provider {
        val uri = created ?: fail("INVALID_EXPORT_TARGET"); demand(uri.toString() == document, "INVALID_EXPORT_TARGET")
        val meta = info(uri)
        demand(meta.id == DocumentsContract.getDocumentId(uri) && meta.name == createdName && meta.mime == "application/octet-stream" && meta.flags and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT.toLong() == 0L, "INVALID_EXPORT_TARGET")
        val fd = resolver.openFileDescriptor(uri, "r", signal) ?: fail("PROVIDER_UNAVAILABLE")
        val stream = ParcelFileDescriptor.AutoCloseInputStream(fd); handles.add(stream)
        object : InputStream() {
            override fun read(): Int { check(); return stream.read() }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int { check(); return stream.read(bytes, offset, length) }
            override fun close() { try { stream.close() } finally { handles.remove(stream) } }
        }
    }
    override fun cancel() { stopped.set(true); handles.forEach { runCatching { it.close() } }; signal.cancel() }
    override fun close() { handles.forEach { runCatching { it.close() } }; handles.clear() }
}
