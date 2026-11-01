package com.narada.android.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.narada.android.data.MetadataStore
import com.narada.android.data.SettingsStore
import com.narada.android.platform.FolderPermissions
import com.narada.android.platform.PrivateFiles
import com.narada.android.platform.PrivateNotifications
import com.narada.domain.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext

@HiltWorker
class WorkspaceHealthWorker @AssistedInject constructor(
    @Assisted context: Context, @Assisted parameters: WorkerParameters,
    private val store: MetadataStore, private val settings: SettingsStore,
    private val files: PrivateFiles, private val folders: FolderPermissions,
    private val notifications: PrivateNotifications,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val operationId = inputData.getString(OPERATION_ID) ?: return Result.failure()
        if (operationId != id.toString()) return Result.failure()
        return try {
            if (runAttemptCount >= MAX_ATTEMPTS) {
                store.finishJob(operationId, LocalJobState.FAILED, FailureCode.SCHEDULER_UNAVAILABLE)
                return Result.failure()
            }
            if (!store.startJob(operationId)) return Result.success() // Old/terminal worker: no writes.
            withTimeout(30_000) {
                check(store.checkDatabase())
                settings.current() // Fail visibly if preferences cannot be read.
                check(files.probe()) // Fixed-size, app-private bytes; never a selected document.
                folders.reconcile() // Permission metadata only, no folder-content scan.
                coroutineContext.ensureActive()
                val committed = store.finishJob(operationId, LocalJobState.COMPLETED)
                if (committed) runCatching {
                    if (settings.current().notificationsEnabled) notifications.postGeneric()
                }
            }
            Result.success()
        } catch (timedOut: TimeoutCancellationException) {
            runCatching { store.finishJob(operationId, LocalJobState.FAILED, FailureCode.SCHEDULER_UNAVAILABLE) }
            Result.failure()
        } catch (cancelled: CancellationException) {
            // WorkManager owns system-stop rescheduling; leave the durable active ID intact.
            throw cancelled
        } catch (failure: Exception) {
            runCatching { store.finishJob(operationId, LocalJobState.FAILED, FailureCode.STORAGE_UNAVAILABLE) }
            Result.failure()
        }
    }
    companion object { const val OPERATION_ID = "operation_id"; const val MAX_ATTEMPTS = 3 }
}
