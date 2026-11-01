package com.narada.android.chat

import com.narada.android.connection.ConnectionsCoordinator
import com.narada.android.data.SettingsStore
import com.narada.android.platform.PrivateNotifications
import com.narada.android.transfers.isProtectedStorageFailure
import com.narada.connection.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

@Singleton
class RemoteChatGateway @Inject constructor(private val repository: TrustRepository, private val connections: ConnectionsCoordinator) : ChatGateway {
    private suspend fun <T> lease(t: ChatTicket, action: suspend (ChatClient, TrustedPeer, PeerSession, () -> Unit) -> T): T = try {
        connections.withChat(t.peerId) { pairing, peer, session, guard -> action(ChatClient(repository, pairing), peer, session, guard) }
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { if (isProtectedStorageFailure(e)) connections.fileStorageFailure(e); throw e }
    override suspend fun bundle(ticket: ChatTicket) = lease(ticket) { c,p,s,g -> c.bundle(p,s,ticket,g) }
    override suspend fun push(ticket: ChatTicket, delivery: ChatDelivery) = lease(ticket) { c,p,s,g -> c.push(p,s,ticket,delivery,g) }
    override suspend fun pull(ticket: ChatTicket) = lease(ticket) { c,p,s,g -> c.pull(p,s,ticket,g) }
    override suspend fun ack(ticket: ChatTicket, delivery: ChatDelivery) = lease(ticket) { c,p,s,g -> c.ack(p,s,ticket,delivery,g) }
    override fun cancel() = connections.cancelChat()
}
@Module @InstallIn(SingletonComponent::class)
object ChatModule {
    @Provides @Singleton fun crypto(): OlmCryptography = VodozemacOlm()
    @Provides @Singleton fun gateway(remote: RemoteChatGateway, admission: ContentAdmission): ChatGateway = PacedChatGateway(remote, admission)
    @Provides @Singleton fun notice(settings: SettingsStore, notifications: PrivateNotifications) = ChatNotice {
        if (settings.current().notificationsEnabled) notifications.postChat()
    }
}
