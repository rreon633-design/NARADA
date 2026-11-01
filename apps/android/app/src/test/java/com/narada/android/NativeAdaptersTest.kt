package com.narada.android

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.work.*
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.testing.SynchronousExecutor
import com.narada.android.platform.*
import com.narada.android.work.*
import com.narada.domain.*
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NativeAdaptersTest {
    private lateinit var f: TestFixture
    @Before fun setup() { f=TestFixture() }
    @After fun cleanup() { f.close() }
    private fun worker(operationId:String, attempts:Int=0):WorkspaceHealthWorker {
        val factory=object:WorkerFactory() {
            override fun createWorker(appContext:Context,workerClassName:String,workerParameters:WorkerParameters):ListenableWorker =
                WorkspaceHealthWorker(appContext,workerParameters,f.store,f.settings,PrivateFiles(appContext),FolderPermissions(f.permissions,f.store),PrivateNotifications(appContext))
        }
        return TestListenableWorkerBuilder<WorkspaceHealthWorker>(f.context)
            .setId(UUID.fromString(operationId)).setInputData(workDataOf(WorkspaceHealthWorker.OPERATION_ID to operationId))
            .setRunAttemptCount(attempts).setWorkerFactory(factory).build()
    }
    @Test fun actualHealthWorkerCommitsARealLocalResult() = runBlocking {
        val job=f.store.requestJob();val result=worker(job.operationId).doWork()
        assertEquals(ListenableWorker.Result.success(),result);assertEquals(LocalJobState.COMPLETED,f.store.currentJob()?.state)
    }
    @Test fun terminalWorkDoesNotRepeatOrOverwriteCancellation() = runBlocking {
        val job=f.store.requestJob();f.store.finishJob(job.operationId,LocalJobState.CANCELLED)
        worker(job.operationId).doWork();assertEquals(LocalJobState.CANCELLED,f.store.currentJob()?.state)
    }
    @Test fun stoppedWorkHasABoundedAttemptBudget() = runBlocking {
        val job=f.store.requestJob();assertEquals(ListenableWorker.Result.failure(),worker(job.operationId,3).doWork())
        assertEquals(LocalJobState.FAILED,f.store.currentJob()?.state)
    }
    @Test fun corruptPreferencesFailTheWorkerWithoutReplacingThem() = runBlocking {
        val bytes=byteArrayOf(-1,-1,0);f.settingsFile.writeBytes(bytes)
        val job=f.store.requestJob();assertEquals(ListenableWorker.Result.failure(),worker(job.operationId).doWork())
        assertEquals(LocalJobState.FAILED,f.store.currentJob()?.state);assertArrayEquals(bytes,f.settingsFile.readBytes())
    }
    @Test fun notificationsHaveNoPrivateFieldsAndUseSecretVisibility() {
        val app=f.context.applicationContext as Application;shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val adapter=PrivateNotifications(f.context);adapter.ensureChannel();assertTrue(adapter.postGeneric())
        val manager=f.context.getSystemService(NotificationManager::class.java)
        val notification=shadowOf(manager).getNotification(PrivateNotifications.NOTIFICATION_ID)
        assertEquals(android.app.Notification.VISIBILITY_SECRET,notification.visibility)
        assertEquals("NARADA",notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString())
        assertEquals("Local workspace activity is ready to review.",notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
        assertTrue(notification.flags and android.app.Notification.FLAG_LOCAL_ONLY != 0)
    }
    @Test fun chatNotificationContainsOnlyGenericTextAndAnImmutableGenericRoute() {
        shadowOf(f.context.applicationContext as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val adapter=PrivateNotifications(f.context);adapter.ensureChannel();assertTrue(adapter.postChat())
        val notification=shadowOf(f.context.getSystemService(NotificationManager::class.java)).getNotification(PrivateNotifications.CHAT_NOTIFICATION_ID)
        assertEquals(android.app.Notification.VISIBILITY_SECRET,notification.visibility)
        assertEquals("Private message activity. Open NARADA to view.",notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
        assertEquals("NARADA",notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString())
        assertTrue(notification.flags and android.app.Notification.FLAG_LOCAL_ONLY != 0)
        val pending=shadowOf(notification.contentIntent)
        assertEquals(MainActivity.ACTION_PRIVATE_CHAT,pending.savedIntent.action)
        assertTrue(pending.savedIntent.extras==null || pending.savedIntent.extras!!.isEmpty)
        assertTrue(pending.isImmutable)
        assertTrue(notification.actions.isNullOrEmpty())
    }
    @Test fun deniedChatNotificationPermissionDoesNotPostOrPrompt() {
        shadowOf(f.context.applicationContext as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val adapter=PrivateNotifications(f.context);adapter.ensureChannel();assertFalse(adapter.postChat())
    }
    @Test fun deniedNotificationPermissionDoesNotPost() {
        shadowOf(f.context.applicationContext as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val adapter=PrivateNotifications(f.context);adapter.ensureChannel()
        assertFalse(adapter.postGeneric());assertFalse(adapter.runtimePermissionGranted())
    }
    @Test fun realWorkManagerAdapterKeepsOneStableRequest() = runBlocking {
        val release=CompletableDeferred<Unit>()
        val factory=object:WorkerFactory() {
            override fun createWorker(appContext:Context,workerClassName:String,workerParameters:WorkerParameters):ListenableWorker = object:CoroutineWorker(appContext,workerParameters) {
                override suspend fun doWork():Result { release.await();return Result.success() }
            }
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(f.context,Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(factory).build())
        val scheduler=AndroidWorkScheduler(f.context);val id=UUID.randomUUID().toString()
        try {
            scheduler.enqueue(id);scheduler.enqueue(id)
            val manager=WorkManager.getInstance(f.context)
            assertEquals(1,manager.getWorkInfosByTag(AndroidWorkScheduler.TAG).get(5,TimeUnit.SECONDS).size)
            assertEquals(ScheduledState.ACTIVE,scheduler.state(id))
            scheduler.cancel(id);assertEquals(ScheduledState.CANCELLED,scheduler.state(id))
        } finally { release.complete(Unit); WorkManagerTestInitHelper.closeWorkDatabase() }
    }
}
