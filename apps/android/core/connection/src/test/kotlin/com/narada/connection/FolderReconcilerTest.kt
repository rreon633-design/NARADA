package com.narada.connection

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class FolderReconcilerTest {
    private fun id(value: String) = UUID.nameUUIDFromBytes(value.toByteArray()).toString()
    private fun hash(value: String) = sha256(value.toByteArray())
    private val scope = FolderScope(id("link"), hash("peer"), id("attempt"), id("phone-root"), id("desktop-root"), 1)
    private fun file(name: String = "notes.txt", side: FolderSide = FolderSide.PHONE, content: String = "original", size: Long = 17) =
        FolderFile(id("${side.name}:$name"), name, hash("version:$content"), if (size == 0L) sha256(byteArrayOf()) else hash("$content:$size"), size)
    private val p = file()
    private val d = file(side = FolderSide.DESKTOP)
    private val common = FolderCommonFile(p.relativeLabel, p.objectId, d.objectId, p.sha256, p.size)
    private fun plan(phone: List<FolderFile> = emptyList(), desktop: List<FolderFile> = emptyList(), base: List<FolderCommonFile> = emptyList(),
                     keys: Set<String> = emptySet(), slots: Int = 4, bytes: Long = TransferRules.QUOTA) = FolderReconciler.plan(scope,
        FolderSnapshot(scope, FolderSide.PHONE, true, phone), FolderSnapshot(scope, FolderSide.DESKTOP, true, desktop),
        FolderCheckpoint(scope, base), FolderRecordedIntents(scope, keys), FolderCapacity(slots, bytes))
    private fun kind(plan: FolderPlan) = plan.decisions.single().kind
    @Test fun newPhoneFileProposesDesktopCopyWithoutReceipt() { val r=plan(phone=listOf(p));assertEquals(FolderDecisionKind.COPY_NEW,kind(r));assertEquals(FolderSide.PHONE,r.decisions.single().from);assertEquals(p.size,r.admittedBytes);assertNotNull(r.decisions.single().operationKey) }
    @Test fun newDesktopFileProposesPhoneCopy() { val r=plan(desktop=listOf(d));assertEquals(FolderDecisionKind.COPY_NEW,kind(r));assertEquals(FolderSide.DESKTOP,r.decisions.single().from) }
    @Test fun equalBytesNeedNoTransferButAreNotADeliveryReceipt() { val r=plan(listOf(p),listOf(d));assertEquals(FolderDecisionKind.CONTENT_EQUIVALENT,kind(r));assertEquals(0,r.admittedFiles);assertNull(r.decisions.single().operationKey) }
    @Test fun phoneOnlyChangeProposesRetainedVersionNotOverwrite() { val changed=file(content="phone edit");val r=plan(listOf(changed),listOf(d),listOf(common));assertEquals(FolderDecisionKind.COPY_RETAINED_VERSION,kind(r));assertEquals(FolderSide.PHONE,r.decisions.single().from) }
    @Test fun desktopOnlyChangeProposesRetainedVersion() { val r=plan(listOf(p),listOf(file(side=FolderSide.DESKTOP,content="desktop edit")),listOf(common));assertEquals(FolderDecisionKind.COPY_RETAINED_VERSION,kind(r));assertEquals(FolderSide.DESKTOP,r.decisions.single().from) }
    @Test fun simultaneousDifferentEditsPreserveBothForReview() { val r=plan(listOf(file(content="a")),listOf(file(side=FolderSide.DESKTOP,content="b")),listOf(common));assertEquals(FolderDecisionKind.REVIEW_CONFLICT,kind(r));assertEquals(0,r.admittedFiles) }
    @Test fun identicalSimultaneousEditsDoNotNeedAConflictWinner() { assertEquals(FolderDecisionKind.CONTENT_EQUIVALENT,kind(plan(listOf(file(content="same")),listOf(file(side=FolderSide.DESKTOP,content="same")),listOf(common)))) }
    @Test fun sameNameDifferentBytesWithoutABaseAreAConflict() { assertEquals(FolderDecisionKind.REVIEW_CONFLICT,kind(plan(listOf(p),listOf(file(side=FolderSide.DESKTOP,content="different"))))) }
    @Test fun missingPhoneNeverDeletesOrResurrects() { assertEquals(FolderDecisionKind.REVIEW_MISSING,kind(plan(desktop=listOf(d),base=listOf(common)))) }
    @Test fun missingDesktopNeverDeletesOrResurrects() { assertEquals(FolderDecisionKind.REVIEW_MISSING,kind(plan(phone=listOf(p),base=listOf(common)))) }
    @Test fun bothMissingRetainAReviewObservation() { assertEquals(FolderDecisionKind.REVIEW_MISSING,kind(plan(base=listOf(common)))) }
    @Test fun missingSideAndEditedSurvivorAreStillReview() { assertEquals(FolderDecisionKind.REVIEW_MISSING,kind(plan(phone=listOf(file(content="edit")),base=listOf(common)))) }
    @Test fun knownRenameFencesBothOldAndNewLabels() { val renamed=p.copy(relativeLabel="renamed.txt");val r=plan(listOf(renamed),listOf(d),listOf(common));assertEquals(2,r.decisions.size);assertTrue(r.decisions.all{it.kind==FolderDecisionKind.REVIEW_RENAME});assertEquals(0,r.admittedFiles) }
    @Test fun replacementObjectCannotAdoptAnOldCheckpointEvenWithEqualBytes() { assertEquals(FolderDecisionKind.REVIEW_IDENTITY,kind(plan(listOf(p.copy(objectId=id("replacement"))),listOf(d),listOf(common)))) }
    @Test fun sameSideCaseCollisionHasNoCopy() { val r=plan(phone=listOf(p,file("NOTES.txt")));assertEquals(FolderDecisionKind.REVIEW_NAME_COLLISION,kind(r));assertEquals(0,r.admittedFiles) }
    @Test fun desktopOnlyCaseCollisionIsAlsoHandledWithoutAnEmptyListCrash() { assertEquals(FolderDecisionKind.REVIEW_NAME_COLLISION,kind(plan(desktop=listOf(d,file("NOTES.txt",FolderSide.DESKTOP))))) }
    @Test fun oppositeSideCaseDifferenceIsNotANameMerge() { assertEquals(FolderDecisionKind.REVIEW_NAME_COLLISION,kind(plan(listOf(p),listOf(d.copy(relativeLabel="NOTES.txt"))))) }
    @Test fun fileCannotAlsoBeADestinationParent() { val r=plan(listOf(file("Photos")),listOf(file("photos/a.jpg",FolderSide.DESKTOP)));assertEquals(2,r.decisions.size);assertTrue(r.decisions.all{it.kind==FolderDecisionKind.REVIEW_NAME_COLLISION}) }
    @Test fun equalBytesAtDifferentNamesAreIntentionalSeparateFiles() { val r=plan(phone=listOf(p,file("another.txt")));assertEquals(2,r.admittedFiles);assertEquals(2,r.decisions.map{it.operationKey}.distinct().size) }
    @Test fun orderIsDeterministicEvenWhenCapacityDefersWork() { val a=file("z.txt");val b=file("a.txt");assertEquals(plan(listOf(a,b),slots=1),plan(listOf(b,a),slots=1));assertEquals(FolderDecisionKind.WAIT_CAPACITY,plan(listOf(a,b),slots=1).decisions.last().kind) }
    @Test fun exactRecordedIntentSuppressesAnotherProposalWithoutClaimingDelivery() { val op=FolderReconciler.operationKey(scope,FolderSide.PHONE,p);val r=plan(listOf(p),keys=setOf(op),slots=0,bytes=0);assertEquals(FolderDecisionKind.ALREADY_RECORDED,kind(r));assertEquals(0,r.admittedFiles) }
    @Test fun newVersionHasAnotherIntentKey() { val a=FolderReconciler.operationKey(scope,FolderSide.PHONE,p);val changed=p.copy(version=hash("new version"));assertNotEquals(a,FolderReconciler.operationKey(scope,FolderSide.PHONE,changed));assertEquals(FolderDecisionKind.COPY_NEW,kind(plan(listOf(changed),keys=setOf(a)))) }
    @Test fun intentKeysBindDirectionAndEveryScopeField() { val first=FolderReconciler.operationKey(scope,FolderSide.PHONE,p);assertNotEquals(first,FolderReconciler.operationKey(scope,FolderSide.DESKTOP,p));listOf(scope.copy(linkId=id("other")),scope.copy(peerId=hash("other")),scope.copy(pairingAttempt=id("other")),scope.copy(phoneRootId=id("other")),scope.copy(desktopRootId=id("other")),scope.copy(authorityRevision=2)).forEach{assertNotEquals(first,FolderReconciler.operationKey(it,FolderSide.PHONE,p))} }
    @Test fun noSlotsDefersWithoutDroppingSource() { val r=plan(listOf(p),slots=0);assertEquals(FolderDecisionKind.WAIT_CAPACITY,kind(r));assertEquals(p,r.decisions.single().source) }
    @Test fun byteBudgetDoesNotOverflowOrOverbook() { val r=plan(listOf(file("a",size=64),file("b",size=64)),bytes=100);assertEquals(1,r.admittedFiles);assertEquals(64L,r.admittedBytes);assertEquals(FolderDecisionKind.WAIT_CAPACITY,r.decisions.last().kind) }
    @Test fun currentFileLimitIsNotSilentlyWidenedForFutureSync() { assertEquals(FolderDecisionKind.REVIEW_FILE_LIMIT,kind(plan(listOf(file(size=TransferRules.MAX_BYTES+1)))));assertEquals(1,plan(listOf(file(size=TransferRules.MAX_BYTES))).admittedFiles) }
    @Test fun zeroByteFilesStillConsumeARecordSlot() { val r=plan(listOf(file(size=0)),bytes=0);assertEquals(1,r.admittedFiles);assertEquals(0L,r.admittedBytes) }
    @Test fun incompleteScanIsNotAnEmptyFolder() { expectFailure("FOLDER_SCAN_INCOMPLETE"){FolderReconciler.plan(scope,FolderSnapshot(scope,FolderSide.PHONE,false,emptyList()),FolderSnapshot(scope,FolderSide.DESKTOP,true,listOf(d)),FolderCheckpoint(scope,listOf(common)),FolderRecordedIntents(scope),FolderCapacity(4,128))} }
    @Test fun wrongScopeCannotReuseCheckpointOrIntentHistory() { val wrong=scope.copy(authorityRevision=2);expectFailure("FOLDER_SCOPE_CHANGED"){FolderReconciler.plan(scope,FolderSnapshot(scope,FolderSide.PHONE,true,listOf(p)),FolderSnapshot(scope,FolderSide.DESKTOP,true,emptyList()),FolderCheckpoint(scope,emptyList()),FolderRecordedIntents(wrong),FolderCapacity(4,128))} }
    @Test fun wrongSideCannotChangeDirection() { expectFailure("FOLDER_SCOPE_CHANGED"){FolderReconciler.plan(scope,FolderSnapshot(scope,FolderSide.DESKTOP,true,listOf(p)),FolderSnapshot(scope,FolderSide.DESKTOP,true,emptyList()),FolderCheckpoint(scope,emptyList()),FolderRecordedIntents(scope),FolderCapacity(4,128))} }
    @Test fun duplicateObjectIdentityIsRejected() { expectFailure("INVALID_FOLDER_SNAPSHOT"){plan(listOf(p,p.copy(relativeLabel="alias.txt")))} }
    @Test fun malformedLabelsAndHashesNeverBecomePaths() { listOf("../notes","/notes","a//b","a/CON.txt",".secret","a\\b","a:stream","e\u0301.txt").forEach{expectFailure{plan(listOf(p.copy(relativeLabel=it)))}};expectFailure{plan(listOf(p.copy(sha256="A".repeat(64))))};expectFailure{plan(listOf(p.copy(objectId="content://private")))} }
    @Test fun contradictoryHashLengthsRejectTheWholePlan() { expectFailure("INVALID_FOLDER_SNAPSHOT"){plan(listOf(p),listOf(d.copy(size=18)))} }
    @Test fun negativeSizeAndWrongEmptyDigestAreRejected() { expectFailure("INVALID_FOLDER_SNAPSHOT"){plan(listOf(p.copy(size=-1)))};expectFailure("INVALID_FOLDER_SNAPSHOT"){plan(listOf(p.copy(size=0)))} }
    @Test fun entryLimitRejectsRatherThanTruncates() { expectFailure("FOLDER_ENTRY_LIMIT"){plan((0..FolderReconciler.MAX_ENTRIES).map{file("$it.txt")})} }
    @Test fun invalidCapacityAndReplayKeyAreRejected() { expectFailure("FOLDER_CAPACITY_INVALID"){plan(listOf(p),slots=5)};expectFailure("FOLDER_CAPACITY_INVALID"){plan(listOf(p),bytes=-1)};expectFailure{plan(keys=setOf("invalid"))} }
    @Test fun checkpointCannotBindOneObjectToTwoLogicalNames() { expectFailure("INVALID_FOLDER_CHECKPOINT"){plan(base=listOf(common,common.copy(relativeLabel="alias.txt")))} }
    @Test fun replayHistoryIsBoundedAndNotEvicted() { expectFailure("FOLDER_RECORD_LIMIT"){plan(keys=(0..FolderReconciler.MAX_RECORDED).map{hash("key$it")}.toSet())} }
    @Test fun structuredObjectsDoNotPrintProtectedLabelsOrIdentifiers() { listOf(scope,p,FolderSnapshot(scope,FolderSide.PHONE,true,listOf(p)),common,FolderCheckpoint(scope,listOf(common)),FolderRecordedIntents(scope),plan(listOf(p)),plan(listOf(p)).decisions.single()).forEach { assertFalse(it.toString().contains("notes.txt"));assertFalse(it.toString().contains(p.objectId));assertFalse(it.toString().contains(scope.peerId)) } }
}
