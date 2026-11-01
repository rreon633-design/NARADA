package com.narada.android.platform

import android.content.Context
import androidx.core.content.FileProvider
import android.net.Uri
import com.narada.domain.*
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class PrivateFiles @Inject constructor(@param:ApplicationContext private val context: Context) {
    private val probeMutex = Mutex()
    suspend fun probe(): Boolean = withContext(Dispatchers.IO) { probeMutex.withLock {
        val root = File(context.noBackupFilesDir, "local-checks").also { check(it.mkdirs() || it.isDirectory) }
        // A process can die before finally. This namespace contains ONLY our tiny
        // check probes, not user content; serialize probes and reclaim known orphans.
        val owned = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.probe")
        root.listFiles()?.filter { it.isFile && owned.matches(it.name) }?.forEach { check(it.delete()) }
        val file = File(root, "${UUID.randomUUID()}.probe")
        val data = "NARADA_LOCAL_CHECK_V1".toByteArray(Charsets.US_ASCII)
        check(file.createNewFile())
        try {
            FileOutputStream(file).use { it.write(data); it.fd.sync() }
            file.inputStream().use { it.readAtMost(data.size + 1).contentEquals(data) }
        } finally { file.delete() }
    } }
    suspend fun exportReport(report: SupportReport): Uri = withContext(Dispatchers.IO) {
        val bytes = report.json().toByteArray(Charsets.UTF_8)
        check(bytes.size <= FoundationLimits.MAX_REPORT_BYTES)
        val root = File(context.cacheDir, "support").also { check(it.mkdirs() || it.isDirectory) }
        // Only this adapter's own UUID-named reports can be cleaned; no selected-folder content.
        root.listFiles()?.filter { it.name.matches(Regex("support-[a-f0-9-]{36}\\.json")) }
            ?.sortedByDescending { it.lastModified() }?.drop(4)?.forEach { it.delete() }
        val file = File(root, PrivateNamePolicy.reportName(UUID.randomUUID().toString()))
        check(file.createNewFile())
        try { FileOutputStream(file).use { it.write(bytes); it.fd.sync() } }
        catch (failure: Exception) { file.delete(); throw failure }
        FileProvider.getUriForFile(context, "${context.packageName}.support", file)
    }
}
