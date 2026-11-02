package com.narada.android.attachments

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.os.StatFs
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import com.narada.android.connection.AndroidConnectionKeys
import com.narada.android.connection.ConnectionsCoordinator
import com.narada.android.transfers.isProtectedStorageFailure
import com.narada.connection.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class AttachmentData
@Singleton class RemoteAttachmentsGateway @Inject constructor(private val repository: TrustRepository, private val connections: ConnectionsCoordinator) : AttachmentsGateway {
    private suspend fun <T> lease(r: AttachmentRecord, action: suspend (AttachmentsClient, TrustedPeer, PeerSession, () -> Unit) -> T): T = try {
        connections.withAttachments(r.peerId) { pairing, peer, session, guard -> action(AttachmentsClient(repository, pairing), peer, session, guard) }
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { if (isProtectedStorageFailure(e)) connections.fileStorageFailure(e); throw e }
    override suspend fun capabilities(record: AttachmentRecord) = lease(record) { c,p,s,g -> c.capabilities(p,s,record,g) }
    override suspend fun begin(record: AttachmentRecord) = lease(record) { c,p,s,g -> c.control("begin",p,s,record,g) }
    override suspend fun status(record: AttachmentRecord) = lease(record) { c,p,s,g -> c.control("status",p,s,record,g) }
    override suspend fun finish(record: AttachmentRecord) = lease(record) { c,p,s,g -> c.control("finish",p,s,record,g) }
    override suspend fun upload(record: AttachmentRecord, index: Int, bytes: ByteArray) = lease(record) { c,p,s,g -> c.upload(p,s,record,index,bytes,g) }
    override suspend fun download(record: AttachmentRecord, index: Int) = lease(record) { c,p,s,g -> c.download(p,s,record,index,g) }
    override fun cancel() = connections.cancelAttachments()
}
interface ImportDocument : AutoCloseable { fun open(): Pair<String, InputStream>; fun cancel(); override fun close() }
fun interface ImportProviderFactory { fun create(uri: String, flags: Int): ImportDocument }
@Module @InstallIn(SingletonComponent::class) object AttachmentsModule {
    @Provides @Singleton fun age(): AgeCryptography = RustAgeCryptography()
    @Provides @Singleton fun gateway(remote: RemoteAttachmentsGateway, admission: ContentAdmission): AttachmentsGateway = PacedAttachmentsGateway(remote, admission)
    @Provides @Singleton @AttachmentData fun chunks(@ApplicationContext context: Context, keys: AndroidConnectionKeys): TransferChunks = EncryptedChunkFiles(
        File(context.noBackupFilesDir, "attachments-v1").toPath(),
        object : ChunkCryptography {
            override fun seal(context: ChunkContext, clear: ByteArray) = TransferCipher.seal(context, keys.transferKey(context.storeId), clear)
            override fun unseal(context: ChunkContext, sealed: ByteArray) = TransferCipher.unseal(context, keys.transferKey(context.storeId), sealed)
        },
        // Same Android-private-volume probe as ordinary transfers; no NIO mount-table query.
        freeBytes = { StatFs(context.noBackupFilesDir.absolutePath).availableBytes },
        syncDirectory = { path -> val fd = Os.open(path.toString(), OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0); try { Os.fsync(fd) } finally { Os.close(fd) } },
    )
    @Provides @Singleton fun imports(@ApplicationContext context: Context): ImportProviderFactory = ImportProviderFactory { uri, flags -> DocumentsImportProvider(context, uri, flags) }
}
/** Temporary read-only SAF authority; bounded stream consumption happens in the shared quarantined
 * provider worker. Never takes a persisted grant, opens an executable, or resolves a raw path. */
class DocumentsImportProvider(private val context: Context, private val selected: String, private val flags: Int) : ImportDocument {
    private val resolver = context.contentResolver
    private val signal = CancellationSignal()
    private val stopped = AtomicBoolean()
    private val opened = AtomicBoolean()
    private val handles = CopyOnWriteArrayList<Closeable>()
    private fun check() { demand(!stopped.get() && !Thread.currentThread().isInterrupted, "IMPORT_INTERRUPTED") }
    override fun open(): Pair<String, InputStream> {
        try {
            check(); TransferRules.uri(selected); demand(opened.compareAndSet(false, true), "IMPORT_STOPPED")
            demand(flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0, "PROVIDER_PERMISSION_LOST")
            val uri = selected.toUri()
            demand(uri.authority?.isNotBlank() == true && uri.authority?.startsWith("${context.packageName}.") == false && uri.query == null && uri.fragment == null && !DocumentsContract.isTreeUri(uri) && DocumentsContract.isDocumentUri(context, uri), "INVALID_IMPORT_SOURCE")
            val columns = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.COLUMN_SIZE)
            val name = resolver.query(uri, columns, null, null, null, signal)?.use { c ->
                demand(c.moveToFirst(), "PROVIDER_UNAVAILABLE")
                val name = c.getString(0); AttachmentRules.name(name)
                demand(c.getString(1) != DocumentsContract.Document.MIME_TYPE_DIR && c.getLong(2) and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT.toLong() == 0L, "INVALID_IMPORT_SOURCE")
                if (!c.isNull(3)) demand(c.getLong(3) in 0..AttachmentRules.MAX_PLAIN.toLong(), "ATTACHMENT_LIMIT")
                demand(!c.moveToNext() && !c.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false) && c.extras.getString(DocumentsContract.EXTRA_ERROR).isNullOrEmpty(), "PROVIDER_UNAVAILABLE")
                name
            } ?: fail("PROVIDER_UNAVAILABLE")
            check(); val fd = resolver.openFileDescriptor(uri, "r", signal) ?: fail("PROVIDER_UNAVAILABLE")
            if (fd.statSize > AttachmentRules.MAX_PLAIN) { fd.close(); fail("ATTACHMENT_LIMIT") }
            val input = ParcelFileDescriptor.AutoCloseInputStream(fd); handles.add(input)
            return name to object : InputStream() {
                override fun read(): Int { check(); return input.read() }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int { check(); return input.read(buffer, offset, length) }
                override fun close() { input.close(); handles.remove(input) }
            }
        } catch (e: ConnectionFailure) { throw e }
        catch (_: SecurityException) { fail("PROVIDER_PERMISSION_LOST") }
        catch (_: android.os.OperationCanceledException) { fail("IMPORT_INTERRUPTED") }
        catch (_: Exception) { fail("PROVIDER_UNAVAILABLE") }
    }
    override fun cancel() { stopped.set(true); signal.cancel(); handles.forEach { runCatching { it.close() } } }
    override fun close() { stopped.set(true); handles.forEach { runCatching { it.close() } }; handles.clear() }
}
