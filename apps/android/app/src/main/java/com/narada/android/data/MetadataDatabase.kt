package com.narada.android.data

import com.narada.android.platform.readAtMost
import android.content.Context
import android.database.sqlite.SQLiteDatabaseCorruptException
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import android.util.AtomicFile
import java.io.File
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "local_events")
data class EventRow(@PrimaryKey val id: String, val kind: String, @ColumnInfo(name = "created_at") val createdAt: Long)

@Entity(tableName = "health_job")
data class JobRow(
    @PrimaryKey val slot: Int = 1,
    @ColumnInfo(name = "operation_id") val operationId: String,
    val state: String,
    @ColumnInfo(name = "requested_at") val requestedAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "error_code") val errorCode: String? = null,
)

@Entity(tableName = "uri_grants", indices = [Index(value = ["uri"], unique = true)])
data class GrantRow(
    @PrimaryKey val id: String, val uri: String, val label: String, val flags: Int,
    @ColumnInfo(name = "retained_read") val retainedRead: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "last_checked_at", defaultValue = "0") val lastCheckedAt: Long = 0,
)

@Entity(tableName = "connection_anchor")
data class ConnectionAnchor(
    @PrimaryKey val slot: Int = 1,
    @ColumnInfo(name = "store_id") val storeId: String,
    val status: String,
    @ColumnInfo(name = "device_id") val deviceId: String?,
    val fingerprint: String?,
)

@Dao
interface MetadataDao {
    @Query("SELECT * FROM connection_anchor WHERE slot = 1") suspend fun connectionAnchor(): ConnectionAnchor?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun reserveConnectionAnchor(anchor: ConnectionAnchor)
    @Query("UPDATE connection_anchor SET status = 'READY', device_id = :deviceId, fingerprint = :fingerprint WHERE slot = 1 AND store_id = :storeId AND status = 'PREPARING'")
    suspend fun bindConnectionAnchor(storeId: String, deviceId: String, fingerprint: String): Int

    @Query("SELECT * FROM local_events ORDER BY created_at DESC, id DESC LIMIT 30") fun observeEvents(): Flow<List<EventRow>>
    @Query("SELECT * FROM health_job WHERE slot = 1") fun observeJob(): Flow<JobRow?>
    @Query("SELECT * FROM uri_grants ORDER BY created_at, id") fun observeGrants(): Flow<List<GrantRow>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertEvent(event: EventRow)
    @Query("DELETE FROM local_events WHERE id NOT IN (SELECT id FROM local_events ORDER BY created_at DESC, id DESC LIMIT 200)") suspend fun trimEvents()
    @Query("SELECT COUNT(*) FROM local_events") suspend fun countEvents(): Int
    @Query("DELETE FROM local_events") suspend fun clearEvents()
    @Query("SELECT * FROM health_job WHERE slot = 1") suspend fun job(): JobRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putJob(job: JobRow)
    @Query("SELECT * FROM uri_grants ORDER BY created_at, id") suspend fun grants(): List<GrantRow>
    @Query("SELECT * FROM uri_grants WHERE uri = :uri LIMIT 1") suspend fun grantByUri(uri: String): GrantRow?
    @Query("SELECT * FROM uri_grants WHERE id = :id LIMIT 1") suspend fun grantById(id: String): GrantRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putGrant(grant: GrantRow)
    @Query("DELETE FROM uri_grants WHERE id = :id") suspend fun deleteGrant(id: String)
}

@Database(entities = [EventRow::class, JobRow::class, GrantRow::class, ConnectionAnchor::class], version = 3, exportSchema = true)
abstract class MetadataDatabase : RoomDatabase() {
    abstract fun dao(): MetadataDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE health_job ADD COLUMN error_code TEXT")
                db.execSQL("ALTER TABLE uri_grants ADD COLUMN last_checked_at INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS connection_anchor (slot INTEGER NOT NULL, store_id TEXT NOT NULL, status TEXT NOT NULL, device_id TEXT, fingerprint TEXT, PRIMARY KEY(slot))")
            }
        }
        fun create(context: Context, name: String = "narada-metadata.db"): MetadataDatabase =
            Room.databaseBuilder(context, MetadataDatabase::class.java, name)
                .openHelperFactory(PreservingOpenHelperFactory())
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build() // No destructive migration or corruption replacement.
    }
}

/** Prevent default SQLite corruption recovery from deleting the user's ledger. */
class PreservingOpenHelperFactory : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val original = configuration.callback
        val callback = object : SupportSQLiteOpenHelper.Callback(original.version) {
            override fun onConfigure(db: SupportSQLiteDatabase) = original.onConfigure(db)
            override fun onCreate(db: SupportSQLiteDatabase) = original.onCreate(db)
            override fun onOpen(db: SupportSQLiteDatabase) = original.onOpen(db)
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = original.onUpgrade(db, oldVersion, newVersion)
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = original.onDowngrade(db, oldVersion, newVersion)
            override fun onCorruption(db: SupportSQLiteDatabase) {
                throw SQLiteDatabaseCorruptException("NARADA_METADATA_PRESERVED")
            }
        }
        val safe = SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(configuration.name).callback(callback)
            .noBackupDirectory(configuration.useNoBackupDirectory)
            .allowDataLossOnRecovery(false).build()
        val delegate = FrameworkSQLiteOpenHelperFactory().create(safe)
        val name = configuration.name ?: return delegate
        val databaseFile = if (configuration.useNoBackupDirectory) File(configuration.context.noBackupFilesDir, name)
            else configuration.context.getDatabasePath(name)
        val marker = AtomicFile(File(configuration.context.noBackupFilesDir, "$name.enrolled"))
        return object : SupportSQLiteOpenHelper {
            override val databaseName: String? get() = delegate.databaseName
            override fun setWriteAheadLoggingEnabled(enabled: Boolean) = delegate.setWriteAheadLoggingEnabled(enabled)
            override val writableDatabase: SupportSQLiteDatabase get() = open { delegate.writableDatabase }
            override val readableDatabase: SupportSQLiteDatabase get() = open { delegate.readableDatabase }
            @Synchronized private fun open(getDatabase: () -> SupportSQLiteDatabase): SupportSQLiteDatabase {
                val markerPresent = marker.baseFile.exists() || File(marker.baseFile.path + ".bak").exists() || File(marker.baseFile.path + ".new").exists()
                if (markerPresent) {
                    val stamp = marker.openRead().use { it.readAtMost(33).toString(Charsets.US_ASCII) }
                    if (stamp != "NARADA_METADATA_V1" || !databaseFile.isFile) {
                        throw SQLiteDatabaseCorruptException("NARADA_METADATA_MISSING_OR_MARKER_INVALID")
                    }
                }
                val db = getDatabase()
                if (!marker.baseFile.exists()) {
                    val stream = marker.startWrite()
                    try { stream.write("NARADA_METADATA_V1".toByteArray(Charsets.US_ASCII)); marker.finishWrite(stream) }
                    catch (failure: Exception) { marker.failWrite(stream); throw failure }
                }
                return db
            }
            override fun close() = delegate.close()
        }
    }
}
