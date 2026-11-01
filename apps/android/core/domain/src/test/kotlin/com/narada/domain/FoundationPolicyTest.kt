package com.narada.domain

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class FoundationPolicyTest {
    @Test fun nameTrimsAndNormalizes() { assertEquals("Café", DeviceNamePolicy.normalize("  Cafe\u0301  ")) }
    @Test fun namesSupportTeluguAndEmoji() { assertEquals("నా ఫోన్ 📱", DeviceNamePolicy.normalize("నా ఫోన్ 📱")) }
    @Test fun blankNameIsRejected() { rejects(FailureCode.INVALID_NAME) { DeviceNamePolicy.normalize("   ") } }
    @Test fun nameIsCodePointBounded() { rejects(FailureCode.INVALID_NAME) { DeviceNamePolicy.normalize("a".repeat(49)) } }
    @Test fun controlsAreRejected() { listOf("a\u0000b", "a\nb", "a\u0085b").forEach { v -> rejects(FailureCode.INVALID_NAME) { DeviceNamePolicy.normalize(v) } } }
    @Test fun bidiSpoofingControlsAreRejected() { rejects(FailureCode.INVALID_NAME) { DeviceNamePolicy.normalize("Phone\u202Eexe") } }
    @Test fun exactNameLimitIsAccepted() { assertEquals(48, DeviceNamePolicy.normalize("a".repeat(48)).length) }
    @Test fun grantRequiresPersistableConsent() { rejects(FailureCode.GRANT_NOT_PERSISTABLE) { GrantPolicy.readOnlyPersistenceFlags(GrantPolicy.READ) } }
    @Test fun grantRequiresReadConsent() { rejects(FailureCode.GRANT_NOT_PERSISTABLE) { GrantPolicy.readOnlyPersistenceFlags(GrantPolicy.WRITE or GrantPolicy.PERSISTABLE) } }
    @Test fun writeGrantIsNeverRetainedInPhaseOne() { assertEquals(1, GrantPolicy.readOnlyPersistenceFlags(1 or 2 or 64)) }
    @Test fun unrelatedGrantFlagsDoNotBecomeAuthority() { assertEquals(1, GrantPolicy.readOnlyPersistenceFlags(1 or 64 or 128)) }
    @Test fun generatedReportNameIsScoped() { val id=UUID.randomUUID().toString(); assertEquals("support-$id.json", PrivateNamePolicy.reportName(id)) }
    @Test fun traversalCannotNamePrivateFiles() { listOf("../outside", "/etc/passwd", "x/y", "a\\b", "", "00000000-0-0-0-0").forEach { v -> rejects(FailureCode.REPORT_UNAVAILABLE) { PrivateNamePolicy.reportName(v) } } }
    @Test fun onlyQueuedAndRunningJobsAreActive() { assertEquals(setOf(LocalJobState.QUEUED,LocalJobState.RUNNING),LocalJobState.entries.filter { it.active }.toSet()) }
    @Test fun fivePrimaryTabsHaveMoreAsRealDestination() { assertEquals(listOf("home","files","chat","transfers","more"),Destination.primary.map { it.route }) }
    @Test fun unknownRouteCannotInvokeACommand() { assertEquals(Destination.HOME,Destination.fromRoute("send_chat")) }
    @Test fun laterFeaturesDoNotClaimFoundationAvailability() { assertEquals(5,Destination.CHAT.phase);assertEquals(2,Destination.DEVICES.phase);assertEquals(7,Destination.OFFLINE.phase) }
    @Test fun preferencesDefaultToNoNotifications() { val s=WorkspaceSettings();assertFalse(s.setupCompleted);assertFalse(s.notificationsEnabled) }
    @Test fun sensitiveStoreCannotFallBackToPlaintext() { assertFalse(SensitiveContentGate.available);rejects(FailureCode.SENSITIVE_STORAGE_UNAVAILABLE) { SensitiveContentGate.requireAvailable() } }
    @Test fun supportReportIsAnExplicitAllowlist() {
        val report=SupportReport("0.1.0",35,123456L,true,true,2,false,LocalJobState.COMPLETED)
        val fields=Json.parseToJsonElement(report.json()).jsonObject.keys
        assertEquals(setOf("appVersion","androidApi","generatedAtEpochMillis","metadataHealthy","privateFilesHealthy","retainedFolderCount","notificationsAllowed","lastLocalJobState","formatVersion","metadataSchema","developmentBuild","networkingImplemented","sensitiveContentStorageAvailable"),fields)
        assertFalse(report.networkingImplemented); assertFalse(report.sensitiveContentStorageAvailable)
    }
    @Test fun reportRejectsUnboundedOrPrivateVersionText() {
        assertThrows(IllegalArgumentException::class.java) { SupportReport("PERSONAL_LABEL",35,0,true,true,0,false,null) }
    }
    @Test fun reportCannotInventAnUnboundedGrantCount() {
        assertThrows(IllegalArgumentException::class.java) { SupportReport("0.1.0",35,0,true,true,9,false,null) }
    }
    private fun rejects(code:FailureCode,block:()->Unit) { val error=assertThrows(FoundationFailure::class.java,block);assertEquals(code,error.code) }
}
