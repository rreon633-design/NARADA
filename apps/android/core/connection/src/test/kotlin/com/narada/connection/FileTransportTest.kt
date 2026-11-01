package com.narada.connection

import java.net.Socket
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class FileTransportTest {
    private fun body(size:Int)="{\"ok\":true,\"data\":\"${"a".repeat(size)}\",\"error\":null}"
    private fun response(body:String,headers:String="Content-Length: ${body.length}\r\n")="HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n${headers}\r\n$body"
    private fun invoke(f:TlsFixture,path:String=FileRules.PATH,request:ByteArray?=null):JsonElement {
        val connector=TlsConnector(SocketSource { Socket() })
        try { return connector.connect(f.endpoint,PeerPin(f.identity.fingerprint,f.identity.deviceId),null).use {
            if(request!=null) { it.request(path,request);error("Request should fail") }
            it.post<Hello,JsonElement>(path,Hello())
        } } finally { connector.closeAll() }
    }
    @Test fun fileRouteAloneSupportsTheLargerBoundedResponse() {
        TlsFixture(reply=response(body(50_000))).use { assertEquals(50_000,invoke(it).jsonPrimitive.content.length) }
    }
    @Test fun healthStillRejectsResponsesAboveSixteenKiB() {
        TlsFixture(reply=response(body(20_000))).use { f->expectFailure("BODY_TOO_LARGE"){invoke(f,"/api/v1/health")} }
    }
    @Test fun fileContentLengthCannotExceedDesktopBound() {
        TlsFixture(reply=response("","Content-Length: 61025\r\n")).use { f->expectFailure("BODY_TOO_LARGE"){invoke(f)} }
    }
    @Test fun largeChunkedFileResponseRemainsBounded() {
        val data=body(50_000);val encoded=data.chunked(16_000).joinToString(""){"${it.length.toString(16)}\r\n$it\r\n"}+"0\r\n\r\n"
        TlsFixture(reply=response(encoded,"Transfer-Encoding: chunked\r\n")).use { assertEquals(50_000,invoke(it).jsonPrimitive.content.length) }
    }
    @Test fun cumulativeChunksCannotBypassTheFileLimit() {
        val data=body(61_025);val encoded=data.chunked(16_000).joinToString(""){"${it.length.toString(16)}\r\n$it\r\n"}+"0\r\n\r\n"
        TlsFixture(reply=response(encoded,"Transfer-Encoding: chunked\r\n")).use { f->expectFailure("BODY_TOO_LARGE"){invoke(f)} }
    }
    @Test fun ownerPathsAndArbitraryUrlSuffixesAreStillForbidden() {
        TlsFixture(reply=response(body(1))).use { f->expectFailure("NO_PEER_ROUTE"){invoke(f,"/api/v1/files/../owner")} }
    }
    @Test fun fileRequestsDoNotInheritTheLargerResponseLimit() {
        TlsFixture(reply=response(body(1))).use { f->expectFailure("BODY_TOO_LARGE"){invoke(f,request=ByteArray(16_385))};assertEquals(0,f.requests.get()) }
    }
    @Test fun jsonPreflightStillRunsForLargeAuthenticatedReplies() {
        val data=body(20_000).replace("\"ok\":true","\"ok\":true,\"ok\":false")
        TlsFixture(reply=response(data)).use { f->expectFailure("DUPLICATE_FIELD"){invoke(f)} }
    }
}
