package com.narada.android

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.narada.android.data.*
import com.narada.domain.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MetadataStoreTest {
    private lateinit var f: TestFixture
    @Before fun setup() { f = TestFixture() }
    @After fun cleanup() { f.close() }
    @Test fun freshDatabaseIsSchemaThreeAndReadable() = runBlocking {
        assertTrue(f.store.checkDatabase()); assertEquals(3, f.database.openHelper.readableDatabase.version)
    }
    @Test fun eventsAreBoundedAndIdempotent() = runBlocking {
        repeat(210) { f.store.record(ActivityKind.SETTINGS_SAVED, "event-$it", it.toLong()) }
        f.store.record(ActivityKind.SETTINGS_SAVED, "event-209", 209L)
        assertEquals(200, f.database.dao().countEvents()); assertEquals(30, f.store.snapshots.first().events.size)
    }
    @Test fun repeatedRequestsReturnOneDurableOperationId() = runBlocking {
        val ids = List(12) { async(Dispatchers.Default) { f.store.requestJob().operationId } }.awaitAll()
        assertEquals(1, ids.distinct().size); assertEquals(1, f.database.dao().countEvents())
    }
    @Test fun cancellationPreventsLateCompletion() = runBlocking {
        val job = f.store.requestJob(); assertTrue(f.store.startJob(job.operationId))
        assertTrue(f.store.finishJob(job.operationId, LocalJobState.CANCELLED))
        assertFalse(f.store.finishJob(job.operationId, LocalJobState.COMPLETED))
        assertEquals(LocalJobState.CANCELLED, f.store.currentJob()?.state)
    }
    @Test fun staleWorkerCannotOverwriteNewGeneration() = runBlocking {
        val first = f.store.requestJob(); f.store.finishJob(first.operationId, LocalJobState.CANCELLED)
        val second = f.store.requestJob(); assertNotEquals(first.operationId, second.operationId)
        assertFalse(f.store.startJob(first.operationId)); assertFalse(f.store.finishJob(first.operationId, LocalJobState.COMPLETED))
        assertEquals(second, f.store.currentJob())
    }
    @Test fun clearingEventsDoesNotDeleteGrantsOrJob() = runBlocking {
        f.store.requestJob(); f.store.retainGrant("content://test/tree/root", 1); f.store.clearActivity()
        assertEquals(0, f.database.dao().countEvents()); assertNotNull(f.store.currentJob()); assertEquals(1, f.store.grants().size)
    }
    @Test fun metadataSurvivesDatabaseReopen() = runBlocking {
        val first = f.store.requestJob(); f.store.retainGrant("content://test/tree/root", 1); f.database.close()
        MetadataDatabase.create(f.context, f.name).use { reopened ->
            assertEquals(first.operationId, MetadataStore(reopened).currentJob()?.operationId)
            assertEquals(1, reopened.dao().grants().size)
        }
    }
    @Test fun missingEnrolledDatabaseIsNotSilentlyCreated() = runBlocking {
        f.store.checkDatabase(); f.database.close(); f.context.deleteDatabase(f.name)
        val marker = File(f.context.noBackupFilesDir, "${f.name}.enrolled")
        assertTrue(marker.exists())
        MetadataDatabase.create(f.context, f.name).use { reopened ->
            assertThrows(Exception::class.java) { reopened.openHelper.writableDatabase }
        }
        assertFalse(f.context.getDatabasePath(f.name).exists()); assertTrue(marker.exists())
    }
    @Test fun corruptDatabaseBytesArePreserved() {
        val file = f.context.getDatabasePath(f.name); file.parentFile!!.mkdirs()
        val bytes = "NOT_A_SQLITE_DATABASE_DO_NOT_DELETE".toByteArray(); file.writeBytes(bytes)
        assertThrows(Exception::class.java) { f.database.openHelper.writableDatabase }
        assertArrayEquals(bytes, file.readBytes())
    }
    @Test fun unknownEnumFailsInsteadOfInventingAnEmptyWorkspace() = runBlocking {
        f.database.dao().putJob(JobRow(operationId = UUID.randomUUID().toString(), state = "UNRECOGNIZED", requestedAt = 1, updatedAt = 1))
        val result = runCatching { f.store.snapshots.first() }
        assertTrue(result.isFailure)
    }
    @Test fun schemaOneMigrationPreservesRowsAndAddsSafeDefaults() = runBlocking {
        val path = f.context.getDatabasePath(f.name); path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            old.execSQL("CREATE TABLE local_events (id TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL, created_at INTEGER NOT NULL)")
            old.execSQL("CREATE TABLE health_job (slot INTEGER NOT NULL PRIMARY KEY, operation_id TEXT NOT NULL, state TEXT NOT NULL, requested_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
            old.execSQL("CREATE TABLE uri_grants (id TEXT NOT NULL PRIMARY KEY, uri TEXT NOT NULL, label TEXT NOT NULL, flags INTEGER NOT NULL, retained_read INTEGER NOT NULL, created_at INTEGER NOT NULL)")
            old.execSQL("CREATE UNIQUE INDEX index_uri_grants_uri ON uri_grants(uri)")
            old.execSQL("INSERT INTO local_events VALUES ('event', 'SETUP_COMPLETED', 10)")
            old.execSQL("INSERT INTO health_job VALUES (1, 'old-operation', 'QUEUED', 10, 10)")
            old.execSQL("INSERT INTO uri_grants VALUES ('grant', 'content://test/tree/root', 'Folder', 1, 1, 10)")
            old.version = 1
        }
        assertTrue(f.store.checkDatabase()); assertEquals("old-operation", f.store.currentJob()?.operationId)
        assertNull(f.store.currentJob()?.errorCode); assertEquals(0L, f.store.grants().single().lastCheckedAt)
        assertEquals(1, f.database.dao().countEvents()); assertEquals(3, f.database.openHelper.readableDatabase.version)
    }
    @Test fun phaseOneSchemaTwoUpgradePreservesRowsAndAddsEmptyAnchor() = runBlocking<Unit> {
        val path = f.context.getDatabasePath(f.name); path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            old.execSQL("CREATE TABLE local_events (id TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL, created_at INTEGER NOT NULL)")
            old.execSQL("CREATE TABLE health_job (slot INTEGER NOT NULL PRIMARY KEY, operation_id TEXT NOT NULL, state TEXT NOT NULL, requested_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, error_code TEXT)")
            old.execSQL("CREATE TABLE uri_grants (id TEXT NOT NULL PRIMARY KEY, uri TEXT NOT NULL, label TEXT NOT NULL, flags INTEGER NOT NULL, retained_read INTEGER NOT NULL, created_at INTEGER NOT NULL, last_checked_at INTEGER NOT NULL DEFAULT 0)")
            old.execSQL("CREATE UNIQUE INDEX index_uri_grants_uri ON uri_grants(uri)")
            old.execSQL("INSERT INTO local_events VALUES ('retained', 'SETUP_COMPLETED', 10)")
            old.version = 2
        }
        assertTrue(f.store.checkDatabase()); assertEquals(3, f.database.openHelper.readableDatabase.version)
        assertEquals(1, f.database.dao().countEvents()); assertNull(f.database.dao().connectionAnchor())
    }
    @Test fun realRoomAnchorReservationSurvivesReopenAndDoesNotOverwrite() = runBlocking<Unit> {
        val id = UUID.randomUUID().toString()
        f.database.dao().reserveConnectionAnchor(ConnectionAnchor(storeId=id,status="PREPARING",deviceId=null,fingerprint=null))
        assertEquals(1,f.database.dao().bindConnectionAnchor(id,"a".repeat(64),"b".repeat(64)))
        assertEquals(0,f.database.dao().bindConnectionAnchor(id,"c".repeat(64),"d".repeat(64)))
        f.database.close()
        MetadataDatabase.create(f.context,f.name).use { reopened -> assertEquals("a".repeat(64),reopened.dao().connectionAnchor()!!.deviceId) }
    }
    @Test fun unsupportedNewerSchemaIsNotDestructivelyDowngraded() {
        val path = f.context.getDatabasePath(f.name); path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old -> old.execSQL("CREATE TABLE sentinel (value TEXT)"); old.execSQL("INSERT INTO sentinel VALUES ('KEEP')"); old.version = 99 }
        assertThrows(Exception::class.java) { f.database.openHelper.writableDatabase }
        SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
            raw.rawQuery("SELECT value FROM sentinel", null).use { c -> assertTrue(c.moveToFirst()); assertEquals("KEEP", c.getString(0)) }
        }
    }
}
