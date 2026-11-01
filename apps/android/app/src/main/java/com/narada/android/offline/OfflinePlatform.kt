package com.narada.android.offline

import com.narada.android.connection.ConnectionsCoordinator
import com.narada.android.transfers.isProtectedStorageFailure
import com.narada.connection.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

@Singleton class RemoteSyncGateway @Inject constructor(private val repository: TrustRepository, private val connections: ConnectionsCoordinator) : SyncGateway {
    override suspend fun page(ticket: SyncTicket): SyncResult = try {
        connections.withSync(ticket.peerId) { pairing, peer, session, guard -> SyncClient(repository, pairing).page(peer, session, ticket, guard) }
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { if (isProtectedStorageFailure(e)) connections.fileStorageFailure(e); throw e }
    override fun cancel() = connections.cancelSync()
}
@Module @InstallIn(SingletonComponent::class) object OfflineModule {
    @Provides @Singleton fun gateway(remote: RemoteSyncGateway, admission: ContentAdmission): SyncGateway = PacedSyncGateway(remote, admission)
}
