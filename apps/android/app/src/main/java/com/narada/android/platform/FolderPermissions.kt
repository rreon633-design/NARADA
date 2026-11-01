package com.narada.android.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import com.narada.android.data.MetadataStore
import com.narada.domain.*
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface PersistedReadPermissions {
    fun readableUris(): Set<String>
    fun take(uri: Uri, flags: Int)
    fun release(uri: Uri, flags: Int)
}

@Singleton
class AndroidReadPermissions @Inject constructor(@ApplicationContext context: Context) : PersistedReadPermissions {
    private val resolver = context.contentResolver
    override fun readableUris(): Set<String> = resolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri.toString() }.toSet()
    override fun take(uri: Uri, flags: Int) = resolver.takePersistableUriPermission(uri, flags)
    override fun release(uri: Uri, flags: Int) = resolver.releasePersistableUriPermission(uri, flags)
}

@Singleton
class FolderPermissions @Inject constructor(private val permissions: PersistedReadPermissions, private val metadata: MetadataStore) {
    private val mutex = Mutex()
    suspend fun retain(uri: Uri, offeredFlags: Int) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (uri.scheme != "content" || uri.authority.isNullOrBlank() || uri.toString().length > 4096 || !DocumentsContract.isTreeUri(uri)) {
                throw FoundationFailure(FailureCode.PERMISSION_UNAVAILABLE)
            }
            val flags = GrantPolicy.readOnlyPersistenceFlags(offeredFlags)
            val rows = metadata.grants()
            if (rows.none { it.uri == uri.toString() } && rows.size >= FoundationLimits.MAX_GRANTS) throw FoundationFailure(FailureCode.GRANT_LIMIT)
            metadata.reserveGrant(uri.toString()) // Persist intent before acquiring OS authority.
            val alreadyHeld = uri.toString() in permissions.readableUris()
            try {
                permissions.take(uri, flags)
                if (uri.toString() !in permissions.readableUris()) throw FoundationFailure(FailureCode.GRANT_NOT_PERSISTABLE)
                metadata.retainGrant(uri.toString(), flags)
            } catch (failure: Exception) {
                // Roll back only access acquired by THIS call, never an existing owner's grant.
                if (!alreadyHeld) runCatching { permissions.release(uri, flags) }
                throw failure
            }
        }
    }
    suspend fun forget(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val row = metadata.grant(id) ?: return@withLock
            if (row.uri in permissions.readableUris()) permissions.release(row.uri.toUri(), row.flags)
            metadata.forgetGrant(id)
        }
    }
    suspend fun reconcile() = withContext(Dispatchers.IO) { mutex.withLock { metadata.reconcileGrants(permissions.readableUris()) } }
    companion object {
        fun pickerIntent() = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
        )
    }
}
