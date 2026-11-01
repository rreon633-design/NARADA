package com.narada.connection

import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TransfersProtocolTest {
    private fun manifest(size: Int = 17) = TransferSamples.bundle(TransferSamples.bytes(size)).manifest
    @Test fun decimalSizeIsCanonicalAndBounded() { listOf("-1","01","1.0"," 1","67108865","999999999999999999999").forEach { expectFailure { TransferRules.size(it) } }; assertEquals(TransferRules.MAX_BYTES,TransferRules.size("67108864")) }
    @Test fun chunksCoverEmptyFullAndPartialTails() { val empty=manifest(0);assertEquals(0,empty.chunk_count);expectFailure("INVALID_CHUNK"){empty.length(0)};val tail=manifest(TransferRules.CHUNK+17);assertEquals(TransferRules.CHUNK,tail.length(0));assertEquals(17,tail.length(1));expectFailure("INVALID_CHUNK"){tail.length(2)} }
    @Test fun wrongProtocolAndDimensionsAreRejected() { val m=manifest();listOf(m.copy(version=2),m.copy(chunk_size=16),m.copy(chunk_count=2),m.copy(chunk_count=-1),m.copy(size="01")).forEach { expectFailure { it.validate() } } }
    @Test fun hashCaseAndFilenameTraversalAreRejected() { val m=manifest();listOf(m.copy(sha256="A".repeat(64)),m.copy(manifest_id="z".repeat(64)),m.copy(name="../source"),m.copy(name="CON.txt"),m.copy(name="hidden. ")).forEach { expectFailure { it.validate() } } }
    @Test fun zeroByteManifestCannotClaimAnotherFullHash() { expectFailure("INVALID_MANIFEST"){manifest(0).copy(sha256="a".repeat(64)).validate()} }
    @Test fun immutableDigestBindsEveryFieldAndOrderedHashList() { val b=TransferSamples.bundle(TransferSamples.bytes());b.manifest.verify(b.hashes);expectFailure("MANIFEST_MISMATCH"){b.manifest.copy(name="different.bin").verify(b.hashes)};expectFailure("MANIFEST_MISMATCH"){b.manifest.verify(b.hashes.reversed())};expectFailure("INVALID_MANIFEST"){b.manifest.verify(b.hashes.drop(1))} }
    @Test fun unicodeManifestDigestUsesCanonicalUtf8Tuple() {
        val b=TransferSamples.bundle(byteArrayOf(1,2,3),"నమూనా.bin");val m=b.manifest
        val exact="[1,\"${m.source_version}\",\"నమూనా.bin\",\"3\",262144,1,\"${m.sha256}\",[\"${b.hashes.single()}\"]]"
        assertEquals(sha256(exact.toByteArray(Charsets.UTF_8)),m.manifest_id)
    }
    @Test fun sourceAndChunkPathsNeverAcceptUrisOrTraversal() { listOf("../x","content://provider/root","/etc/passwd","","AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA").forEach { expectFailure { TransferRules.chunkPath(it,0) } };expectFailure { TransferSamples.source.copy(object_id="/secret").validate() };expectFailure("INVALID_CHUNK"){TransferRules.chunkPath(TransferSamples.id,256)} }
    @Test fun manifestPagesRejectCrossTransferAndCrossManifest() { val m=manifest();val p=TransferManifestPage(TransferSamples.id,m.manifest_id,0,listOf(m.sha256),null);p.validate(TransferSamples.id,m,0);expectFailure { p.copy(transfer_id=UUID.randomUUID().toString()).validate(TransferSamples.id,m,0) };expectFailure { p.copy(manifest_id="b".repeat(64)).validate(TransferSamples.id,m,0) } }
    @Test fun manifestPagesRejectMissingDuplicateAndNonprogressingBounds() { val m=manifest();val p=TransferManifestPage(TransferSamples.id,m.manifest_id,0,listOf(m.sha256),null);listOf(p.copy(offset=1),p.copy(hashes=emptyList()),p.copy(hashes=listOf(m.sha256,m.sha256)),p.copy(next_offset=0)).forEach{expectFailure{it.validate(TransferSamples.id,m,0)}} }
    @Test fun pageBoundaryAtThirtyTwoHasAnExactNextOffset() { val m=manifest().copy(size=(TransferRules.CHUNK*33).toString(),chunk_count=33);val page=TransferManifestPage(TransferSamples.id,m.manifest_id,0,List(32){"a".repeat(64)},32);page.validate(TransferSamples.id,m,0);expectFailure{page.copy(next_offset=null).validate(TransferSamples.id,m,0)} }
    @Test fun manifestWireRejectsQuotedNumbersAndUnknownFields() { val json=Wire.json.encodeToJsonElement(manifest()).jsonObject;listOf(JsonObject(json+mapOf("version" to JsonPrimitive("1"))),JsonObject(json+mapOf("path" to JsonPrimitive("private")))).forEach{expectFailure{Wire.decode<TransferManifest>(it.toString().toByteArray())}} }
    @Test fun duplicateManifestFieldsCannotSmuggleAnotherSize() { val text=Wire.encode(manifest()).decodeToString().replace("\"size\":\"17\"","\"size\":\"17\",\"size\":\"0\"");expectFailure("DUPLICATE_FIELD"){Wire.decode<TransferManifest>(text.toByteArray())} }
    @Test fun remoteSourceProgressIsNotPhoneCheckpointProgress() { val f=TransferPeerFixture();val r=f.record;val s=TransferStatusReply(r.id,"ready",f.bundle.manifest,emptyList(),r.size.toString(),null,null);s.source(r);assertEquals(0L,r.verifiedBytes);expectFailure{ s.copy(verified_bytes="0").source(r) } }
    @Test fun sourceReplyBindsSelectedNameVersionSizeAndIdentifier() { val f=TransferPeerFixture();val s=TransferStatusReply(f.record.id,"ready",f.bundle.manifest,emptyList(),f.record.size.toString(),null,null);listOf(s.copy(transfer_id=UUID.randomUUID().toString()),s.copy(manifest=f.bundle.manifest.copy(name="other.bin")),s.copy(state="receiving"),s.copy(missing=listOf(0)),s.copy(result=buildJsonObject{put("path","private")})).forEach{expectFailure{it.source(f.record)}} }
    @Test fun runningTokenAndReceiptShapeFailClosed() { val f=TransferPeerFixture();expectFailure{f.record.copy(runId=null).validate()};expectFailure{f.record.copy(state=DownloadState.VERIFIED).validate()};expectFailure{f.record.copy(remoteFinished=true).validate()} }
    @Test fun checkpointsMustBeUniqueSortedAndWithinManifest() { val f=TransferPeerFixture();listOf(listOf(1,0),listOf(0,0),listOf(256),listOf(-1)).forEach{expectFailure{f.withManifest().copy(chunks=it).validate()}} }
    @Test fun oldPayloadCannotSilentlyContainNewTransfers() = runBlocking<Unit> { val f=DownloadFixture();f.enqueue();expectFailure("TRUST_STORE_INVALID"){f.memory.document.copy(version=2).validate()};f.memory.document.copy(version=3).validate() }
    @Test fun exportReceiptsCannotInventSuccessOrRawPathTargets() { val id=UUID.randomUUID().toString();expectFailure{ExportRecord(id,"copy.bin",1,state=ExportState.VERIFIED).validate()};expectFailure{ExportRecord(id,"copy.bin",1,state=ExportState.INTENT,tree="file:///private").validate()};ExportRecord(id,"copy.bin",1,state=ExportState.UNCERTAIN).validate() }
    @Test fun redactedRecordsNeverPrintPrivateNamesOrUris() { val f=TransferPeerFixture();assertFalse(f.record.toString().contains("ordinary.bin"));assertFalse(ExportRecord(UUID.randomUUID().toString(),"PRIVATE-NAME.bin",1,tree="content://provider/PRIVATE-URI",state=ExportState.UNCERTAIN).toString().contains("PRIVATE")) }
}
