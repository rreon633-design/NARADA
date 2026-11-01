package com.narada.connection

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.crypto.KeyGenerator
import org.junit.Assert.*
import org.junit.Test

class TransferChunksTest {
    private class Fixture : AutoCloseable {
        val root=Files.createTempDirectory("narada-chunk-test-");val base=root.resolve("received")
        val owner=UUID.randomUUID().toString();val bytes="PRIVATE_DOCUMENT_CONTENT_fixture".repeat(20).toByteArray()
        val bundle=TransferSamples.bundle(bytes)
        val record=DownloadRecord(TransferSamples.id,"a".repeat(64),UUID.randomUUID().toString(),TransferSamples.source,"ordinary.bin",bytes.size.toLong(),1,manifest=bundle.manifest,hashes=bundle.hashes)
        val key=KeyGenerator.getInstance("AES").apply{init(256)}.generateKey()
        val crypto=object:ChunkCryptography{override fun seal(context:ChunkContext,clear:ByteArray)=TransferCipher.seal(context,key,clear);override fun unseal(context:ChunkContext,sealed:ByteArray)=TransferCipher.unseal(context,key,sealed)}
        var free=1024L*1024*1024
        var syncs=0
        fun open(sync:(Path)->Unit={p->java.nio.channels.FileChannel.open(p,java.nio.file.StandardOpenOption.READ).use{it.force(true)};syncs++})=EncryptedChunkFiles(base,crypto,freeBytes={free},syncDirectory=sync)
        val store=open()
        val folder get()=base.resolve(owner).resolve(record.id)
        val file get()=folder.resolve("chunk-0.enc")
        val context get()=ChunkContext(owner,record.id,bundle.manifest.manifest_id,0,bytes.size)
        override fun close(){root.toFile().deleteRecursively()}
    }
    @Test fun chunkCipherRoundTripAndRandomNonceDoNotExposePlaintext(){Fixture().use{f->val a=f.crypto.seal(f.context,f.bytes);val b=f.crypto.seal(f.context,f.bytes);assertFalse(a.contentEquals(b));assertFalse(a.toString(Charsets.ISO_8859_1).contains("PRIVATE_DOCUMENT_CONTENT"));assertArrayEquals(f.bytes,f.crypto.unseal(f.context,a))}}
    @Test fun allContextFieldsAreAuthenticated(){Fixture().use{f->val sealed=f.crypto.seal(f.context,f.bytes);listOf(f.context.copy(storeId=UUID.randomUUID().toString()),f.context.copy(transferId=UUID.randomUUID().toString()),f.context.copy(manifestId="b".repeat(64)),f.context.copy(index=1),f.context.copy(length=f.bytes.size-1)).forEach{expectFailure("LOCAL_COPY_CHANGED"){f.crypto.unseal(it,sealed)}}}}
    @Test fun tagTamperTruncationAndTrailingPayloadFailClosed(){Fixture().use{f->val data=f.crypto.seal(f.context,f.bytes);listOf(data.copyOf(data.size-1),data+byteArrayOf(0),data.clone().apply{this[lastIndex]=(this[lastIndex].toInt() xor 1).toByte()}).forEach{expectFailure("LOCAL_COPY_CHANGED"){f.crypto.unseal(f.context,it)}}}}
    @Test fun transferCiphertextCannotReplaceTheTrustAggregate(){Fixture().use{f->val data=f.crypto.seal(f.context,f.bytes);expectFailure("TRUST_STORE_INVALID"){TrustCipher.unseal(f.owner,f.key,data)};val trust=TrustCipher.seal(f.owner,f.key,f.bytes);expectFailure("LOCAL_COPY_CHANGED"){f.crypto.unseal(f.context,trust)}}}
    @Test fun encryptedFragmentSurvivesIndependentFilesystemReopen(){Fixture().use{f->f.store.reserve(f.owner,f.record);f.store.write(f.owner,f.record,0,f.bytes);assertTrue(f.syncs>=5);assertArrayEquals(f.bytes,f.open().read(f.owner,f.record,0));assertFalse(Files.readString(f.file,Charsets.ISO_8859_1).contains("PRIVATE_DOCUMENT_CONTENT"))}}
    @Test fun writeDoesNotReplaceAnInstalledCorruptFragment(){Fixture().use{f->f.store.reserve(f.owner,f.record);Files.write(f.file,byteArrayOf(1,2,3));expectFailure("LOCAL_COPY_CHANGED"){f.store.write(f.owner,f.record,0,f.bytes)};assertArrayEquals(byteArrayOf(1,2,3),Files.readAllBytes(f.file))}}
    @Test fun matchingInstalledFragmentIsIdempotent(){Fixture().use{f->f.store.reserve(f.owner,f.record);f.store.write(f.owner,f.record,0,f.bytes);val before=Files.readAllBytes(f.file);f.store.write(f.owner,f.record,0,f.bytes);assertArrayEquals(before,Files.readAllBytes(f.file))}}
    @Test fun knownUncheckpointedPartialStageCanBeDiscardedOnExplicitRetry(){Fixture().use{f->f.store.reserve(f.owner,f.record);Files.write(f.folder.resolve("chunk-0.pending"),byteArrayOf(1,2));f.store.write(f.owner,f.record,0,f.bytes);assertFalse(Files.exists(f.folder.resolve("chunk-0.pending")));assertArrayEquals(f.bytes,f.store.read(f.owner,f.record,0))}}
    @Test fun missingFragmentIsNotAClaimedCheckpoint(){Fixture().use{f->assertNull(f.store.read(f.owner,f.record,0));f.store.reserve(f.owner,f.record);assertNull(f.store.read(f.owner,f.record,0))}}
    @Test fun diskFullReserveAndWriteAreExplicitAndDoNotCreateContent(){Fixture().use{f->f.free=1;expectFailure("LOCAL_STORAGE_FULL"){f.store.reserve(f.owner,f.record)};assertFalse(Files.exists(f.base));f.free=1024L*1024*1024;f.store.reserve(f.owner,f.record);f.free=1;expectFailure("LOCAL_STORAGE_FULL"){f.store.write(f.owner,f.record,0,f.bytes)};assertFalse(Files.exists(f.file))}}
    @Test fun directorySyncFailureNeverReportsAPersistedChunk(){Fixture().use{f->f.store.reserve(f.owner,f.record);val failing=f.open{throw java.io.IOException("test fsync failure")};expectFailure("LOCAL_STORAGE_UNAVAILABLE"){failing.write(f.owner,f.record,0,f.bytes)};assertTrue(Files.exists(f.file));assertArrayEquals(f.bytes,f.open().read(f.owner,f.record,0))}}
    @Test fun symlinkFragmentsCannotReadOrOverwriteAnotherFile(){Fixture().use{f->f.store.reserve(f.owner,f.record);val outside=f.root.resolve("outside.txt");Files.writeString(outside,"unchanged");Files.createSymbolicLink(f.file,outside);expectFailure("LOCAL_COPY_CHANGED"){f.store.read(f.owner,f.record,0)};expectFailure("LOCAL_COPY_CHANGED"){f.store.write(f.owner,f.record,0,f.bytes)};expectFailure("LOCAL_COPY_CHANGED"){f.store.remove(f.owner,f.record)};assertEquals("unchanged",Files.readString(outside))}}
    @Test fun unsafeDirectoryAndHostileIdentifiersAreRejected(){Fixture().use{f->Files.createSymbolicLink(f.base,f.root);expectFailure("LOCAL_COPY_CHANGED"){f.store.reserve(f.owner,f.record)}};Fixture().use{f->expectFailure{f.store.reserve("../outside",f.record)};assertFalse(Files.exists(f.base))}}
    @Test fun cleanupPreservesUnrecognizedObjectsUntilReviewed(){Fixture().use{f->f.store.reserve(f.owner,f.record);f.store.write(f.owner,f.record,0,f.bytes);Files.writeString(f.folder.resolve("unrecognized"),"evidence");expectFailure("LOCAL_COPY_CHANGED"){f.store.remove(f.owner,f.record)};assertTrue(Files.exists(f.file));assertEquals("evidence",Files.readString(f.folder.resolve("unrecognized")))}}
    @Test fun explicitCleanupOnlyRemovesCanonicalOwnedFragments(){Fixture().use{f->f.store.reserve(f.owner,f.record);f.store.write(f.owner,f.record,0,f.bytes);val outside=f.root.resolve("keep.txt");Files.writeString(outside,"keep");f.store.remove(f.owner,f.record);assertFalse(Files.exists(f.folder));assertTrue(Files.exists(outside));f.store.remove(f.owner,f.record)}}
}
