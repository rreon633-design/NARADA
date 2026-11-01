package com.narada.android.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.narada.android.connection.ConnectionsCoordinator
import com.narada.connection.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Singleton
class RemoteFilesGateway @Inject constructor(private val repository: TrustRepository, private val connections: ConnectionsCoordinator) : FilesGateway {
    private suspend fun <T> lease(action: suspend (FilesClient, TrustedPeer, PeerSession, () -> Unit) -> T): T = try {
        connections.withFiles { engine, peer, session, guard -> action(FilesClient(repository, engine), peer, session, guard) }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { localFailure(failure); throw failure }
    override suspend fun query(query: FileQuery) = lease { client, peer, session, guard -> client.query(peer, session, query, guard) }
    override suspend fun mutate(root: String, objectId: String, version: String, action: FileAction, confirmed: Boolean) =
        lease { client, peer, session, guard -> client.mutate(peer, session, root, objectId, version, action, confirmed, guard) }
    override suspend fun retry(operationId: String, confirmed: Boolean) = lease { client, peer, session, guard -> client.retry(peer, session, operationId, confirmed, guard) }
    override suspend fun dismiss(operationId: String, acknowledged: Boolean) {
        try { connections.dismissFileOperation(operationId, acknowledged) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { localFailure(failure); throw failure }
    }
    override fun cancel() = connections.cancelFiles()
    private fun localFailure(failure: Exception) {
        if (failure !is ConnectionFailure || failure.code in setOf("IDENTITY_KEY_MISSING", "IDENTITY_CHANGED", "IDENTITY_ANCHOR_MISSING", "TRUST_STORE_MISSING", "TRUST_STORE_INVALID", "KEYSTORE_UNAVAILABLE", "STORAGE_UNAVAILABLE")) connections.fileStorageFailure(failure)
    }
}

@HiltViewModel
class FilesViewModel @Inject constructor(gateway: RemoteFilesGateway, connections: ConnectionsCoordinator) : ViewModel() {
    val browser = FileBrowser(gateway, viewModelScope)
    val state = browser.state
    init {
        viewModelScope.launch {
            connections.state.collect { current ->
                val doc = current.document
                val peer = doc?.peers?.find { it.id == doc.preferredPeer }
                val authority = if (doc?.enabled == true && current.transportAllowed && current.foreground && current.networkAvailable &&
                    peer?.status == TrustStatus.ACTIVE && !peer.blocked && peer.failures < 6 && "files.v1" in peer.remoteOperations)
                    FileAuthority(peer.id, peer.attemptId, peer.localEpoch, peer.remoteRevision, peer.remoteEpoch, current.accessEpoch) else null
                browser.authorityChanged(authority, doc?.fileOperations.orEmpty(), current.foreground)
            }
        }
    }
    override fun onCleared() { browser.cancel(); super.onCleared() }
}

fun fileMessage(code: String): String = when (code) {
    "FILES_OFFLINE", "NETWORK_DISABLED", "LOCAL_NETWORK_UNAVAILABLE", "PEER_NOT_FOUND" -> "Connect an approved desktop in Devices. Keep NARADA visible on private Wi-Fi or Ethernet, then refresh. No offline copy is kept here."
    "FILES_UNSUPPORTED" -> "This desktop did not advertise the supported file protocol. No owner API or alternate endpoint was tried."
    "ACCESS_DENIED", "PERMISSION_DENIED", "FILE_ACCESS_CHANGED", "DEVICE_REVOKED", "PAIRING_STATE_CHANGED" -> "Current access could not be confirmed. The view was discarded. Review desktop grants and device trust, then refresh."
    "CURSOR_STALE" -> "The page expired or the index changed. Refresh to restart from the first page; different snapshots are not combined."
    "STALE_VERSION", "FILE_SELECTION_STALE", "OBJECT_NOT_FOUND", "OBJECT_UNAVAILABLE" -> "This object moved, changed or became unavailable. Refresh before choosing a new action. A saved mutation outcome may still need review."
    "PREVIEW_UNSUPPORTED" -> "Only permitted PNG/JPEG images can produce a bounded pixel preview. PDFs, SVG, HTML and external viewers are not opened."
    "PREVIEW_LIMIT", "INVALID_PREVIEW" -> "The preview is malformed or exceeds the desktop or phone limits. No image was opened or saved."
    "INVALID_FILE_SEARCH" -> "Enter 1–128 UTF-8 bytes without control characters. Search is submitted explicitly, within this granted root."
    "INVALID_FILE_NAME", "INVALID_NAME", "UNSAFE_NAME", "EXCLUDED_NAME" -> "Use a normalized name of at most 180 UTF-8 bytes, without separators, hidden/reserved names or a trailing dot/space."
    "NAME_COLLISION" -> "That name is already in use. Nothing is overwritten. Inspect the desktop and review any unconfirmed saved request before a different action."
    "DIRECTORY_NOT_EMPTY", "DIRECTORY_UNSUPPORTED" -> "This protocol only mutates files and empty directories. It does not recursively move or delete folders."
    "INDEX_UNAVAILABLE", "ROOT_UNAVAILABLE", "ROOT_NOT_FOUND" -> "The desktop root/index is unavailable. Ask its owner to check the path, permissions, exclusions and index. Phone paths and SAF grants cannot repair a remote root."
    "TRASH_QUOTA" -> "Managed trash is full. Restore retained objects on the desktop; the phone never permanently deletes or automatically purges them."
    "TRASH_UNAVAILABLE", "TRASH_CHANGED" -> "This retained object is no longer safely available. Its desktop record needs owner review."
    "FILE_OPERATION_UNRESOLVED", "OPERATION_INDETERMINATE", "OPERATION_NOT_APPLIED", "FILE_WORK_FAILED" -> "A desktop outcome is not confirmed. Inspect the desktop, then retry the same saved operation or explicitly dismiss local retry. Cancellation cannot undo a processed request."
    "FILE_OPERATION_SCOPE_CHANGED" -> "The saved operation belongs to another pairing or has already been resolved locally. It will not be replayed under replacement trust."
    "FILE_OPERATION_LIMIT", "OPERATION_HISTORY_LIMIT" -> "The protected operation history is full. Unknown outcomes were preserved, not silently discarded."
    "FILE_REMOTE_STORAGE", "FILE_IO" -> "The desktop could not safely access its storage. Phone trust was not reset. Review the desktop and any unconfirmed request."
    "FILE_BUSY", "RATE_LIMITED" -> "The desktop is busy. Retry explicitly after current work finishes; a mutation is never automatically reissued."
    "INVALID_FILE_RESPONSE", "INVALID_FILE_ID", "INVALID_FILE_VERSION", "INVALID_FILE_ACTION", "INVALID_FILE_RECEIPT", "INVALID_HANDSHAKE", "DUPLICATE_FIELD", "BODY_TOO_LARGE", "IDEMPOTENCY_CONFLICT" -> "The response or saved request did not match the strict file contract. No speculative success or alternate path was used."
    else -> com.narada.android.connection.connectionMessage(code)
}
