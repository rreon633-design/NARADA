package com.narada.connection

import java.net.Socket
import org.junit.Assert.*
import org.junit.Test

class TransferTransportTest {
    private fun response(bytes:ByteArray,headers:String="",mime:String="application/octet-stream",status:String="200 OK",length:String=bytes.size.toString(),hash:String=sha256(bytes)):ByteArray =
        "HTTP/1.1 $status\r\nContent-Type: $mime\r\nContent-Length: $length\r\nX-Narada-Sha256: $hash\r\n${headers}\r\n".toByteArray()+bytes
    private fun invoke(reply:ByteArray,length:Int=17,action:(PeerSession)->ByteArray={it.getChunk(TransferSamples.id,0,length)}):ByteArray {
        TlsFixture(reply="",expectedMethod="GET",rawReply=reply).use { f ->
            val connector=TlsConnector(SocketSource{Socket()})
            try { return connector.connect(f.endpoint,PeerPin(f.identity.fingerprint,f.identity.deviceId),null).use { s -> action(s).also {
                assertEquals(1,f.requests.get());assertEquals("0",f.requestHeaders["content-length"]);assertEquals("1",f.requestHeaders["x-narada-transfer-version"]);assertEquals("GET ${TransferRules.PATH}/chunks/${TransferSamples.id}/0 HTTP/1.1",f.requestLine)
            } } } finally { connector.closeAll() }
        }
    }
    @Test fun binaryGetPreservesAllOctetsAndExactChunkBound(){val bytes=TransferSamples.bytes(TransferRules.CHUNK);assertArrayEquals(bytes,invoke(response(bytes),bytes.size))}
    @Test fun binaryHeaderDigestMustMatchTheReceivedBytes(){val bytes=TransferSamples.bytes(17);expectFailure("HASH_MISMATCH"){invoke(response(bytes,hash="b".repeat(64)))}}
    @Test fun oversizedOrShortDeclaredChunkIsRefusedBeforeBodyAllocation(){expectFailure("INVALID_CHUNK"){invoke(response(byteArrayOf(),length="262145"),TransferRules.CHUNK)};expectFailure("INVALID_CHUNK"){invoke(response(byteArrayOf(),length="16"),17)}}
    @Test fun binarySuccessCannotUseJsonOrCompressedContent(){expectFailure{invoke(response(TransferSamples.bytes(17),mime="application/json"))};expectFailure{invoke(response(TransferSamples.bytes(17),headers="Content-Encoding: gzip\r\n"))}}
    @Test fun transferFramingRejectsDuplicateLengthsAndChunkedSmuggling(){expectFailure{invoke(response(TransferSamples.bytes(17),headers="Content-Length: 17\r\n"))};expectFailure{invoke(response(TransferSamples.bytes(17),headers="Transfer-Encoding: chunked\r\n"))}}
    @Test fun binaryRouteStillRejectsRedirectsAndArbitraryIds(){expectFailure("REDIRECT_REJECTED"){invoke(response(byteArrayOf(),status="302 Found"))};expectFailure{invoke(response(TransferSamples.bytes(17)),action={it.getChunk("../owner",0,17)})}}
    @Test fun binaryErrorsUseBoundedTypedJsonNotUntrustedProse(){val data=Wire.encode(Envelope<kotlinx.serialization.json.JsonElement>(false,null,RemoteError("ACCESS_DENIED","PRIVATE server path")));expectFailure("ACCESS_DENIED"){invoke(response(data,mime="application/json",status="403 Forbidden"))}}
    @Test fun remoteStorageErrorsDoNotLatchThePhoneIdentity(){val data=Wire.encode(Envelope<kotlinx.serialization.json.JsonElement>(false,null,RemoteError("STORAGE_UNAVAILABLE","PRIVATE disk path")));expectFailure("TRANSFER_REMOTE_STORAGE"){invoke(response(data,mime="application/json",status="500 Error"))}}
    @Test fun binaryErrorsDoNotInheritTheQuarterMiBDataLimit(){val data="{\"ok\":false,\"data\":null,\"error\":{\"code\":\"ACCESS_DENIED\",\"message\":\"${"a".repeat(17000)}\"}}".toByteArray();expectFailure("BODY_TOO_LARGE"){invoke(response(data,mime="application/json",status="403 Forbidden"),TransferRules.CHUNK)}}
    @Test fun incompleteBinaryReplyNeverReturnsAPartialChunk(){expectFailure{invoke(response(byteArrayOf(1,2,3),length="17"))}}
}
