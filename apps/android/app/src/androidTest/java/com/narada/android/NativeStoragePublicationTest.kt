package com.narada.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.narada.connection.NativeStorageBridge
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Same minified app/JNI bytes, enforcing Android sandbox. Only UUID-owned synthetic fixtures. */
@RunWith(AndroidJUnit4::class)
class NativeStoragePublicationTest {
    @Test fun actualAtomicPublicationPreservesCollisionsAndRejectsSymlinks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = context.noBackupFilesDir.toPath().resolve("storage-native-${UUID.randomUUID()}")
        Files.createDirectory(root, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val first = "synthetic sealed first fragment".toByteArray()
        val second = "synthetic sealed conflicting fragment".toByteArray()
        fun stage(name: String, bytes: ByteArray): Path = Files.createFile(root.resolve(name),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))).also { Files.write(it, bytes, WRITE) }
        try {
            val pending = stage("chunk-0.pending", first)
            assertEquals("Native publication status; numeric errno only, no paths", 0,
                NativeStorageBridge.moveNoReplace(root.toString(), "chunk-0.pending", "chunk-0.enc"))
            assertFalse(Files.exists(pending, NOFOLLOW_LINKS))
            assertArrayEquals(first, Files.readAllBytes(root.resolve("chunk-0.enc")))
            stage("chunk-0.pending", second)
            assertEquals(1, NativeStorageBridge.moveNoReplace(root.toString(), "chunk-0.pending", "chunk-0.enc"))
            assertArrayEquals(first, Files.readAllBytes(root.resolve("chunk-0.enc")))
            assertArrayEquals(second, Files.readAllBytes(pending))
            stage("chunk-1.pending", second)
            Files.createSymbolicLink(root.resolve("chunk-1.enc"), root.resolve("chunk-0.enc").fileName)
            assertEquals(1, NativeStorageBridge.moveNoReplace(root.toString(), "chunk-1.pending", "chunk-1.enc"))
            assertTrue(Files.isSymbolicLink(root.resolve("chunk-1.enc")))
            assertArrayEquals(second, Files.readAllBytes(root.resolve("chunk-1.pending")))
            Files.createSymbolicLink(root.resolve("chunk-2.pending"), root.resolve("chunk-0.enc").fileName)
            assertTrue(NativeStorageBridge.moveNoReplace(root.toString(), "chunk-2.pending", "chunk-2.enc") != 0)
            assertTrue(Files.isSymbolicLink(root.resolve("chunk-2.pending")))
            assertFalse(Files.exists(root.resolve("chunk-2.enc"), NOFOLLOW_LINKS))
            assertArrayEquals(first, Files.readAllBytes(root.resolve("chunk-0.enc")))
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
        }
    }
}
