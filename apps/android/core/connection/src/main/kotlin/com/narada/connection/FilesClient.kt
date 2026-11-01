package com.narada.connection

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.UUID

/** No owned socket, listener, raw path, content URI, disk cache or automatic mutation retry. */
class FilesClient(private val repository: TrustRepository, private val pairing: PairingEngine, private val now: () -> Long = System::currentTimeMillis) {
    private suspend fun fence(captured: TrustedPeer, guard: () -> Unit): TrustedPeer {
        currentCoroutineContext().ensureActive(); guard()
        val document = repository.read() ?: fail("IDENTITY_NOT_READY")
        demand(document.enabled, "NETWORK_DISABLED")
        val current = document.peer(captured.id)
        demand(current.status == TrustStatus.ACTIVE && !current.blocked && current.failures < 6 &&
            current.attemptId == captured.attemptId && current.localEpoch == captured.localEpoch && current.fingerprint == captured.fingerprint, "PAIRING_STATE_CHANGED")
        demand(current.remoteRevision == captured.remoteRevision && current.remoteEpoch == captured.remoteEpoch && current.remotePermissions == captured.remotePermissions && current.remoteOperations == captured.remoteOperations, "FILE_ACCESS_CHANGED")
        return current
    }
    private fun authorize(peer: TrustedPeer, rootId: String?, vararg capabilities: String) {
        demand("files.v1" in peer.remoteOperations, "FILES_UNSUPPORTED")
        if (rootId != null) {
            val root = peer.remotePermissions.roots.singleOrNull { it.root_id == rootId } ?: fail("ACCESS_DENIED")
            demand(root.capabilities.containsAll(listOf("browse") + capabilities), "ACCESS_DENIED")
        }
    }
    private suspend fun before(peer: TrustedPeer, session: PeerSession, guard: () -> Unit): TrustedPeer {
        fence(peer, guard)
        val fresh = pairing.health(peer, session)
        fence(fresh, guard)
        return fresh
    }
    private suspend fun after(before: TrustedPeer, session: PeerSession, guard: () -> Unit): TrustedPeer {
        fence(before, guard)
        val after = pairing.health(before, session)
        fence(before, guard)
        demand(after.remoteRevision == before.remoteRevision && after.remoteEpoch == before.remoteEpoch &&
            after.remotePermissions == before.remotePermissions && "files.v1" in after.remoteOperations, "FILE_ACCESS_CHANGED")
        return after
    }
    suspend fun query(peer: TrustedPeer, session: PeerSession, query: FileQuery, guard: () -> Unit = {}): FileResult {
        val payload = query.payload() // Reject hostile IDs/versions before issuing even health traffic.
        val before = before(peer, session, guard)
        authorize(before, query.rootId, *when (query) { is FileQuery.Preview -> arrayOf("read"); is FileQuery.Trash -> arrayOf("trash"); else -> emptyArray() })
        fence(before, guard)
        val wire = FileWire(Hello(), action = FileWireAction("query", payload))
        val result = when (query) {
            FileQuery.Roots -> FileResult.Roots(session.post<FileWire, RootsReply>(FileRules.PATH, wire).also { it.validate(before.remotePermissions) })
            is FileQuery.Browse, is FileQuery.Search -> FileResult.Page(session.post<FileWire, PageReply>(FileRules.PATH, wire).also { it.validate(query, before.remotePermissions) })
            is FileQuery.Details -> FileResult.Details(session.post<FileWire, RemoteEntry>(FileRules.PATH, wire).also { it.validate(); demand(it.object_id == query.objectId, "INVALID_FILE_RESPONSE") })
            is FileQuery.Preview -> FileResult.Preview(session.post<FileWire, PreviewReply>(FileRules.PATH, wire).pixels(query))
            is FileQuery.Trash -> FileResult.Trash(session.post<FileWire, TrashReply>(FileRules.PATH, wire).also { it.validate(query, before.remotePermissions) })
        }
        after(before, session, guard) // Do not publish a response across an observed grant/session change.
        return result
    }
    /** Explicit confirmation is required at BOTH UI and domain boundary. Persist intent before the first effectful POST. */
    suspend fun mutate(peer: TrustedPeer, session: PeerSession, rootId: String, objectId: String, version: String, action: FileAction, confirmed: Boolean, guard: () -> Unit = {}): FileOperation {
        demand(confirmed, "LOCAL_CONFIRMATION_REQUIRED")
        val input = FileMutationInput(rootId, UUID.randomUUID().toString(), objectId, version, action)
        input.validate()
        val before = before(peer, session, guard); authorizeMutation(before, input)
        fence(before, guard)
        val operation = FileOperation(peer.id, peer.attemptId, input, now())
        repository.mutate { doc ->
            owned(doc, before)
            demand(doc.fileOperations.none { it.peerId == peer.id && it.state == FileOperationState.UNCONFIRMED }, "FILE_OPERATION_UNRESOLVED")
            // Only terminal local records can leave the bounded receipt window. Unknown outcomes are never evicted.
            val records = if (doc.fileOperations.size < 32) doc.fileOperations else {
                val removable = doc.fileOperations.indexOfFirst { it.state != FileOperationState.UNCONFIRMED }
                demand(removable >= 0, "FILE_OPERATION_LIMIT"); doc.fileOperations.filterIndexed { index, _ -> index != removable }
            }
            doc.copy(version = 3, fileOperations = records + operation)
        }
        return submit(before, session, operation, guard)
    }
    suspend fun retry(peer: TrustedPeer, session: PeerSession, operationId: String, confirmed: Boolean, guard: () -> Unit = {}): FileOperation {
        demand(confirmed, "LOCAL_CONFIRMATION_REQUIRED"); FileRules.id(operationId)
        val before = before(peer, session, guard)
        val doc = repository.read() ?: fail("IDENTITY_NOT_READY")
        val operation = doc.fileOperations.singleOrNull { it.request.operationId == operationId } ?: fail("FILE_OPERATION_MISSING")
        demand(operation.state == FileOperationState.UNCONFIRMED && operation.peerId == peer.id && operation.pairingAttempt == peer.attemptId, "FILE_OPERATION_SCOPE_CHANGED")
        authorizeMutation(before, operation.request)
        // The same sealed operation ID + exact arguments. Never rebase onto a new object/version/name.
        return submit(before, session, operation, guard)
    }
    private fun authorizeMutation(peer: TrustedPeer, input: FileMutationInput) {
        authorize(peer, input.rootId, *when (input.action.kind) { "rename" -> arrayOf("modify"); "restore" -> arrayOf("trash", "create"); else -> arrayOf("trash") })
    }
    private fun owned(doc: TrustDocument, peer: TrustedPeer) {
        val current = doc.peer(peer.id)
        demand(doc.enabled && current.status == TrustStatus.ACTIVE && !current.blocked && current.attemptId == peer.attemptId &&
            current.localEpoch == peer.localEpoch && current.remoteRevision == peer.remoteRevision && current.remoteEpoch == peer.remoteEpoch && current.remotePermissions == peer.remotePermissions, "PAIRING_STATE_CHANGED")
    }
    private suspend fun submit(peer: TrustedPeer, session: PeerSession, operation: FileOperation, guard: () -> Unit): FileOperation {
        fence(peer, guard)
        val receipt: FileReceipt = session.post(FileRules.PATH, FileWire(Hello(), action = FileWireAction("mutate", operation.request.payload())))
        receipt.validate(operation.request)
        after(peer, session, guard)
        val confirmed = operation.copy(state = FileOperationState.COMMITTED, receipt = receipt)
        repository.mutate { doc ->
            owned(doc, peer)
            demand(doc.fileOperations.singleOrNull { it.request.operationId == operation.request.operationId } == operation, "FILE_OPERATION_SCOPE_CHANGED")
            doc.copy(version = 3, fileOperations = doc.fileOperations.map { if (it.request.operationId == operation.request.operationId) confirmed else it })
        }
        // A failed write, timeout, cancellation, lost reply, policy change or malformed receipt leaves UNCONFIRMED.
        fence(peer, guard)
        return confirmed
    }
    suspend fun dismiss(operationId: String, acknowledged: Boolean) {
        demand(acknowledged, "LOCAL_CONFIRMATION_REQUIRED"); FileRules.id(operationId)
        repository.mutate { doc ->
            val operation = doc.fileOperations.singleOrNull { it.request.operationId == operationId } ?: fail("FILE_OPERATION_MISSING")
            demand(operation.state == FileOperationState.UNCONFIRMED, "FILE_OPERATION_SCOPE_CHANGED")
            doc.copy(version = 3, fileOperations = doc.fileOperations.map { if (it.request.operationId == operationId) it.copy(state = FileOperationState.DISMISSED_UNCONFIRMED) else it })
        }
        // Dismissal is LOCAL bookkeeping, not cancellation, remote failure, recovery, or a success receipt.
    }
}

/** Android provides the single connection-owner lease; JVM tests exercise this controller without Android networking. */
interface FilesGateway {
    suspend fun query(query: FileQuery): FileResult
    suspend fun mutate(root: String, objectId: String, version: String, action: FileAction, confirmed: Boolean): FileOperation
    suspend fun retry(operationId: String, confirmed: Boolean): FileOperation
    suspend fun dismiss(operationId: String, acknowledged: Boolean)
    fun cancel()
}
