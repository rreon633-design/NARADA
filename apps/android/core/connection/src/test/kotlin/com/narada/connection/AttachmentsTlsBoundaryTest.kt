package com.narada.connection

import java.net.Socket
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Linux JSSE framing tests with disposable identities, NOT Android Conscrypt/network proof. */
class AttachmentsTlsBoundaryTest {
    private val message = UUID.randomUUID().toString()
    private val blob = UUID.randomUUID().toString()
    private val json = "{\"ok\":true,\"data\":{},\"error\":null}"
    private fun jsonReply(status: String = "200 OK", headers: String = "Content-Length: ${json.length}\r\n") = "HTTP/1.1 $status\r\nContent-Type: application/json\r\n$headers\r\n$json"
    private fun <T> use(f: TlsFixture, block: (PeerSession) -> T): T {
        val c = TlsConnector(SocketSource { Socket() })
        return try { c.connect(f.endpoint, PeerPin(f.identity.fingerprint, f.identity.deviceId), null).use(block) } finally { c.closeAll() }
    }
    private fun binary(bytes: ByteArray, hash: String = sha256(bytes), length: Int = bytes.size, extra: String = ""): ByteArray =
        "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: $length\r\nX-Narada-Sha256: $hash\r\n$extra\r\n".toByteArray() + bytes

    @Test fun putUsesBoundedOpaqueBytesExactVersionHashAndRoute() {
        val bytes = TransferSamples.bytes(TransferRules.CHUNK)
        TlsFixture(reply = jsonReply(), expectedMethod = "PUT", maxRequestBody = TransferRules.CHUNK).use { f ->
            assertArrayEquals(json.toByteArray(), use(f) { it.putAttachmentChunk(message, blob, 32, bytes) })
            assertEquals("PUT /api/v1/attachment-upload/chunks/$message/$blob/32 HTTP/1.1", f.requestLine)
            assertEquals("application/octet-stream", f.requestHeaders["content-type"]); assertEquals("1", f.requestHeaders["x-narada-upload-version"])
            assertEquals(bytes.size, f.requestBodyLength); assertEquals(sha256(bytes), f.requestBodySha256); assertEquals(sha256(bytes), f.requestHeaders["x-narada-sha256"])
            assertEquals(setOf("host", "content-type", "content-length", "x-narada-transfer-version", "x-narada-upload-version", "x-narada-sha256", "cache-control"), f.requestHeaders.keys)
        }
    }
    @Test fun invalidPutSizeAndIdentifiersAreRejectedBeforeHttpBody() {
        val cases: List<(PeerSession) -> ByteArray> = listOf(
            { it.putAttachmentChunk(message, blob, 0, byteArrayOf()) },
            { it.putAttachmentChunk(message, blob, 0, ByteArray(TransferRules.CHUNK + 1)) },
            { it.putAttachmentChunk(message, blob, 33, byteArrayOf(1)) },
            { it.putAttachmentChunk("../escape", blob, 0, byteArrayOf(1)) },
        )
        cases.forEach { call -> TlsFixture(reply = jsonReply(), expectedMethod = "PUT").use { f ->
            expectFailure { use(f, call) }; f.done.await(2, TimeUnit.SECONDS); assertEquals(0, f.requests.get())
        } }
    }
    @Test fun putRejectsRedirectDuplicateLengthCompressionAndStatusEnvelopeMismatch() {
        val responses = listOf(jsonReply("302 Found"), jsonReply(headers = "Content-Length: ${json.length}\r\nContent-Length: ${json.length}\r\n"), jsonReply(headers = "Content-Length: ${json.length}\r\nContent-Encoding: gzip\r\n"), jsonReply("403 Forbidden"))
        responses.forEach { response -> TlsFixture(reply = response, expectedMethod = "PUT").use { f -> expectFailure { use(f) { it.putAttachmentChunk(message, blob, 0, byteArrayOf(1)) } }; assertEquals(1, f.requests.get()) } }
    }
    @Test fun blobGetRequiresExactLengthAndCiphertextHash() {
        val bytes = TransferSamples.bytes(16385)
        TlsFixture(reply = "", expectedMethod = "GET", rawReply = binary(bytes)).use { f ->
            assertArrayEquals(bytes, use(f) { it.getBlobChunk(blob, 1, bytes.size) })
            assertEquals("GET /api/v1/blobs/chunks/$blob/1 HTTP/1.1", f.requestLine); assertEquals(0, f.requestBodyLength)
        }
    }
    @Test fun blobGetRejectsHashMismatchTruncationDuplicateHashAndCompression() {
        val bytes = TransferSamples.bytes(97)
        val replies = listOf(binary(bytes, "f".repeat(64)), binary(bytes.copyOf(96), length = 97), binary(bytes, extra = "X-Narada-Sha256: ${sha256(bytes)}\r\n"), binary(bytes, extra = "Content-Encoding: gzip\r\n"))
        replies.forEach { response -> TlsFixture(reply = "", expectedMethod = "GET", rawReply = response).use { f -> expectFailure { use(f) { it.getBlobChunk(blob, 0, bytes.size) } } } }
    }
    @Test fun blobGetRejectsChunkedBinaryAndDeclaredOversizeBeforeAllocation() {
        val bytes = byteArrayOf(1)
        val chunked = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nTransfer-Encoding: chunked\r\nX-Narada-Sha256: ${sha256(bytes)}\r\n\r\n1\r\nx\r\n0\r\n\r\n".toByteArray()
        for (response in listOf(chunked, binary(bytes, length = 999999))) TlsFixture(reply = "", expectedMethod = "GET", rawReply = response).use { f -> expectFailure("INVALID_CHUNK") { use(f) { it.getBlobChunk(blob, 0, 1) } } }
    }
    @Test fun blobErrorsExposeOnlyApprovedCodeNotRemoteDetails() {
        val body = "{\"ok\":false,\"data\":null,\"error\":{\"code\":\"ACCESS_DENIED\",\"message\":\"untrusted remote PRIVATE SENTINEL\"}}"
        val response = "HTTP/1.1 403 Forbidden\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\n\r\n$body"
        TlsFixture(reply = response, expectedMethod = "GET").use { f ->
            try { use(f) { it.getBlobChunk(blob, 0, 1) }; fail("Expected rejection") }
            catch (e: ConnectionFailure) { assertEquals("ACCESS_DENIED", e.code); assertFalse(e.toString().contains("SENTINEL")) }
        }
    }
}
