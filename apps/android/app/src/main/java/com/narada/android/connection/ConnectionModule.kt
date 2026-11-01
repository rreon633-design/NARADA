package com.narada.android.connection

import android.content.Context
import android.net.ssl.SSLSockets
import com.narada.android.data.MetadataDatabase
import com.narada.connection.PeerConnector
import com.narada.connection.TrustRepository
import com.narada.connection.TlsConnector
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module @InstallIn(SingletonComponent::class)
object ConnectionModule {
    @Provides fun networkPolicy(network: LocalNetwork): ConnectionNetworkPolicy = network
    @Provides fun discoveryControl(discovery: LocalDiscovery): DiscoveryControl = discovery
    @Provides @Singleton fun repository(@ApplicationContext context: Context, database: MetadataDatabase, keys: AndroidConnectionKeys): TrustRepository =
        ProtectedConnectionsRepository(RoomEnrollment(database), keys, AtomicProtectedBlob(File(context.noBackupFilesDir, "connections-v1")))
    @Provides @Singleton fun connector(network: LocalNetwork): PeerConnector = TlsConnector(network) { socket ->
        if (SSLSockets.isSupportedSocket(socket)) SSLSockets.setUseSessionTickets(socket, false)
    }
}
