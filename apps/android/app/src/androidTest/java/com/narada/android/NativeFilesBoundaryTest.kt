package com.narada.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.narada.android.connection.AndroidConnectionKeys
import com.narada.android.connection.AtomicProtectedBlob
import com.narada.android.ui.screens.decodeBoundedPreview
import com.narada.connection.*
import java.io.File
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Compiled here, NOT executed without an emulator/device. Random test aliases/directories only. */
@RunWith(AndroidJUnit4::class)
class NativeFilesBoundaryTest {
    @Test fun nativeJpegDecoderHonorsValidatedBoundsAndRejectsDimensionSubstitution() {
        val bytes=InstrumentationRegistry.getInstrumentation().context.assets.open("bounded-preview-fixture.jpg").use{it.readBytes()}
        val preview=PixelPreview(UUID.randomUUID().toString(),"a".repeat(64),20,14,bytes)
        val bitmap=decodeBoundedPreview(preview)
        try { assertEquals(20,bitmap.width);assertEquals(14,bitmap.height);assertTrue(bitmap.allocationByteCount<=256*256*4) } finally { bitmap.recycle() }
        assertThrows(ConnectionFailure::class.java){decodeBoundedPreview(PixelPreview(preview.objectId,preview.version,256,256,bytes))}
    }
    @Test fun nativeKeySealedUnknownIntentSurvivesAtomicFileReopenWithoutBecomingSuccess() {
        val id=UUID.randomUUID().toString();val keys=AndroidConnectionKeys()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(context.noBackupFilesDir,"disposable-file-journal-test-$id")
        try {
            keys.create(id)
            val input=FileMutationInput(UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString(),"a".repeat(64),FileAction("rename","DISPOSABLE-TEST-NAME.jpg"))
            val operation=FileOperation("b".repeat(64),UUID.randomUUID().toString(),input,System.currentTimeMillis())
            val raw=Wire.encode(operation,Wire.MAX_STORE)
            try { AtomicProtectedBlob(directory).write(keys.seal(id,raw)) } finally { raw.fill(0) }
            val sealed=AtomicProtectedBlob(directory).read();assertFalse(String(sealed).contains("DISPOSABLE-TEST-NAME"))
            val clear=keys.unseal(id,sealed)
            try { val restored=Wire.decode<FileOperation>(clear,Wire.MAX_STORE);restored.validate();assertEquals(operation,restored);assertEquals(FileOperationState.UNCONFIRMED,restored.state);assertNull(restored.receipt) }
            finally { clear.fill(0) }
        } finally {
            directory.deleteRecursively() // This test's isolated random directory, never application trust.bin.
            KeyStore.getInstance("AndroidKeyStore").apply{load(null)}.let{it.deleteEntry("narada.connection.ec.$id");it.deleteEntry("narada.connection.seal.$id")}
        }
    }
}
