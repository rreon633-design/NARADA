package com.narada.android

import android.app.Application
import com.narada.android.data.MetadataDatabase
import com.narada.android.data.MetadataStore
import com.narada.android.work.*
import com.narada.domain.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WorkOwnershipTest {
    private lateinit var f: TestFixture
    @Before fun setup() { f=TestFixture() }
    @After fun cleanup() { f.close() }
    @Test fun concurrentUiRequestsEnqueueOneWorkId() = runBlocking {
        val ids=List(16) { async(Dispatchers.Default) { f.coordinator.request().operationId } }.awaitAll()
        assertEquals(1,ids.distinct().size);assertEquals(1,f.scheduler.enqueued.size)
    }
    @Test fun recreationDoesNotEnqueueAnotherActiveJob() = runBlocking {
        val first=f.coordinator.request()
        WorkCoordinator(f.store,f.scheduler,f.scope).reconcile()
        assertEquals(listOf(first.operationId),f.scheduler.enqueued)
    }
    @Test fun crashWindowBeforeEnqueueRecoversSameDurableId() = runBlocking {
        val committed=f.store.requestJob();f.database.close()
        MetadataDatabase.create(f.context,f.name).use { reopened ->
            WorkCoordinator(MetadataStore(reopened),f.scheduler,f.scope).reconcile()
            assertEquals(listOf(committed.operationId),f.scheduler.enqueued)
        }
    }
    @Test fun recreationWithoutAnActiveRequestDoesNotCreateWork() = runBlocking {
        f.coordinator.reconcile();assertTrue(f.scheduler.enqueued.isEmpty());assertNull(f.store.currentJob())
    }
    @Test fun failedEnqueueIsDurablyVisible() = runBlocking {
        f.scheduler.rejectEnqueue=true
        assertTrue(runCatching { f.coordinator.request() }.isFailure)
        assertEquals(LocalJobState.FAILED,f.store.currentJob()?.state)
        assertEquals(FailureCode.SCHEDULER_UNAVAILABLE,f.store.currentJob()?.errorCode)
    }
    @Test fun schedulerSuccessCannotFabricateADurableCheckResult() = runBlocking {
        val job=f.store.requestJob();f.scheduler.states[job.operationId]=ScheduledState.SUCCEEDED
        f.coordinator.reconcile();assertEquals(LocalJobState.FAILED,f.store.currentJob()?.state)
    }
    @Test fun cancelPersistsBeforeAndroidStopAndPreventsLateCommit() = runBlocking {
        val job=f.coordinator.request();f.store.startJob(job.operationId)
        assertTrue(f.coordinator.cancel());assertEquals(ScheduledState.CANCELLED,f.scheduler.states[job.operationId])
        assertFalse(f.store.finishJob(job.operationId,LocalJobState.COMPLETED))
        assertEquals(LocalJobState.CANCELLED,f.store.currentJob()?.state)
    }
    @Test fun cancellingAUiCallerDoesNotOwnAcceptedBackgroundCommand() = runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val slow=object:WorkScheduler {
            var enqueued=false
            override suspend fun state(id:String)=ScheduledState.MISSING
            override suspend fun enqueue(id:String) { entered.complete(Unit);release.await();enqueued=true }
            override suspend fun cancel(id:String) {}
        }
        val owner=WorkCoordinator(f.store,slow,f.scope)
        val ui=launch { owner.request() };entered.await();ui.cancelAndJoin();release.complete(Unit)
        withTimeout(5000) { while (!slow.enqueued) yield() }
        assertTrue(slow.enqueued);assertEquals(LocalJobState.QUEUED,f.store.currentJob()?.state)
    }
}
