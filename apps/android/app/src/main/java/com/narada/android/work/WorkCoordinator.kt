package com.narada.android.work

import com.narada.android.data.MetadataStore
import com.narada.domain.*
import com.narada.android.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class WorkCoordinator @Inject constructor(private val store: MetadataStore, private val scheduler: WorkScheduler, @param:ApplicationScope private val applicationScope: CoroutineScope) {
    private val mutex = Mutex()
    suspend fun request(): LocalJob = applicationScope.async { mutex.withLock {
        val job = store.requestJob() // Commit the stable operation ID BEFORE enqueueing.
        ensureScheduled(job)
        store.currentJob() ?: job
    } }.await()
    suspend fun reconcile() = mutex.withLock {
        store.currentJob()?.takeIf { it.state.active }?.let { ensureScheduled(it) }
    }
    private suspend fun ensureScheduled(job: LocalJob) {
        try {
            when (scheduler.state(job.operationId)) {
                ScheduledState.MISSING -> scheduler.enqueue(job.operationId)
                ScheduledState.ACTIVE -> Unit
                ScheduledState.CANCELLED -> store.finishJob(job.operationId, LocalJobState.CANCELLED)
                // A scheduler result is not proof that our durable local result exists.
                ScheduledState.SUCCEEDED, ScheduledState.FAILED -> store.finishJob(job.operationId, LocalJobState.FAILED, FailureCode.SCHEDULER_UNAVAILABLE)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            store.finishJob(job.operationId, LocalJobState.FAILED, FailureCode.SCHEDULER_UNAVAILABLE)
            throw FoundationFailure(FailureCode.SCHEDULER_UNAVAILABLE)
        }
    }
    suspend fun cancel(): Boolean = applicationScope.async { mutex.withLock {
        val job = store.currentJob()?.takeIf { it.state.active } ?: return@withLock false
        // Terminal local state wins over a late worker commit, even if Android's stop is delayed.
        val changed = store.finishJob(job.operationId, LocalJobState.CANCELLED)
        scheduler.cancel(job.operationId)
        changed
    } }.await()
}
