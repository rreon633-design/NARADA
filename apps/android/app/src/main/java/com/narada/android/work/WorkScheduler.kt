package com.narada.android.work

import android.content.Context
import androidx.work.*
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class ScheduledState { MISSING, ACTIVE, SUCCEEDED, CANCELLED, FAILED }
interface WorkScheduler {
    suspend fun enqueue(id: String)
    suspend fun state(id: String): ScheduledState
    suspend fun cancel(id: String)
}

@Singleton
class AndroidWorkScheduler @Inject constructor(@param:ApplicationContext private val context: Context) : WorkScheduler {
    private val manager: WorkManager get() = WorkManager.getInstance(context)
    override suspend fun enqueue(id: String) = withContext(Dispatchers.IO) {
        val request = OneTimeWorkRequestBuilder<WorkspaceHealthWorker>()
            .setId(UUID.fromString(id)).setInputData(workDataOf(WorkspaceHealthWorker.OPERATION_ID to id))
            .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
            .addTag(TAG).build()
        manager.enqueueUniqueWork("$TAG:$id", ExistingWorkPolicy.KEEP, request).result.get(10, TimeUnit.SECONDS)
        Unit
    }
    override suspend fun state(id: String): ScheduledState = withContext(Dispatchers.IO) {
        when (manager.getWorkInfoById(UUID.fromString(id)).get(10, TimeUnit.SECONDS)?.state) {
            null -> ScheduledState.MISSING
            WorkInfo.State.SUCCEEDED -> ScheduledState.SUCCEEDED
            WorkInfo.State.FAILED -> ScheduledState.FAILED
            WorkInfo.State.CANCELLED -> ScheduledState.CANCELLED
            else -> ScheduledState.ACTIVE
        }
    }
    override suspend fun cancel(id: String) = withContext(Dispatchers.IO) {
        manager.cancelWorkById(UUID.fromString(id)).result.get(10, TimeUnit.SECONDS)
        Unit
    }
    companion object { const val TAG = "narada-local-check" }
}
