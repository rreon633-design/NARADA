package com.narada.android

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.core.content.FileProvider
import com.narada.android.data.SettingsStore
import com.narada.android.platform.*
import com.narada.domain.*
import java.io.File
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SettingsAndPlatformTest {
    private lateinit var f: TestFixture
    @Before fun setup() {
        // Robolectric changes dataDir between methods but AndroidX caches roots by
        // authority for the entire JVM. Clear that TEST-only cache to model a new
        // process, without broadening the production provider or weakening assertions.
        val cache=FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible=true }
        (cache.get(null) as MutableMap<*, *>).clear()
        f = TestFixture()
    }
    @After fun cleanup() { f.close() }
    @Test fun freshSettingsDoNotEnablePermissionsOrSetup() = runBlocking<Unit> {
        val s=f.settings.current(); assertFalse(s.setupCompleted); assertFalse(s.notificationsEnabled); assertEquals("My phone",s.deviceName)
    }
    @Test fun setupAndPreferencesSurviveAStoreReopen() = runBlocking<Unit> {
        f.settings.completeSetup(" My actual phone "); f.settings.reduceMotion(true); f.settings.notifications(true)
        f.scope.coroutineContext[Job]?.cancelAndJoin()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        try {
            val reopened=SettingsStore(PreferenceDataStoreFactory.create(scope=scope,produceFile={f.settingsFile}))
            val s=reopened.current();assertTrue(s.setupCompleted);assertTrue(s.reduceMotion);assertTrue(s.notificationsEnabled);assertEquals("My actual phone",s.deviceName)
        } finally { scope.coroutineContext[Job]?.cancelAndJoin() }
    }
    @Test fun corruptedPreferencesAreNotReplacedByDefaults() = runBlocking<Unit> {
        val bytes=byteArrayOf(-1,-1,-1,-1,0,8); f.settingsFile.writeBytes(bytes)
        assertTrue(runCatching { f.settings.current() }.isFailure)
        assertArrayEquals(bytes,f.settingsFile.readBytes())
        assertTrue(runCatching { f.settings.changeName("Replacement") }.isFailure)
        assertArrayEquals(bytes,f.settingsFile.readBytes())
    }
    @Test fun invalidNameDoesNotModifyCommittedSettings() = runBlocking<Unit> {
        f.settings.completeSetup("Original")
        assertTrue(runCatching { f.settings.changeName(" ") }.isFailure)
        assertEquals("Original",f.settings.current().deviceName)
    }
    @Test fun pickerRequestsReadOnlyTreeAccess() {
        val i=FolderPermissions.pickerIntent();assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE,i.action)
        assertEquals(0,i.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertNotEquals(0,i.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    @Test fun persistableGrantStripsOfferedWriteAndIsActuallyConfirmed() = runBlocking<Unit> {
        val adapter=FolderPermissions(f.permissions,f.store)
        adapter.retain(Uri.parse("content://test/tree/primary%3AFolder"),1 or 2 or 64)
        assertEquals(listOf(1),f.permissions.retainedFlags);assertEquals(1,f.store.grants().single().flags)
    }
    @Test fun transientGrantDoesNotCreateARecord() = runBlocking<Unit> {
        val adapter=FolderPermissions(f.permissions,f.store)
        assertTrue(runCatching { adapter.retain(Uri.parse("content://test/tree/root"),1) }.isFailure)
        assertTrue(f.store.grants().isEmpty());assertTrue(f.permissions.held.isEmpty())
    }
    @Test fun providerThatDoesNotRetainPermissionCannotAppearGranted() = runBlocking<Unit> {
        f.permissions.offerActuallyRetains=false
        assertTrue(runCatching { FolderPermissions(f.permissions,f.store).retain(Uri.parse("content://test/tree/root"),65) }.isFailure)
        assertFalse(f.store.grants().single().retainedRead)
    }
    @Test fun fileUrisAndNonTreeUrisAreRejected() = runBlocking<Unit> {
        val adapter=FolderPermissions(f.permissions,f.store)
        listOf("file:///sdcard/private","content://test/document/root").forEach { assertTrue(runCatching { adapter.retain(Uri.parse(it),65) }.isFailure) }
        assertTrue(f.permissions.held.isEmpty())
    }
    @Test fun crashBetweenOsConsentAndReceiptKeepsARecoverableRecord() = runBlocking<Unit> {
        val uri="content://test/tree/pending"
        f.store.reserveGrant(uri);f.permissions.held += uri
        FolderPermissions(f.permissions,f.store).reconcile()
        assertTrue(f.store.grants().single().retainedRead)
    }
    @Test fun lostGrantStaysVisibleAsMissingInsteadOfDisappearing() = runBlocking<Unit> {
        val adapter=FolderPermissions(f.permissions,f.store);adapter.retain(Uri.parse("content://test/tree/root"),65)
        f.permissions.held.clear();adapter.reconcile()
        assertEquals(1,f.store.grants().size);assertFalse(f.store.grants().single().retainedRead)
    }
    @Test fun grantLimitIsEnforcedWithoutAcquiringExtraPermission() = runBlocking<Unit> {
        val adapter=FolderPermissions(f.permissions,f.store)
        repeat(8) { adapter.retain(Uri.parse("content://test/tree/root$it"),65) }
        assertTrue(runCatching { adapter.retain(Uri.parse("content://test/tree/ninth"),65) }.isFailure)
        assertEquals(8,f.permissions.held.size);assertEquals(8,f.store.grants().size)
    }
    @Test fun failedReleaseKeepsTheLocalGrantRecord() = runBlocking<Unit> {
        val adapter=FolderPermissions(f.permissions,f.store);adapter.retain(Uri.parse("content://test/tree/root"),65)
        f.permissions.failRelease=true
        assertTrue(runCatching { adapter.forget(f.store.grants().single().id) }.isFailure)
        assertEquals(1,f.store.grants().size)
    }
    @Test fun privateProbeCleansUpOnlyItsOwnSmallFile() = runBlocking<Unit> {
        val dir=File(f.context.noBackupFilesDir,"local-checks").also { it.mkdirs() }; val keep=File(dir,"unrelated.keep").also { it.writeText("KEEP") }
        assertTrue(PrivateFiles(f.context).probe());assertEquals("KEEP",keep.readText())
        assertTrue(dir.listFiles()!!.none { it.extension=="probe" });keep.delete()
    }
    @Test fun interruptedPrivateProbeIsReclaimedWithoutTouchingOtherNames() = runBlocking<Unit> {
        val dir=File(f.context.noBackupFilesDir,"local-checks").also { it.mkdirs() }
        val orphan=File(dir,"12345678-1234-1234-1234-123456789abc.probe").also { it.writeText("INTERRUPTED") }
        val keep=File(dir,"not-an-owned-probe.probe").also { it.writeText("KEEP") }
        assertTrue(PrivateFiles(f.context).probe());assertFalse(orphan.exists());assertEquals("KEEP",keep.readText())
        keep.delete()
    }
    @Test fun concurrentPrivateChecksShareOneSerializedProbeNamespace() = runBlocking<Unit> {
        val files=PrivateFiles(f.context)
        val results=List(8) { async(Dispatchers.Default) { files.probe() } }.awaitAll()
        assertTrue(results.all { it });assertTrue(File(f.context.noBackupFilesDir,"local-checks").listFiles()!!.none { it.extension=="probe" })
    }
    @Test fun reportExportUsesContentUriAndOmitsPersonalSentinels() = runBlocking<Unit> {
        f.settings.completeSetup("PERSONAL_DEVICE_SENTINEL")
        f.store.retainGrant("content://private/tree/PERSONAL_PATH_SENTINEL",1)
        val uri=PrivateFiles(f.context).exportReport(SupportReport("0.1.0",35,1,true,true,1,false,null))
        assertEquals("content",uri.scheme);assertEquals("${f.context.packageName}.support",uri.authority)
        val body=f.context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
        assertFalse(body.contains("PERSONAL"));assertFalse(body.contains("content://"));assertFalse(body.contains("deviceName"))
    }
    @Test fun providerCannotExposeAnArbitraryPrivateFile() {
        val outside=File(f.context.filesDir,"private-outside.txt").also { it.writeText("DO_NOT_EXPORT") }
        assertThrows(IllegalArgumentException::class.java) { FileProvider.getUriForFile(f.context,"${f.context.packageName}.support",outside) }
        outside.delete()
    }
}
