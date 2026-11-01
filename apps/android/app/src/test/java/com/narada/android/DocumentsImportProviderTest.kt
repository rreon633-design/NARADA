package com.narada.android

import android.content.*
import android.content.pm.ProviderInfo
import android.content.pm.ResolveInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract as D
import androidx.test.core.app.ApplicationProvider
import com.narada.android.attachments.DocumentsImportProvider
import com.narada.connection.*
import java.io.File
import java.util.UUID
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/** Host ContentResolver fixture only. Native URI grants/Binder/provider behavior remains unrun. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class DocumentsImportProviderTest {
    class Provider : ContentProvider() {
        lateinit var file: File; var name = "input.bin"; var mime = "application/octet-stream"; var flags = 0; var size: Long? = 4; var loading = false; var duplicate = false; var denied = false
        val modes = mutableListOf<String>(); var queries = 0
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
            queries++; if (denied) throw SecurityException(); val cols = projection!!; val row = cols.map { when (it) { D.Document.COLUMN_DISPLAY_NAME -> name; D.Document.COLUMN_MIME_TYPE -> mime; D.Document.COLUMN_FLAGS -> flags; D.Document.COLUMN_SIZE -> size; else -> null } }.toTypedArray<Any?>()
            return MatrixCursor(cols).apply { addRow(row); if (duplicate) addRow(row); if (loading) extras = Bundle().apply { putBoolean(D.EXTRA_LOADING, true) } }
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor { modes += mode; return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode)) }
        override fun getType(uri: Uri) = mime
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("No writes")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("Never deletes input")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("No writes")
    }
    private lateinit var context: Context; private lateinit var p: Provider
    @Before fun setup() { context = ApplicationProvider.getApplicationContext(); p = Provider(); val info = ProviderInfo().apply { authority = AUTHORITY; exported = true; grantUriPermissions = true }; p.attachInfo(context, info); p.file = File(context.cacheDir, "input-${UUID.randomUUID()}.bin").apply { writeBytes(byteArrayOf(0, 1, 2, -1)) }; ShadowContentResolver.registerProviderInternal(AUTHORITY, p); shadowOf(context.packageManager).addResolveInfoForIntent(Intent(D.PROVIDER_INTERFACE), ResolveInfo().apply { providerInfo = info }) }
    @After fun cleanup() { if (::p.isInitialized) p.file.delete() }
    private fun adapter(flags: Int = 1, uri: String = URI) = DocumentsImportProvider(context, uri, flags)
    private fun failure(code: String, block: () -> Unit) { try { block(); org.junit.Assert.fail("Expected refusal") } catch (e: ConnectionFailure) { assertEquals(code, e.code) } }
    @Test fun selectedDocumentOpensExactlyOnceAndOnlyInReadMode() { adapter().use { a -> val (name, stream) = a.open(); assertEquals("input.bin", name); assertArrayEquals(byteArrayOf(0, 1, 2, -1), stream.use { it.readBytes() }); failure("IMPORT_STOPPED") { a.open() } }; assertEquals(listOf("r"), p.modes) }
    @Test fun absentTemporaryReadAuthorityNeverQueriesOrOpensProvider() { failure("PROVIDER_PERMISSION_LOST") { adapter(2).use { it.open() } }; assertEquals(0, p.queries); assertTrue(p.modes.isEmpty()) }
    @Test fun treeAndOwnProviderAreNeverAcceptedAsReadSources() { failure("INVALID_IMPORT_SOURCE") { adapter(uri = "content://$AUTHORITY/tree/root").use { it.open() } }; failure("INVALID_IMPORT_SOURCE") { adapter(uri = "content://${context.packageName}.private/document/key").use { it.open() } }; assertEquals(0, p.queries) }
    @Test fun virtualAndDirectoryDocumentsAreRefusedWithoutOpening() { p.flags = D.Document.FLAG_VIRTUAL_DOCUMENT; failure("INVALID_IMPORT_SOURCE") { adapter().use { it.open() } }; p.flags = 0; p.mime = D.Document.MIME_TYPE_DIR; failure("INVALID_IMPORT_SOURCE") { adapter().use { it.open() } }; assertTrue(p.modes.isEmpty()) }
    @Test fun oversizedDeclaredFileIsRejectedBeforeDescriptorAllocation() { p.size = AttachmentRules.MAX_PLAIN.toLong() + 1; failure("ATTACHMENT_LIMIT") { adapter().use { it.open() } }; assertTrue(p.modes.isEmpty()) }
    @Test fun unknownSizeStillUsesReadOnlyStreamAndEngineBounds() { p.size = null; adapter().use { a -> a.open().second.use { assertEquals(4, it.readBytes().size) } }; assertEquals(listOf("r"), p.modes) }
    @Test fun incompleteAndAmbiguousProviderMetadataCannotBeImported() { p.loading = true; failure("PROVIDER_UNAVAILABLE") { adapter().use { it.open() } }; p.loading = false; p.duplicate = true; failure("PROVIDER_UNAVAILABLE") { adapter().use { it.open() } }; assertTrue(p.modes.isEmpty()) }
    @Test fun lostAuthorityAndCancellationHaveSafeLocalErrorCodes() { p.denied = true; failure("PROVIDER_PERMISSION_LOST") { adapter().use { it.open() } }; p.denied = false; adapter().use { a -> a.cancel(); failure("IMPORT_INTERRUPTED") { a.open() } }; assertTrue(p.modes.isEmpty()) }
    @Test fun providerPathLikeNameIsDisplayInputNotFilesystemAuthority() { p.name = "../private.bin"; failure("INVALID_ATTACHMENT") { adapter().use { it.open() } }; assertTrue(p.modes.isEmpty()) }
    companion object { const val AUTHORITY = "com.narada.importfixture.documents"; const val URI = "content://com.narada.importfixture.documents/document/source" }
}
