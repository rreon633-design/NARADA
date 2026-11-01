package com.narada.connection

import java.io.Closeable
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.*
import org.junit.Assert.*
import org.junit.Test

internal class TlsFixture(val identity:TransportIdentity=TestIdentities.create(true), private val reply:String, legacy:Boolean=false, private val trickle:Boolean=false, private val expectedMethod:String="POST", private val rawReply:ByteArray?=null, private val maxRequestBody:Int=16384) : Closeable {
    val requests=AtomicInteger()
    val requestHeaders=mutableMapOf<String,String>()
    var requestLine=""
        private set
    var requestBodyLength=0
        private set
    var requestBodySha256:String?=null
        private set
    val done=CountDownLatch(1)
    private val keys=KeyStore.getInstance("PKCS12").apply { load(null);setKeyEntry("host",identity.key,"test".toCharArray(),arrayOf(identity.certificate)) }
    private val factory=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys,"test".toCharArray()) }
    private val context=SSLContext.getInstance("TLS").apply { init(factory.keyManagers,null,null) }
    private val server=(context.serverSocketFactory.createServerSocket(0,1,InetAddress.getByName("127.0.0.1")) as SSLServerSocket).apply {
        enabledProtocols=arrayOf(if(legacy)"TLSv1.2" else "TLSv1.3");soTimeout=5000
        sslParameters=sslParameters.apply { applicationProtocols=arrayOf("http/1.1") }
    }
    val endpoint=Endpoint.parse("127.0.0.1:${server.localPort}")
    private var accepted:SSLSocket?=null
    private val thread=Thread {
        try { (server.accept() as SSLSocket).use { socket ->
            accepted=socket;socket.soTimeout=4000;socket.startHandshake()
            val input=socket.inputStream
            fun line():String { val out=java.io.ByteArrayOutputStream();while(true){val b=input.read();if(b<0)throw java.io.EOFException();if(b==13){check(input.read()==10);return out.toString()};check(out.size()<16384);out.write(b)} }
            val first=line();check(first.startsWith("$expectedMethod "));requestLine=first
            var size=0
            while(true){val line=line();if(line.isEmpty())break;requestHeaders[line.substringBefore(':').lowercase()]=line.substringAfter(':').trim();if(line.startsWith("Content-Length:"))size=line.substringAfter(':').trim().toInt()}
            check(size in 0..maxRequestBody); val digest=java.security.MessageDigest.getInstance("SHA-256")
            repeat(size){val b=input.read();check(b>=0);digest.update(b.toByte())};requestBodyLength=size;requestBodySha256=digest.digest().joinToString(""){"%02x".format(it)};requests.incrementAndGet()
            if(trickle) { reply.forEach { socket.outputStream.write(it.code);socket.outputStream.flush();Thread.sleep(400) } }
            else { socket.outputStream.write(rawReply ?: reply.toByteArray(Charsets.US_ASCII));socket.outputStream.flush() }
        } }catch(_:Exception){}finally{done.countDown()}
    }.apply { isDaemon=true;start() }
    override fun close(){runCatching{accepted?.close()};server.close();thread.interrupt();thread.join(5500)}
}
class TlsBoundaryTest {
    private val body="{\"ok\":true,\"data\":{\"hello\":{\"version\":1,\"capabilities\":[\"connection.health\",\"connection.permissions\"]},\"state\":\"awaiting_owner\",\"permissions\":{\"chat\":false,\"attachment\":false,\"roots\":[]}},\"error\":null}"
    private fun response(value:String=body,headers:String="Content-Length: ${value.length}\r\n",status:String="200 OK")="HTTP/1.1 $status\r\nContent-Type: application/json\r\n${headers}\r\n$value"
    private fun connector()=TlsConnector(SocketSource { Socket() })
    private fun invoke(f:TlsFixture):PairState=connector().let { client->try { client.connect(f.endpoint,PeerPin(f.identity.fingerprint,f.identity.deviceId),null).use { it.post<Hello,PairState>("/pair/v1/prove",Hello()) } }finally{client.closeAll()} }
    @Test fun socketCreationRacingWithCloseCannotStartAConnection() {
        val raw=Socket();lateinit var client:TlsConnector
        client=TlsConnector(SocketSource { client.closeAll();raw })
        val host=TestIdentities.create(true)
        expectFailure("CONNECTION_INTERRUPTED") { client.connect(Endpoint.parse("127.0.0.1:1"),PeerPin(host.fingerprint,host.deviceId),null) }
        assertTrue(raw.isClosed);assertFalse(raw.isConnected)
    }
    @Test fun absoluteDeadlineStopsANonIdleSlowTlsResponse() {
        TlsFixture(reply=response(),trickle=true).use { f ->
            val started=System.nanoTime()
            expectFailure("PEER_TIMEOUT") { invoke(f) }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<12000)
        }
    }
    @Test fun exactPinHostnameAndTls13CanExchangeBoundedJson(){TlsFixture(reply=response()).use { assertEquals("awaiting_owner",invoke(it).state);assertEquals(1,it.requests.get()) }}
    @Test fun wrongPinStopsBeforeAnyHttpBody(){TlsFixture(reply=response()).use { f->val client=connector();try{expectFailure("IDENTITY_REJECTED"){client.connect(f.endpoint,PeerPin("a".repeat(64),f.identity.deviceId),null).use{it.request("/pair/v1/claim",byteArrayOf(1))}}}finally{client.closeAll()};f.done.await(2,TimeUnit.SECONDS);assertEquals(0,f.requests.get()) }}
    @Test fun certificateWithoutServerSanIsRejectedBeforeToken(){TlsFixture(TestIdentities.create(),response()).use { f->expectFailure("IDENTITY_REJECTED"){invoke(f)};f.done.await(2,TimeUnit.SECONDS);assertEquals(0,f.requests.get()) }}
    @Test fun tls12IsNeverNegotiated(){TlsFixture(reply=response(),legacy=true).use { f->expectFailure("IDENTITY_REJECTED"){invoke(f)};assertEquals(0,f.requests.get()) }}
    @Test fun redirectsAreNeverFollowed(){TlsFixture(reply=response(status="302 Found")).use { f->expectFailure("REDIRECT_REJECTED"){invoke(f)} }}
    @Test fun oversizedContentLengthIsRejectedBeforeAllocation(){TlsFixture(reply=response("", "Content-Length: 65535\r\n")).use { f->expectFailure("BODY_TOO_LARGE"){invoke(f)} }}
    @Test fun duplicateLengthsAreRejected(){TlsFixture(reply=response(headers="Content-Length: ${body.length}\r\nContent-Length: ${body.length}\r\n")).use { f->expectFailure{invoke(f)} }}
    @Test fun lengthPlusChunkedIsRejected(){TlsFixture(reply=response(headers="Content-Length: ${body.length}\r\nTransfer-Encoding: chunked\r\n")).use { f->expectFailure{invoke(f)} }}
    @Test fun compressedResponsesAreRejected(){TlsFixture(reply=response(headers="Content-Length: ${body.length}\r\nContent-Encoding: gzip\r\n")).use { f->expectFailure{invoke(f)} }}
    @Test fun boundedChunkedJsonIsAccepted(){val encoded="${body.length.toString(16)}\r\n$body\r\n0\r\n\r\n";TlsFixture(reply=response(encoded,"Transfer-Encoding: chunked\r\n")).use { assertEquals("awaiting_owner",invoke(it).state) }}
    @Test fun chunkTrailersCannotSmuggleAnotherMessage(){val encoded="${body.length.toString(16)}\r\n$body\r\n0\r\nX-Extra: nope\r\n\r\n";TlsFixture(reply=response(encoded,"Transfer-Encoding: chunked\r\n")).use { f->expectFailure{invoke(f)} }}
    @Test fun httpSuccessCannotHideAnErrorEnvelope(){TlsFixture(reply=response("{\"ok\":false,\"data\":null,\"error\":{\"code\":\"PERMISSION_DENIED\",\"message\":\"secret remote text\"}}")).use { f->expectFailure("INVALID_HANDSHAKE"){invoke(f)} }}
    @Test fun duplicateJsonKeysInAuthenticatedBodyAreStillRejected(){TlsFixture(reply=response(body.replace("\"ok\":true","\"ok\":true,\"ok\":true"))).use { f->expectFailure("DUPLICATE_FIELD"){invoke(f)} }}
}
