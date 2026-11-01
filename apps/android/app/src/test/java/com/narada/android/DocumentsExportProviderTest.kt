package com.narada.android

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract as D
import androidx.test.core.app.ApplicationProvider
import com.narada.android.transfers.DocumentsExportProvider
import com.narada.connection.ConnectionFailure
import java.io.File
import java.util.UUID
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/** Uses Android DocumentsContract/ContentResolver against a disposable host provider. Real Android
 * URI grants, Binder stalls, cloud uploads and OEM descriptor durability remain device gates. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=android.app.Application::class)
class DocumentsExportProviderTest {
    class Provider:ContentProvider(){
        val children=mutableListOf<Pair<String,String>>();val modes=mutableListOf<String>();var calls=0;var creates=0;var returnedId="new";var returnedAuthority=AUTHORITY;var actualName:String?=null;var name="copy.bin";var child=true;var loading=false;var writable=true;var reportEmpty=false
        lateinit var file:File
        override fun onCreate()=true
        override fun query(uri:Uri,projection:Array<out String>?,selection:String?,args:Array<out String>?,sort:String?):Cursor {
            calls++;val cols=projection?:arrayOf(D.Document.COLUMN_DOCUMENT_ID,D.Document.COLUMN_DISPLAY_NAME,D.Document.COLUMN_MIME_TYPE,D.Document.COLUMN_FLAGS,D.Document.COLUMN_SIZE);val c=MatrixCursor(cols)
            if(uri.lastPathSegment=="children"){children.forEach{(id,label)->c.addRow(cols.map{when(it){D.Document.COLUMN_DOCUMENT_ID->id;D.Document.COLUMN_DISPLAY_NAME->label;else->null}}.toTypedArray<Any?>())};if(loading)c.extras=Bundle().apply{putBoolean(D.EXTRA_LOADING,true)};return c}
            val id=D.getDocumentId(uri);val root=id=="root";c.addRow(cols.map{when(it){D.Document.COLUMN_DOCUMENT_ID->id;D.Document.COLUMN_DISPLAY_NAME->if(root)"Destination" else actualName?:name;D.Document.COLUMN_MIME_TYPE->if(root)D.Document.MIME_TYPE_DIR else "application/octet-stream";D.Document.COLUMN_FLAGS->if(root){if(writable)D.Document.FLAG_DIR_SUPPORTS_CREATE else 0}else D.Document.FLAG_SUPPORTS_WRITE;D.Document.COLUMN_SIZE->if(root)null else if(reportEmpty)0L else file.length();else->null}}.toTypedArray<Any?>());return c
        }
        override fun call(method:String,arg:String?,extras:Bundle?):Bundle = when(method){
            "android:createDocument"->{creates++;name=extras!!.getString(D.Document.COLUMN_DISPLAY_NAME)!!;Bundle().apply{putParcelable("uri",Uri.parse("content://$returnedAuthority/document/$returnedId"))}}
            "android:isChildDocument"->Bundle().apply{putBoolean("result",child)}
            else->throw IllegalArgumentException("Unexpected fixture method")
        }
        override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor{modes+=mode;return ParcelFileDescriptor.open(file,ParcelFileDescriptor.parseMode(mode))}
        override fun getType(uri:Uri)="application/octet-stream"
        override fun insert(uri:Uri,values:ContentValues?):Uri?=error("No insert")
        override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?):Int=error("Never delete a provider document")
        override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?):Int=error("Never update an existing document")
    }
    private lateinit var context:Context;private lateinit var p:Provider
    @Before fun setup(){context=ApplicationProvider.getApplicationContext();p=Provider();p.attachInfo(context,ProviderInfo().apply{authority=AUTHORITY;exported=true;grantUriPermissions=true});p.file=File(context.cacheDir,"provider-${UUID.randomUUID()}.bin").apply{writeBytes(byteArrayOf())};ShadowContentResolver.registerProviderInternal(AUTHORITY,p)}
    @After fun cleanup(){if(::p.isInitialized)runCatching{p.file.delete()}}
    private fun adapter(flags:Int=3)=DocumentsExportProvider(context,TREE,flags)
    private fun failure(code:String,body:()->Unit){try{body();org.junit.Assert.fail("Expected failure")}catch(e:ConnectionFailure){assertEquals(code,e.code)}}
    @Test fun createNewUsesAppendNotTruncateAndAnIndependentReadback(){adapter().use{a->a.preflight(TREE,"copy.bin");val uri=a.create(TREE,"copy.bin");a.writer(uri).use{it.write(byteArrayOf(1,2,3));it.flush()};assertArrayEquals(byteArrayOf(1,2,3),a.reader(uri).use{it.readBytes()})};assertEquals(listOf("r","wa","r"),p.modes);assertEquals(1,p.creates)}
    @Test fun readOnlyOrMissingTemporaryWriteGrantCannotCreate(){failure("PROVIDER_PERMISSION_LOST"){adapter(1).use{it.preflight(TREE,"copy.bin")}};assertEquals(0,p.calls);p.writable=false;failure("PROVIDER_READ_ONLY"){adapter().use{it.preflight(TREE,"copy.bin")}};assertEquals(0,p.creates)}
    @Test fun normalizedCaseInsensitiveCollisionIsRejectedBeforeCreate(){p.children+="old" to "COPY.BIN";failure("EXPORT_NAME_COLLISION"){adapter().use{it.preflight(TREE,"copy.bin")}};p.children.clear();p.children+="old" to "e\u0301.txt";failure("EXPORT_NAME_COLLISION"){adapter().use{it.preflight(TREE,"é.txt")}};assertEquals(0,p.creates)}
    @Test fun boundedSnapshotRejectsMoreThan256Children(){repeat(257){p.children+="old$it" to "item$it"};failure("PROVIDER_DIRECTORY_LIMIT"){adapter().use{it.preflight(TREE,"copy.bin")}};assertEquals(0,p.creates)}
    @Test fun loadingProviderCannotPretendItsPartialSnapshotIsComplete(){p.loading=true;failure("PROVIDER_DIRECTORY_INCOMPLETE"){adapter().use{it.preflight(TREE,"copy.bin")}};assertEquals(0,p.creates)}
    @Test fun reusedChildIdentityNeverOpensAnExistingFileForOutput(){p.children+="old" to "keep.bin";p.returnedId="old";p.file.writeText("KEEP");failure("PROVIDER_REUSED_DOCUMENT"){adapter().use{it.preflight(TREE,"copy.bin");it.create(TREE,"copy.bin")}};assertTrue(p.modes.isEmpty());assertEquals("KEEP",p.file.readText())}
    @Test fun providerRenamedDocumentIsNotWritten(){p.actualName="unexpected.bin";failure("PROVIDER_NAME_CHANGED"){adapter().use{a->a.preflight(TREE,"copy.bin");val uri=a.create(TREE,"copy.bin");a.writer(uri)}};assertEquals(1,p.creates);assertTrue(p.modes.isEmpty());assertEquals(0L,p.file.length())}
    @Test fun dishonestEmptyMetadataStillGetsIndependentEmptyContentCheck(){p.reportEmpty=true;p.file.writeText("KEEP");failure("PROVIDER_NOT_EMPTY"){adapter().use{a->a.preflight(TREE,"copy.bin");a.writer(a.create(TREE,"copy.bin"))}};assertEquals(listOf("r"),p.modes);assertEquals("KEEP",p.file.readText())}
    @Test fun foreignAuthorityOrNonDescendantNeverGetsAWriter(){p.returnedAuthority="foreign.provider";failure("INVALID_EXPORT_TARGET"){adapter().use{it.preflight(TREE,"copy.bin");it.create(TREE,"copy.bin")}};p.returnedAuthority=AUTHORITY;p.child=false;failure("INVALID_EXPORT_TARGET"){adapter().use{it.preflight(TREE,"copy.bin");it.create(TREE,"copy.bin")}};assertTrue(p.modes.isEmpty())}
    @Test fun ownProviderAndNonTreeUriHaveNoWriteAuthority(){failure("INVALID_EXPORT_TARGET"){DocumentsExportProvider(context,"content://${context.packageName}.private/tree/root",3).preflight("content://${context.packageName}.private/tree/root","copy.bin")};failure("INVALID_EXPORT_TARGET"){DocumentsExportProvider(context,"content://$AUTHORITY/document/root",3).preflight("content://$AUTHORITY/document/root","copy.bin")};assertEquals(0,p.creates)}
    @Test fun cancelledAdapterAndConsumedCreateApprovalCannotBeReused(){adapter().use{a->a.preflight(TREE,"copy.bin");a.create(TREE,"copy.bin");failure("INVALID_EXPORT_TARGET"){a.create(TREE,"copy.bin")};a.cancel();failure("EXPORT_INTERRUPTED"){a.preflight(TREE,"other.bin")}};assertEquals(1,p.creates)}
    companion object {const val AUTHORITY="com.narada.fixture.documents";const val TREE="content://com.narada.fixture.documents/tree/root"}
}
