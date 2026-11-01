package com.narada.domain

import java.text.Normalizer
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object FoundationLimits {
    const val SCHEMA_VERSION = 2
    const val MAX_EVENTS = 200
    const val VISIBLE_EVENTS = 30
    const val MAX_GRANTS = 8
    const val MAX_REPORT_BYTES = 16 * 1024
}

data class WorkspaceSettings(
    val deviceName: String = "My phone",
    val setupCompleted: Boolean = false,
    val notificationsEnabled: Boolean = false,
    val reduceMotion: Boolean = false,
)

enum class FailureCode {
    INVALID_NAME, STORAGE_UNAVAILABLE, STORAGE_CORRUPT, PERMISSION_UNAVAILABLE,
    GRANT_NOT_PERSISTABLE, GRANT_LIMIT, SCHEDULER_UNAVAILABLE, NOTIFICATIONS_DENIED,
    REPORT_UNAVAILABLE, SENSITIVE_STORAGE_UNAVAILABLE,
}

class FoundationFailure(val code: FailureCode) : Exception(code.name)

object DeviceNamePolicy {
    fun normalize(input: String): String {
        val value = Normalizer.normalize(input.trim(), Normalizer.Form.NFC)
        val points = value.codePoints().toArray()
        val bidi = setOf(0x061C, 0x200E, 0x200F, 0x202A, 0x202B, 0x202C, 0x202D, 0x202E,
            0x2066, 0x2067, 0x2068, 0x2069)
        if (points.isEmpty() || points.size > 48 || value.toByteArray(Charsets.UTF_8).size > 192 ||
            points.any { Character.isISOControl(it) || it in bidi }) {
            throw FoundationFailure(FailureCode.INVALID_NAME)
        }
        return value
    }
}

object GrantPolicy {
    const val READ = 1
    const val WRITE = 2
    const val PERSISTABLE = 64
    fun readOnlyPersistenceFlags(offered: Int): Int {
        if (offered and PERSISTABLE == 0 || offered and READ == 0) {
            throw FoundationFailure(FailureCode.GRANT_NOT_PERSISTABLE)
        }
        return READ // Phase 1 deliberately never retains write permission.
    }
}

object PrivateNamePolicy {
    fun reportName(operationId: String): String {
        val parsed = runCatching { UUID.fromString(operationId) }.getOrNull()
        if (parsed == null || parsed.toString() != operationId) {
            throw FoundationFailure(FailureCode.REPORT_UNAVAILABLE)
        }
        return "support-$operationId.json"
    }
}

@Serializable
enum class LocalJobState { QUEUED, RUNNING, COMPLETED, CANCELLED, FAILED;
    val active: Boolean get() = this == QUEUED || this == RUNNING
}

data class LocalJob(
    val operationId: String,
    val state: LocalJobState,
    val requestedAt: Long,
    val updatedAt: Long,
    val errorCode: FailureCode? = null,
)

enum class ActivityKind {
    SETUP_COMPLETED, SETTINGS_SAVED, FOLDER_RETAINED, FOLDER_FORGOTTEN,
    CHECK_QUEUED, CHECK_COMPLETED, CHECK_CANCELLED, CHECK_FAILED,
    NOTIFICATION_SENT, NOTIFICATION_UNAVAILABLE,
}

data class LocalActivity(val id: String, val kind: ActivityKind, val createdAt: Long)
data class FolderGrant(val id: String, val label: String, val available: Boolean)

enum class Destination(val route: String, val label: String, val phase: Int) {
    HOME("home", "Home", 1), FILES("files", "Files", 3), CHAT("chat", "Chat", 5),
    TRANSFERS("transfers", "Transfers", 4), MORE("more", "More", 1),
    SYNC("sync", "Sync", 7), OFFLINE("offline", "Offline files", 7),
    DEVICES("devices", "Devices", 2), NOTIFICATIONS("notifications", "Notifications", 1),
    SETTINGS("settings", "Settings", 1), HELP("help", "Help", 1),
    STORAGE("storage", "Local storage", 1), DIAGNOSTICS("diagnostics", "Diagnostics", 1),
    APPEARANCE("appearance", "Appearance", 1);
    companion object {
        val primary = listOf(HOME, FILES, CHAT, TRANSFERS, MORE)
        fun fromRoute(route: String?): Destination = entries.firstOrNull { it.route == route } ?: HOME
    }
}

@Serializable
data class SupportReport(
    val appVersion: String,
    val androidApi: Int,
    val generatedAtEpochMillis: Long,
    val metadataHealthy: Boolean,
    val privateFilesHealthy: Boolean,
    val retainedFolderCount: Int?,
    val notificationsAllowed: Boolean,
    val lastLocalJobState: LocalJobState?,
    val formatVersion: Int = 1,
    val metadataSchema: Int = FoundationLimits.SCHEMA_VERSION,
    val developmentBuild: Boolean = true,
    val networkingImplemented: Boolean = false,
    val sensitiveContentStorageAvailable: Boolean = false,
) {
    init {
        require(appVersion.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")))
        require(androidApi in 1..100)
        require(retainedFolderCount == null || retainedFolderCount in 0..FoundationLimits.MAX_GRANTS)
    }
    fun json(): String = reportJson.encodeToString(this).also {
        check(it.toByteArray().size <= FoundationLimits.MAX_REPORT_BYTES)
    }
}

private val reportJson = Json { prettyPrint = true; encodeDefaults = true }

/** Phase 1 cannot persist messages or secrets in an ordinary metadata adapter. */
object SensitiveContentGate {
    val available: Boolean = false
    fun requireAvailable(): Nothing = throw FoundationFailure(FailureCode.SENSITIVE_STORAGE_UNAVAILABLE)
}
