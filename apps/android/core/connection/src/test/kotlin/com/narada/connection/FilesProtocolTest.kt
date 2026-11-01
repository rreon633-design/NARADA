package com.narada.connection

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class FilesProtocolTest {
    @Test fun rootsAndAllTypedRepliesValidateAgainstActualGrants() {
        RootsReply(listOf(FileSamples.root),1).validate(FileSamples.grants)
        FileSamples.page.validate(FileQuery.Browse(FileSamples.rootId),FileSamples.grants)
        FileSamples.entry.validate()
        TrashReply(FileSamples.root,listOf(FileSamples.trash),0,null,"Retained").validate(FileQuery.Trash(FileSamples.rootId),FileSamples.grants)
    }
    @Test fun requestsContainOpaqueIdsAndNeverDisplayPaths() {
        val requests=listOf(FileQuery.Roots,FileQuery.Browse(FileSamples.rootId,FileSamples.directoryId,FileSamples.cursor),FileQuery.Search(FileSamples.rootId,"photo"),FileQuery.Details(FileSamples.rootId,FileSamples.objectId),FileQuery.Preview(FileSamples.rootId,FileSamples.objectId,FileSamples.version),FileQuery.Trash(FileSamples.rootId))
        requests.forEach { assertFalse(it.payload().toString().contains("relative_path"));assertFalse(it.payload().toString().contains("content://")) }
        assertEquals(JsonNull,FileQuery.Browse(FileSamples.rootId).payload()["directory_id"])
        assertEquals("name_desc",FileQuery.Search(FileSamples.rootId,"a",sort=FileSort.NAME_DESC).payload()["sort"]!!.jsonPrimitive.content)
    }
    @Test fun hostileObjectAndRootIdentifiersFailLocally() {
        listOf("../secret", "C:\\private", "content://provider/document/2", "https://host/file", "1-1-1-1-1", "11111111-1111-4111-8111-11111111111Z").forEach { bad ->
            expectFailure("INVALID_FILE_ID") { FileQuery.Browse(bad).payload() };expectFailure("INVALID_FILE_ID") { FileQuery.Details(FileSamples.rootId,bad).payload() }
        }
    }
    @Test fun searchIsUtf8ByteBoundedNotCharacterCounted() {
        FileQuery.Search(FileSamples.rootId,"é".repeat(64)).payload()
        listOf("", " ", "é".repeat(65), "abc\n", "a\u202eb").forEach { expectFailure { FileQuery.Search(FileSamples.rootId,it).payload() } }
    }
    @Test fun versionsAreCanonicalFullHashes() { listOf("a", "A".repeat(64), "a".repeat(63)+"/", "\"".repeat(64)).forEach { expectFailure("INVALID_FILE_VERSION") { FileQuery.Preview(FileSamples.rootId,FileSamples.objectId,it).payload() } } }
    @Test fun namesRejectTraversalControlsDevicesAndNonNormalizedUnicode() {
        listOf("../x", "a/b", "a\\b", "C:photo", "a\u0000b", "CON.txt", "COM1", "COM¹.txt", "LPT9.log", "\ud800", "photo.", "photo ", "a\u202eb", "e\u0301.jpg").forEach { expectFailure { FileRules.name(it) } }
        FileRules.newName("తెలుగు చిత్రము.jpg");FileRules.newName("Café.jpg")
    }
    @Test fun hiddenAndReservedDestinationNamesAreRefusedBeforeIntent() { listOf(".secret","desktop.ini","THUMBS.DB","$"+"Recycle.Bin","System Volume Information").forEach { expectFailure("INVALID_FILE_NAME") { FileAction("rename",it).validate() } } }
    @Test fun mutationWireShapeMatchesRustTaggedAction() {
        val rename=FileSamples.input().payload();assertEquals("rename",rename["action"]!!.jsonObject["kind"]!!.jsonPrimitive.content)
        val trash=FileSamples.input("trash").payload();assertFalse(trash["action"]!!.jsonObject.containsKey("name"))
        val restore=FileSamples.input("restore").payload();assertEquals(JsonNull,restore["action"]!!.jsonObject["name"])
        expectFailure { FileAction("delete").validate() };expectFailure { FileAction("trash","x").validate() }
    }
    @Test fun ownerOnlyRootFieldsAreNotAcceptedAsPeerData() {
        val value=Wire.json.encodeToJsonElement(FileSamples.root).jsonObject
        val contaminated=JsonObject(value+mapOf("path" to JsonPrimitive("C:\\secret")))
        expectFailure { Wire.decode<RemoteRoot>(contaminated.toString().toByteArray()) }
    }
    @Test fun duplicateEscapedKeysAndScalarCoercionAreRejected() {
        val text=String(Wire.encode(FileSamples.root))
        expectFailure("DUPLICATE_FIELD") { Wire.decode<RemoteRoot>(text.replace("\"revision\":1", "\"revision\":1,\"revis\\u0069on\":2").toByteArray()) }
        expectFailure { Wire.decode<RemoteRoot>(text.replace("\"revision\":1","\"revision\":\"1\"").toByteArray()) }
    }
    @Test fun ungrantedOrAlteredRootCapabilitiesFailClosed() {
        expectFailure("FILE_ACCESS_CHANGED") { FileSamples.root.validate(Permissions()) }
        expectFailure("FILE_ACCESS_CHANGED") { FileSamples.root.copy(capabilities=listOf("browse")).validate(FileSamples.grants) }
        expectFailure { FileSamples.root.copy(capabilities=listOf("browse","read","read")).validate(FileSamples.grants) }
    }
    @Test fun rootAndEntryCollectionsAreBoundedAndUnique() {
        expectFailure { RootsReply(List(9){FileSamples.root},1).validate(FileSamples.grants) }
        expectFailure { FileSamples.page.copy(entries=List(17){FileSamples.entry}).validate(FileQuery.Browse(FileSamples.rootId),FileSamples.grants) }
        expectFailure { FileSamples.page.copy(entries=listOf(FileSamples.entry,FileSamples.entry)).validate(FileQuery.Browse(FileSamples.rootId),FileSamples.grants) }
    }
    @Test fun hostileOrForeignBreadcrumbsNeverBecomeNavigation() {
        expectFailure { FileSamples.page.copy(breadcrumbs=listOf(Breadcrumb("Other",null))).validate(FileQuery.Browse(FileSamples.rootId),FileSamples.grants) }
        expectFailure { FileSamples.page.copy(breadcrumbs=listOf(Breadcrumb("Shared",null),Breadcrumb("..",FileSamples.directoryId))).validate(FileQuery.Browse(FileSamples.rootId,FileSamples.directoryId),FileSamples.grants) }
        expectFailure { FileSamples.page.copy(breadcrumbs=listOf(Breadcrumb("Shared",null))).validate(FileQuery.Browse(FileSamples.rootId,FileSamples.directoryId),FileSamples.grants) }
    }
    @Test fun listingEpochParentAndRootMustMatchRequestedScope() {
        expectFailure { FileSamples.page.copy(index_epoch="2").validate(FileQuery.Browse(FileSamples.rootId),FileSamples.grants) }
        expectFailure { FileSamples.page.validate(FileQuery.Browse(FileSamples.directoryId),FileSamples.grants) }
        expectFailure { FileSamples.page.copy(entries=listOf(FileSamples.entry.copy(parent_id=FileSamples.directoryId))).validate(FileQuery.Browse(FileSamples.rootId),FileSamples.grants) }
    }
    @Test fun displayPathsAreBoundedAndNeverAcceptedAsAbsoluteOrEscapedPaths() {
        listOf("/sample.jpg","C:/sample.jpg","../sample.jpg","folder/../sample.jpg",List(33){"x"}.joinToString("/")+"/sample.jpg").forEach { expectFailure { FileSamples.entry.copy(relative_path=it).validate() } }
        expectFailure { FileSamples.entry.copy(relative_path="other.jpg").validate() }
    }
    @Test fun unsignedSizesDatesAndKindsAreStrict() {
        FileSamples.entry.copy(size="18446744073709551615").validate()
        listOf("-1","001","18446744073709551616","1.5").forEach { expectFailure { FileSamples.entry.copy(size=it).validate() } }
        expectFailure { FileSamples.entry.copy(modified="yesterday").validate() };expectFailure { FileSamples.entry.copy(kind="symlink").validate() }
    }
    @Test fun previewAcceptsOnlyBoundedBaselineJpegPixels() {
        val p=FileSamples.preview().pixels(FileQuery.Preview(FileSamples.rootId,FileSamples.objectId,FileSamples.version))
        assertEquals(20,p.width);assertEquals(14,p.height)
        val clone=p.encodedCopy();clone.fill(0);assertFalse(p.encodedCopy().all { it==0.toByte() })
    }
    @Test fun previewRejectsSvgPngPolyglotsAndTrailingBytes() {
        listOf("<svg onload='x'/>".toByteArray(),byteArrayOf(0x89.toByte(),0x50,0x4e,0x47),FileSamples.jpeg()+"<script>".toByteArray()).forEach { expectFailure("INVALID_PREVIEW") { JpegBounds.validate(it,20,14) } }
    }
    @Test fun previewRejectsMetadataAndInconsistentDimensions() {
        val jpeg=FileSamples.jpeg();val metadata=jpeg.copyOfRange(0,2)+byteArrayOf(0xff.toByte(),0xe1.toByte(),0,4,1,2)+jpeg.copyOfRange(2,jpeg.size)
        expectFailure("INVALID_PREVIEW") { JpegBounds.validate(metadata,20,14) }
        expectFailure("INVALID_PREVIEW") { JpegBounds.validate(jpeg,21,14) }
        expectFailure("INVALID_PREVIEW") { JpegBounds.validate(FileSamples.jpeg(257,14),257,14) }
    }
    @Test fun previewRejectsNoncanonicalBase64WrongMimeAndWrongVersion() {
        val q=FileQuery.Preview(FileSamples.rootId,FileSamples.objectId,FileSamples.version)
        expectFailure("INVALID_PREVIEW") { FileSamples.preview().copy(data="!!!!").pixels(q) }
        expectFailure("INVALID_PREVIEW") { FileSamples.preview().copy(mime="image/svg+xml").pixels(q) }
        expectFailure("STALE_VERSION") { FileSamples.preview().copy(version="b".repeat(64)).pixels(q) }
        expectFailure("INVALID_PREVIEW") { FileSamples.preview().copy(data=Base64.getEncoder().encodeToString(ByteArray(32769))).pixels(q) }
    }
    @Test fun malformedJpegSegmentLengthAndTruncationCannotReachDecoder() {
        val jpeg=FileSamples.jpeg()
        listOf(jpeg.copyOf(jpeg.size-2), jpeg.copyOf().apply { this[4]=0x7f;this[5]=0x7f }).forEach { expectFailure("INVALID_PREVIEW") { JpegBounds.validate(it,20,14) } }
    }
    @Test fun receiptBindsOperationActionObjectAndTrashId() {
        val input=FileSamples.input();val receipt=FileSamples.receipt(input);receipt.validate(input)
        expectFailure("INVALID_FILE_RECEIPT") { receipt.copy(operation_id=FileSamples.cursor).validate(input) }
        expectFailure("INVALID_FILE_RECEIPT") { receipt.copy(object_id=FileSamples.directoryId).validate(input) }
        expectFailure("INVALID_FILE_RECEIPT") { receipt.copy(state="queued").validate(input) }
        val trash=FileSamples.input("trash");FileSamples.receipt(trash).validate(trash)
        expectFailure("INVALID_FILE_RECEIPT") { FileSamples.receipt(trash).copy(trash_id=null).validate(trash) }
    }
    @Test fun restoreReceiptReturnsOriginalObjectNotTrashRecordIdentifier() {
        val restore=FileSamples.input("restore").copy(objectId=FileSamples.cursor)
        FileSamples.receipt(restore).validate(restore)
        expectFailure("INVALID_FILE_RECEIPT") { FileSamples.receipt(restore).copy(trash_id=restore.operationId).validate(restore) }
    }
    @Test fun journalNeverRepresentsDismissedOrUnknownAsCommitted() {
        val input=FileSamples.input();val operation=FileOperation("a".repeat(64),FileSamples.cursor,input,1)
        operation.validate();operation.copy(state=FileOperationState.DISMISSED_UNCONFIRMED).validate()
        expectFailure("TRUST_STORE_INVALID") { operation.copy(state=FileOperationState.COMMITTED).validate() }
        expectFailure("TRUST_STORE_INVALID") { operation.copy(receipt=FileSamples.receipt(input)).validate() }
    }
    @Test fun duplicateOrExtendedJfifSegmentsCannotCarryAncillaryMetadata() {
        val jpeg=FileSamples.jpeg();val duplicate=jpeg.copyOfRange(0,20)+jpeg.copyOfRange(2,20)+jpeg.copyOfRange(20,jpeg.size)
        expectFailure("INVALID_PREVIEW"){JpegBounds.validate(duplicate,20,14)}
        val extended=jpeg.copyOfRange(0,20)+byteArrayOf(1,2)+jpeg.copyOfRange(20,jpeg.size)
        extended[5]=18;expectFailure("INVALID_PREVIEW"){JpegBounds.validate(extended,20,14)}
    }

}
