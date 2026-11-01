package com.narada.android

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.narada.android.di.ApplicationScope
import com.narada.android.platform.PrivateNotifications
import com.narada.android.work.WorkCoordinator
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@HiltAndroidApp
class NaradaApplication : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var notifications: PrivateNotifications
    @Inject lateinit var coordinator: Lazy<WorkCoordinator>
    @Inject @ApplicationScope lateinit var applicationScope: CoroutineScope
    override val workManagerConfiguration: Configuration get() = Configuration.Builder()
        .setWorkerFactory(workerFactory).setMinimumLoggingLevel(android.util.Log.ERROR).build()
    override fun onCreate() {
        super.onCreate()
        notifications.ensureChannel()
        // Initialization never creates a new diagnostic job, starts sockets or prompts for permission.
        applicationScope.launch { runCatching { coordinator.get().reconcile() } }
    }
}
