package com.narada.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.narada.android.attachments.AttachmentsModule
import com.narada.android.connection.AndroidConnectionKeys
import com.narada.android.connection.AtomicProtectedBlob
import com.narada.android.transfers.TransferModule
import com.narada.connection.*
import java.io.File
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Compiled qualification cases, NOT executed in the host campaign. Use a disposable device profile.
 * Test-owned UUID aliases/directories only. Passing these later still does not prove power-loss,
 * rollback resistance, cloud SAF behavior or Windows LAN interoperability. */
@RunWith(AndroidJUnit4::class)
class NativeTransferBoundaryTest {
    private val bytes="NARADA disposable native transfer fixture".toByteArray()
    private fun record():DownloadRecord {
        val source=TransferSource(UUID.randomUUID().toString(),UUID.randomUUID().toString(),"a".repeat(64));val hashes=listOf(sha256(bytes))
        val m=TransferManifest(1,"0".repeat(64),source.expected_version,"disposable.bin",bytes.size.toString(),TransferRules.CHUNK,1,sha256(bytes))
        return DownloadRecord(UUID.randomUUID().toString(),"b".repeat(64),UUID.randomUUID().toString(),source,m.name,bytes.size.toLong(),1,manifest=m.copy(manifest_id=m.digest(hashes)),hashes=hashes)
    }
    private fun removeAliases(id:String){KeyStore.getInstance("AndroidKeyStore").apply{load(null)}.let{it.deleteEntry("narada.connection.ec.$id");it.deleteEntry("narada.connection.seal.$id")}}
    @Test fun nativeNonexportableTransferKeyBindsEveryChunkContext(){val owner=UUID.randomUUID().toString();val keys=AndroidConnectionKeys();try{keys.create(owner);val r=record();val context=ChunkContext(owner,r.id,r.manifest!!.manifest_id,0,bytes.size);val key=keys.transferKey(owner);assertNull(key.encoded);val sealed=TransferCipher.seal(context,key,bytes);assertArrayEquals(bytes,TransferCipher.unseal(context,AndroidConnectionKeys().transferKey(owner),sealed));assertThrows(ConnectionFailure::class.java){TransferCipher.unseal(context.copy(transferId=UUID.randomUUID().toString()),key,sealed)};sealed[sealed.lastIndex]=(sealed.last().toInt() xor 1).toByte();assertThrows(ConnectionFailure::class.java){TransferCipher.unseal(context,key,sealed)}}finally{removeAliases(owner)}}
    @Test fun nativeNoBackupFragmentsReopenAndNeverOverwriteCorruption() {
        val owner = UUID.randomUUID().toString()
        val keys = AndroidConnectionKeys()
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val r = record()
        val factories = listOf<Pair<String, (AndroidConnectionKeys) -> TransferChunks>>(
            "received-v1" to { keyset -> TransferModule.storage(app, keyset) },
            "attachments-v1" to { keyset -> AttachmentsModule.chunks(app, keyset) }
        )
        try {
            keys.create(owner)
            for ((root, factory) in factories) {
                val owned = File(app.noBackupFilesDir, "$root/$owner")
                try {
                    val storage = factory(keys)
                    storage.reserve(owner, r)
                    storage.write(owner, r, 0, bytes)
                    assertArrayEquals(bytes, factory(AndroidConnectionKeys()).read(owner, r, 0))
                    val file = File(owned, "${r.id}/chunk-0.enc")
                    val corrupt = file.readBytes().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
                    file.writeBytes(corrupt)
                    assertThrows(ConnectionFailure::class.java) { storage.read(owner, r, 0) }
                    assertThrows(ConnectionFailure::class.java) { storage.write(owner, r, 0, bytes) }
                    assertArrayEquals(corrupt, file.readBytes())
                } finally {
                    if (owned.exists()) assertTrue("Test-owned private fragments must be removable", owned.deleteRecursively())
                }
            }
        } finally { removeAliases(owner) }
    }

    @Test fun nativeSealedPrivateReceiptAndUnknownExportReopenWithoutReplay(){val owner=UUID.randomUUID().toString();val keys=AndroidConnectionKeys();val app=InstrumentationRegistry.getInstrumentation().targetContext;val directory=File(app.noBackupFilesDir,"disposable-transfer-journal-$owner");try{keys.create(owner);val r=record();val m=r.manifest!!;val full=r.copy(state=DownloadState.VERIFIED,chunks=listOf(0),receipt=PrivateReceipt(m.manifest_id,m.sha256,m.bytes,2),exports=listOf(ExportRecord(UUID.randomUUID().toString(),"private-copy.bin",3,ExportState.UNCERTAIN,"content://disposable.provider/tree/root")));full.validate();val clear=Wire.encode(full,Wire.MAX_STORE);try{AtomicProtectedBlob(directory).write(keys.seal(owner,clear))}finally{clear.fill(0)};val sealed=AtomicProtectedBlob(directory).read();assertFalse(String(sealed).contains("disposable.provider"));val restored=AndroidConnectionKeys().unseal(owner,sealed);try{val parsed=Wire.decode<DownloadRecord>(restored,Wire.MAX_STORE);parsed.validate();assertEquals(full,parsed);assertEquals(ExportState.UNCERTAIN,parsed.exports.single().state);assertFalse(parsed.remoteFinished)}finally{restored.fill(0)}}finally{directory.deleteRecursively();removeAliases(owner)}}
}
