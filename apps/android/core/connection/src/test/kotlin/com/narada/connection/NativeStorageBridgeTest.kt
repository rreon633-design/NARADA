package com.narada.connection

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import org.junit.Assert.*
import org.junit.Test

/** Actual host JNI/syscall tests, not Android/SELinux or power-loss qualification. */
class NativeStorageBridgeTest {
    private val cipher = "synthetic already-sealed fragment".toByteArray()
    private val other = "pre-existing immutable object".toByteArray()
    private val directoryMode = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
    private val fileMode = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
    private fun <T> owned(block: (Path) -> T): T {
        val root = Files.createTempDirectory("narada-storage-jni-", directoryMode)
        try { return block(root) } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
        }
    }
    private fun file(path: Path, bytes: ByteArray = cipher): Path = Files.createFile(path, fileMode).also { Files.write(it, bytes, WRITE) }
    private fun rejected(source: Path, destination: Path): ConnectionFailure = assertThrows(ConnectionFailure::class.java) { NativeStorageBridge.publish(source, destination) }

    @Test fun completeFragmentMovesWithoutLeavingStaging() = owned { root ->
        val source = file(root.resolve("chunk-0.pending")); val destination = root.resolve("chunk-0.enc")
        NativeStorageBridge.publish(source, destination)
        assertFalse(Files.exists(source, NOFOLLOW_LINKS)); assertArrayEquals(cipher, Files.readAllBytes(destination))
    }
    @Test fun existingDestinationNeverChangesAndStagingSurvives() = owned { root ->
        val source = file(root.resolve("chunk-0.pending")); val destination = file(root.resolve("chunk-0.enc"), other)
        assertEquals("LOCAL_COPY_CHANGED", rejected(source, destination).code)
        assertArrayEquals(cipher, Files.readAllBytes(source)); assertArrayEquals(other, Files.readAllBytes(destination))
    }
    @Test fun destinationSymlinkIsNotFollowedOrReplaced() = owned { root ->
        val victim = file(root.resolve("sentinel"), other); val source = file(root.resolve("chunk-0.pending"))
        val destination = Files.createSymbolicLink(root.resolve("chunk-0.enc"), victim.fileName)
        assertEquals("LOCAL_COPY_CHANGED", rejected(source, destination).code)
        assertTrue(Files.isSymbolicLink(destination)); assertArrayEquals(other, Files.readAllBytes(victim)); assertArrayEquals(cipher, Files.readAllBytes(source))
    }
    @Test fun stagingSymlinkIsRejectedWithoutFollowingIt() = owned { root ->
        val victim = file(root.resolve("sentinel"), other)
        val source = Files.createSymbolicLink(root.resolve("chunk-0.pending"), victim.fileName); val destination = root.resolve("chunk-0.enc")
        rejected(source, destination); assertTrue(Files.isSymbolicLink(source)); assertFalse(Files.exists(destination, NOFOLLOW_LINKS)); assertArrayEquals(other, Files.readAllBytes(victim))
    }
    @Test fun symlinkDirectoryIsRejected() = owned { root ->
        val real = Files.createDirectory(root.resolve("real"), directoryMode); file(real.resolve("chunk-0.pending"))
        val alias = Files.createSymbolicLink(root.resolve("alias"), real.fileName)
        rejected(alias.resolve("chunk-0.pending"), alias.resolve("chunk-0.enc"))
        assertArrayEquals(cipher, Files.readAllBytes(real.resolve("chunk-0.pending"))); assertFalse(Files.exists(real.resolve("chunk-0.enc"), NOFOLLOW_LINKS))
    }
    @Test fun nonRegularStagingIsRejected() = owned { root ->
        val source = Files.createDirectory(root.resolve("chunk-0.pending"), directoryMode); val destination = root.resolve("chunk-0.enc")
        rejected(source, destination); assertTrue(Files.isDirectory(source, NOFOLLOW_LINKS)); assertFalse(Files.exists(destination, NOFOLLOW_LINKS))
    }
    @Test fun sharedPermissionsAreRejectedWithoutMutation() = owned { root ->
        val source = file(root.resolve("chunk-0.pending")); val destination = root.resolve("chunk-0.enc")
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxr-xr-x"))
        rejected(source, destination); assertArrayEquals(cipher, Files.readAllBytes(source)); assertFalse(Files.exists(destination, NOFOLLOW_LINKS))
    }
    @Test fun invalidAndMissingInputsFailClosed() = owned { root ->
        val source = file(root.resolve("chunk-0.pending"))
        assertEquals(3, NativeStorageBridge.moveNoReplace(root.toString(), "chunk-0.pending", "../escape.enc"))
        assertEquals(3, NativeStorageBridge.moveNoReplace(root.toString(), "chunk-00.pending", "chunk-0.enc"))
        assertEquals(3, NativeStorageBridge.moveNoReplace(root.toString(), "chunk-256.pending", "chunk-256.enc"))
        assertEquals(3, NativeStorageBridge.moveNoReplace(root.toString() + "/../x", "chunk-0.pending", "chunk-0.enc"))
        rejected(root.resolve("chunk-1.pending"), root.resolve("chunk-1.enc"))
        assertArrayEquals(cipher, Files.readAllBytes(source)); assertFalse(Files.exists(root.resolve("chunk-0.enc"), NOFOLLOW_LINKS))
    }
}
