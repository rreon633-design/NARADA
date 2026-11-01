package com.narada.android.data

import androidx.room.withTransaction
import com.narada.domain.*
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext

@Singleton
class MetadataStore @Inject constructor(private val db: MetadataDatabase) {
    private val dao = db.dao()
    data class Snapshot(val events: List<LocalActivity>, val job: LocalJob?, val grants: List<FolderGrant>)
    val snapshots = combine(dao.observeEvents(), dao.observeJob(), dao.observeGrants()) { events, job, grants ->
        Snapshot(events.map { LocalActivity(it.id, ActivityKind.valueOf(it.kind), it.createdAt) }, job?.domain(),
            grants.map { FolderGrant(it.id, it.label, it.retainedRead) })
    }
    suspend fun record(kind: ActivityKind, id: String = UUID.randomUUID().toString(), now: Long = System.currentTimeMillis()) = db.withTransaction {
        dao.insertEvent(EventRow(id, kind.name, now)); dao.trimEvents()
    }
    suspend fun clearActivity() = dao.clearEvents()
    suspend fun currentJob(): LocalJob? = dao.job()?.domain()
    suspend fun requestJob(): LocalJob = db.withTransaction {
        dao.job()?.domain()?.takeIf { it.state.active } ?: run {
            val now = System.currentTimeMillis()
            val row = JobRow(operationId = UUID.randomUUID().toString(), state = LocalJobState.QUEUED.name, requestedAt = now, updatedAt = now)
            dao.putJob(row)
            record(ActivityKind.CHECK_QUEUED, "${row.operationId}:queued", now)
            row.domain()
        }
    }
    suspend fun startJob(id: String): Boolean = db.withTransaction {
        val job = dao.job() ?: return@withTransaction false
        if (job.operationId != id || !job.domain().state.active) return@withTransaction false
        dao.putJob(job.copy(state = LocalJobState.RUNNING.name, updatedAt = System.currentTimeMillis()))
        true
    }
    suspend fun finishJob(id: String, state: LocalJobState, error: FailureCode? = null): Boolean = db.withTransaction {
        require(!state.active)
        val job = dao.job() ?: return@withTransaction false
        if (job.operationId != id || !job.domain().state.active) return@withTransaction false
        val now = System.currentTimeMillis()
        dao.putJob(job.copy(state = state.name, updatedAt = now, errorCode = error?.name))
        val event = when (state) {
            LocalJobState.COMPLETED -> ActivityKind.CHECK_COMPLETED
            LocalJobState.CANCELLED -> ActivityKind.CHECK_CANCELLED
            else -> ActivityKind.CHECK_FAILED
        }
        record(event, "$id:terminal", now)
        true
    }
    suspend fun grants(): List<GrantRow> = dao.grants()
    suspend fun grant(id: String): GrantRow? = dao.grantById(id)
    /** Durable pending record closes the crash window around the OS permission transaction. */
    suspend fun reserveGrant(uri: String): GrantRow = db.withTransaction {
        dao.grantByUri(uri) ?: run {
            if (dao.grants().size >= FoundationLimits.MAX_GRANTS) throw FoundationFailure(FailureCode.GRANT_LIMIT)
            val id = UUID.randomUUID().toString()
            val row = GrantRow(id, uri, "Folder · ${id.take(4)}", GrantPolicy.READ, false, System.currentTimeMillis())
            dao.putGrant(row)
            row
        }
    }
    suspend fun retainGrant(uri: String, flags: Int): GrantRow = db.withTransaction {
        require(flags == GrantPolicy.READ)
        val existing = dao.grantByUri(uri)
        if (existing == null && dao.grants().size >= FoundationLimits.MAX_GRANTS) throw FoundationFailure(FailureCode.GRANT_LIMIT)
        val now = System.currentTimeMillis()
        val id = existing?.id ?: UUID.randomUUID().toString()
        val row = GrantRow(id, uri, existing?.label ?: "Folder · ${id.take(4)}", flags, true, existing?.createdAt ?: now, now)
        dao.putGrant(row)
        if (existing == null || !existing.retainedRead) record(ActivityKind.FOLDER_RETAINED)
        row
    }
    suspend fun forgetGrant(id: String) = db.withTransaction { dao.deleteGrant(id); record(ActivityKind.FOLDER_FORGOTTEN) }
    suspend fun reconcileGrants(readableUris: Set<String>) = db.withTransaction {
        val now = System.currentTimeMillis()
        dao.grants().forEach { dao.putGrant(it.copy(retainedRead = it.uri in readableUris, lastCheckedAt = now)) }
    }
    suspend fun checkDatabase(): Boolean = withContext(Dispatchers.IO) {
        db.openHelper.readableDatabase.query("PRAGMA quick_check(1)").use { it.moveToFirst() && it.getString(0) == "ok" }
    }
    private fun JobRow.domain() = LocalJob(operationId, LocalJobState.valueOf(state), requestedAt, updatedAt,
        errorCode?.let(FailureCode::valueOf))
}
