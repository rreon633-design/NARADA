package com.narada.android

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.narada.android.data.*
import com.narada.android.platform.PersistedReadPermissions
import com.narada.android.work.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*

class TestFixture : AutoCloseable {
    val context: Context = ApplicationProvider.getApplicationContext()
    val name = "test-${UUID.randomUUID()}.db"
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val settingsFile = File(context.cacheDir, "${UUID.randomUUID()}.preferences_pb")
    val database = MetadataDatabase.create(context, name)
    val store = MetadataStore(database)
    val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope, produceFile = { settingsFile }))
    val permissions = FakePermissions()
    val scheduler = FakeScheduler()
    val coordinator = WorkCoordinator(store, scheduler, scope)
    override fun close() {
        runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
        database.close(); context.deleteDatabase(name)
        File(context.noBackupFilesDir, "$name.enrolled").delete()
        settingsFile.delete()
    }
}
class FakePermissions : PersistedReadPermissions {
    val held = mutableSetOf<String>()
    var offerActuallyRetains = true
    var failRelease = false
    val retainedFlags = mutableListOf<Int>()
    override fun readableUris(): Set<String> = held.toSet()
    override fun take(uri: Uri, flags: Int) { retainedFlags += flags; if (offerActuallyRetains) held += uri.toString() }
    override fun release(uri: Uri, flags: Int) { if (failRelease) throw SecurityException("TEST_ONLY"); held -= uri.toString() }
}
class FakeScheduler : WorkScheduler {
    val states = mutableMapOf<String, ScheduledState>()
    val enqueued = mutableListOf<String>()
    var rejectEnqueue = false
    override suspend fun enqueue(id: String) {
        if (rejectEnqueue) throw IllegalStateException("TEST_ONLY")
        enqueued += id; states[id] = ScheduledState.ACTIVE
    }
    override suspend fun state(id: String) = states[id] ?: ScheduledState.MISSING
    override suspend fun cancel(id: String) { states[id] = ScheduledState.CANCELLED }
}

inline fun <T> MetadataDatabase.use(block: (MetadataDatabase) -> T): T = try { block(this) } finally { close() }
