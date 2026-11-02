package com.narada.android

import android.app.Application
import android.os.StatFs
import com.narada.android.attachments.AttachmentsModule
import com.narada.android.connection.AndroidConnectionKeys
import com.narada.android.transfers.TransferModule
import com.narada.connection.ConnectionFailure
import com.narada.connection.DownloadRecord
import com.narada.connection.TransferChunks
import com.narada.connection.TransferManifest
import com.narada.connection.TransferRules
import com.narada.connection.TransferSource
import com.narada.connection.sha256
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowStatFs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TransferStorageSpaceTest {
    private fun record(): DownloadRecord {
        val bytes = byteArrayOf(1, 2, 3)
        val source = TransferSource(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "a".repeat(64))
        val hashes = listOf(sha256(bytes))
        val manifest = TransferManifest(1, "0".repeat(64), source.expected_version, "fixture.bin", "3", TransferRules.CHUNK, 1, sha256(bytes))
        return DownloadRecord(UUID.randomUUID().toString(), "b".repeat(64), UUID.randomUUID().toString(), source, manifest.name, 3, 1,
            manifest = manifest.copy(manifest_id = manifest.digest(hashes)), hashes = hashes)
    }

    private fun stores(app: Application): List<Pair<String, TransferChunks>> = listOf(
        "received-v1" to TransferModule.storage(app, AndroidConnectionKeys()),
        "attachments-v1" to AttachmentsModule.chunks(app, AndroidConnectionKeys())
    )

    @Test fun androidAvailableBlocksAreUsedInsteadOfReservedFreeBlocks() {
        val app = RuntimeEnvironment.getApplication()
        val owner = UUID.randomUUID().toString()
        ShadowStatFs.registerStats(app.noBackupFilesDir.absolutePath, 1_000_000, 500_000, 0)
        stores(app).forEach { (root, storage) ->
            val error = assertThrows(ConnectionFailure::class.java) { storage.reserve(owner, record()) }
            assertEquals("LOCAL_STORAGE_FULL", error.code)
            assertFalse(File(app.noBackupFilesDir, "$root/$owner").exists())
        }
    }

    @Test fun diskHeadroomAndEncryptedFragmentOverheadAreNotReduced() {
        val app = RuntimeEnvironment.getApplication()
        val owner = UUID.randomUUID().toString()
        ShadowStatFs.registerStats(app.noBackupFilesDir.absolutePath, 1_000_000, 500_000, 4096)
        assertEquals(16L * 1024 * 1024, StatFs(app.noBackupFilesDir.absolutePath).availableBytes)
        stores(app).forEach { (root, storage) ->
            val error = assertThrows(ConnectionFailure::class.java) { storage.reserve(owner, record()) }
            assertEquals("LOCAL_STORAGE_FULL", error.code)
            assertFalse(File(app.noBackupFilesDir, "$root/$owner").exists())
        }
    }

    @Test fun availableSpaceIsProbedAtReservationNotCachedAtConstruction() {
        val app = RuntimeEnvironment.getApplication()
        val owner = UUID.randomUUID().toString()
        ShadowStatFs.registerStats(app.noBackupFilesDir.absolutePath, 1_000_000, 500_000, 500_000)
        val created = stores(app)
        ShadowStatFs.registerStats(app.noBackupFilesDir.absolutePath, 1_000_000, 500_000, 0)
        created.forEach { (root, storage) ->
            val error = assertThrows(ConnectionFailure::class.java) { storage.reserve(owner, record()) }
            assertEquals("LOCAL_STORAGE_FULL", error.code)
            assertFalse(File(app.noBackupFilesDir, "$root/$owner").exists())
        }
    }

    @Test
    @Config(shadows = [DeniedStatFs::class])
    fun deniedSpaceProbeFailsClosedWithoutInventingCapacity() {
        val app = RuntimeEnvironment.getApplication()
        val owner = UUID.randomUUID().toString()
        stores(app).forEach { (root, storage) ->
            val error = assertThrows(ConnectionFailure::class.java) { storage.reserve(owner, record()) }
            assertEquals("LOCAL_STORAGE_UNAVAILABLE", error.code)
            assertFalse(File(app.noBackupFilesDir, "$root/$owner").exists())
        }
    }
}

@Implements(StatFs::class)
class DeniedStatFs {
    @Implementation
    fun __constructor__(path: String) {
        require(path.isNotBlank())
        throw SecurityException("Synthetic StatFs denial")
    }
}
