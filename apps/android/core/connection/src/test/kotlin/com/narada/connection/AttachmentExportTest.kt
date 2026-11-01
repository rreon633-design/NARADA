package com.narada.connection

import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*

class AttachmentExportTest {
    private val tree = "content://fixture.documents/tree/root"
    @Test fun fullAgeVerificationPrecedesCreateAndReadbackPrecedesReceipt() = runBlocking<Unit> {
        val f = AttachmentFixture(TransferSamples.bytes(32001)); val r = f.outgoing(); val export = AttachmentExport(f.repository, f.engine, now = { f.clock })
        val e = export.prepare(r.id, "inert-copy.bin", true); val provider = FakeDocumentProvider()
        provider.beforeCreate = { assertEquals(ExportState.INTENT, f.record(r.id).exports.single().state) }
        provider.beforeWrite = { assertEquals(provider.uri, f.record(r.id).exports.single().document); assertEquals(ExportState.CREATED, f.record(r.id).exports.single().state) }
        export.execute(r.id, e.id, tree, provider)
        assertArrayEquals(f.plain, provider.bytes.toByteArray()); assertEquals(ExportState.VERIFIED, f.record(r.id).exports.single().state); assertTrue(provider.closed)
    }
    @Test fun corruptCipherNeverCreatesProviderDocument() = runBlocking<Unit> {
        val f = AttachmentFixture(byteArrayOf(1)); val r = f.outgoing(); val ex = AttachmentExport(f.repository, f.engine); val e = ex.prepare(r.id, "safe.bin", true); val provider = FakeDocumentProvider()
        f.chunks.data[r.id to 0]!![0] = 0
        expectFailure("LOCAL_COPY_CHANGED") { runBlocking { ex.execute(r.id, e.id, tree, provider) } }
        assertEquals(0, provider.creates); assertEquals(ExportState.NOT_CREATED, f.record(r.id).exports.single().state); assertFalse(f.record(r.id).available)
    }
    @Test fun emptyFileCanBeExportedOnlyAfterAnAuthenticatedAgeStream() = runBlocking<Unit> {
        val f = AttachmentFixture(byteArrayOf()); val r = f.outgoing(); val ex = AttachmentExport(f.repository, f.engine); val e = ex.prepare(r.id, "empty.bin", true); val provider = FakeDocumentProvider(); ex.execute(r.id, e.id, tree, provider)
        assertEquals(0, provider.bytes.size()); assertEquals(ExportState.VERIFIED, f.record(r.id).exports.single().state)
    }
    @Test fun failedPreflightIsNotCreatedAndDoesNotChangePrivateBytes() = runBlocking<Unit> {
        val f = AttachmentFixture(byteArrayOf(1)); val r = f.outgoing(); val ex = AttachmentExport(f.repository, f.engine); val e = ex.prepare(r.id, "safe.bin", true); val provider = FakeDocumentProvider().apply { preflightFailure = "EXPORT_NAME_COLLISION" }
        expectFailure("EXPORT_NAME_COLLISION") { runBlocking { ex.execute(r.id, e.id, tree, provider) } }; assertEquals(0, provider.creates); assertEquals(ExportState.NOT_CREATED, f.record(r.id).exports.single().state); assertArrayEquals(f.plain, f.engine.verifiedPlain(r.id))
    }
    @Test fun lostCreateReplyRemainsUncertainAndCannotAutoRetry() = runBlocking<Unit> {
        val f = AttachmentFixture(byteArrayOf(1)); val r = f.outgoing(); val ex = AttachmentExport(f.repository, f.engine); val e = ex.prepare(r.id, "safe.bin", true); val provider = FakeDocumentProvider().apply { createFailure = "PROVIDER_CREATE_UNCONFIRMED" }
        expectFailure("PROVIDER_CREATE_UNCONFIRMED") { runBlocking { ex.execute(r.id, e.id, tree, provider) } }; assertEquals(ExportState.UNCERTAIN, f.record(r.id).exports.single().state)
        expectFailure("EXPORT_UNRESOLVED") { runBlocking { ex.prepare(r.id, "another.bin", true) } }; assertEquals(1, provider.creates)
    }
    @Test fun partialWriteAndBadReadbackNeverClaimExported() = runBlocking<Unit> {
        val f = AttachmentFixture(byteArrayOf(1, 2)); val r = f.outgoing(); val ex = AttachmentExport(f.repository, f.engine); val e = ex.prepare(r.id, "safe.bin", true); val provider = FakeDocumentProvider().apply { readTransform = { it.copyOf(1) } }
        expectFailure("EXPORT_VERIFICATION_FAILED") { runBlocking { ex.execute(r.id, e.id, tree, provider) } }; assertEquals(ExportState.UNCERTAIN, f.record(r.id).exports.single().state); assertTrue(f.record(r.id).available)
    }
    @Test fun interruptedCreateCannotWriteOrObtainALateVerifiedReceipt() = runBlocking<Unit> {
        val f = AttachmentFixture(byteArrayOf(1)); val r = f.outgoing(); val ex = AttachmentExport(f.repository, f.engine); val e = ex.prepare(r.id, "safe.bin", true); val provider = FakeDocumentProvider()
        provider.beforeCreate = { runBlocking { ex.interrupted(r.id, e.id) } }
        expectFailure("EXPORT_STOPPED") { runBlocking { ex.execute(r.id, e.id, tree, provider) } }; assertEquals(0, provider.writes); assertEquals(ExportState.UNCERTAIN, f.record(r.id).exports.single().state)
    }
    @Test fun pickerOnlyRestartNeverCreatesAndAdmittedCreateRestartStaysUncertain() = runBlocking<Unit> {
        val f = AttachmentFixture(byteArrayOf(1)); val r = f.outgoing(); val ex = AttachmentExport(f.repository, f.engine); val e = ex.prepare(r.id, "safe.bin", true); f.reopen(); assertEquals(ExportState.NOT_CREATED, f.record(r.id).exports.single().state)
        ex.forget(r.id, e.id, true); val second = ex.prepare(r.id, "next.bin", true)
        f.memory.document = f.memory.document.replaceAttachment(f.record(r.id).copy(exports = listOf(second.copy(state = ExportState.INTENT, tree = tree))))
        f.reopen(); assertEquals(ExportState.UNCERTAIN, f.record(r.id).exports.single().state)
    }
    @Test fun uncertainExportBlocksRemovalUntilExplicitReviewedDismissal() = runBlocking<Unit> {
        val f = AttachmentFixture(byteArrayOf(1)); val r = f.outgoing(); val ex = AttachmentExport(f.repository, f.engine); val e = ex.prepare(r.id, "safe.bin", true)
        f.memory.document = f.memory.document.replaceAttachment(f.record(r.id).copy(exports = listOf(e.copy(state = ExportState.INTENT, tree = tree)))); ex.interrupted(r.id, e.id)
        expectFailure("ATTACHMENT_BUSY") { runBlocking { f.engine.cancel(r.id, true) } }; ex.dismiss(r.id, e.id, true); f.engine.cancel(r.id, true); f.engine.remove(r.id, true); assertTrue(f.memory.document.attachments.isEmpty())
    }
    @Test fun atMostTwoRetainedExportRecordsAndNoUnreviewedForget() = runBlocking<Unit> {
        val f = AttachmentFixture(byteArrayOf(1)); val r = f.outgoing(); val ex = AttachmentExport(f.repository, f.engine)
        repeat(2) { val e = ex.prepare(r.id, "safe-$it.bin", true); ex.pickerCancelled(r.id, e.id) }
        expectFailure("EXPORT_HISTORY_FULL") { runBlocking { ex.prepare(r.id, "third.bin", true) } }
        expectFailure("LOCAL_CONFIRMATION_REQUIRED") { runBlocking { ex.forget(r.id, f.record(r.id).exports.first().id, false) } }
        ex.forget(r.id, f.record(r.id).exports.first().id, true); assertEquals(1, f.record(r.id).exports.size)
    }
}
