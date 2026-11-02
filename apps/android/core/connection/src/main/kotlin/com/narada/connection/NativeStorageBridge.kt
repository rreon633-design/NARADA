package com.narada.connection

import java.nio.file.Path

/** Filesystem-only bridge v1. No crypto/library-state change or Java overwrite fallback.
 * Inputs are the validated opaque app-private fragment paths, never remote names or SAF URIs. */
object NativeStorageBridge {
    private val available = try {
        System.loadLibrary("narada_storage")
        true
    } catch (_: LinkageError) { false } catch (_: SecurityException) { false }

    @JvmStatic external fun moveNoReplace(directory: String, source: String, destination: String): Int

    /** Consumes the staged file only on an atomic move; collision/unknown output is preserved. */
    fun publish(pending: Path, installed: Path) {
        demand(available, "LOCAL_STORAGE_UNAVAILABLE")
        demand(pending.isAbsolute && installed.isAbsolute && pending.parent == installed.parent, "LOCAL_COPY_CHANGED")
        val result = try {
            moveNoReplace(pending.parent.toString(), pending.fileName.toString(), installed.fileName.toString())
        } catch (_: LinkageError) { fail("LOCAL_STORAGE_UNAVAILABLE") }
        when (result) {
            0 -> Unit
            1 -> fail("LOCAL_COPY_CHANGED")
            2 -> fail("LOCAL_STORAGE_FULL")
            else -> fail("LOCAL_STORAGE_UNAVAILABLE")
        }
    }
}
