package com.narada.android.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import com.narada.android.data.MetadataDatabase
import com.narada.android.platform.AndroidReadPermissions
import com.narada.android.platform.PersistedReadPermissions
import com.narada.android.work.AndroidWorkScheduler
import com.narada.android.work.WorkScheduler
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class ApplicationScope
@Module @InstallIn(SingletonComponent::class)
object FoundationModule {
    @Provides @Singleton @ApplicationScope fun scope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Provides @Singleton fun database(@ApplicationContext context: Context): MetadataDatabase = MetadataDatabase.create(context)
    @Provides @Singleton fun settings(@ApplicationContext context: Context, @ApplicationScope scope: CoroutineScope): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope, produceFile = { context.preferencesDataStoreFile("narada-settings") })
}
@Module @InstallIn(SingletonComponent::class)
abstract class PlatformBindings {
    @Binds @Singleton abstract fun permissions(implementation: AndroidReadPermissions): PersistedReadPermissions
    @Binds @Singleton abstract fun scheduler(implementation: AndroidWorkScheduler): WorkScheduler
}
