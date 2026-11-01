package com.narada.connection

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.AlgorithmParameters
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.util.Date
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
object Certificates {
    fun parse(bytes: ByteArray, server: Boolean = false, now: Long? = System.currentTimeMillis()): X509Certificate {
        demand(bytes.isNotEmpty() && bytes.size <= 4096, "INVALID_CERTIFICATE")
        try {
            val stream = ByteArrayInputStream(bytes)
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(stream) as X509Certificate
            demand(stream.available() == 0 && cert.encoded.contentEquals(bytes), "INVALID_CERTIFICATE")
            if (now != null) cert.checkValidity(Date(now)); demand(cert.subjectX500Principal == cert.issuerX500Principal, "INVALID_CERTIFICATE")
            demand(!cert.hasUnsupportedCriticalExtension(), "INVALID_CERTIFICATE")
            cert.verify(cert.publicKey)
            val key = cert.publicKey as? ECPublicKey ?: fail("INVALID_CERTIFICATE")
            val expected = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
            demand(key.params.curve == expected.curve && key.params.generator == expected.generator && key.params.order == expected.order && key.params.cofactor == expected.cofactor, "INVALID_CERTIFICATE")
            demand(cert.sigAlgOID == "1.2.840.10045.4.3.2", "INVALID_CERTIFICATE")
            cert.keyUsage?.let { demand(it.isNotEmpty() && it[0], "INVALID_CERTIFICATE") }
            cert.extendedKeyUsage?.let { demand((if (server) "1.3.6.1.5.5.7.3.1" else "1.3.6.1.5.5.7.3.2") in it, "INVALID_CERTIFICATE") }
            if (server) demand(cert.subjectAlternativeNames?.any { it.size == 2 && it[0] == 2 && it[1] == "narada.local" } == true, "INVALID_CERTIFICATE")
            return cert
        } catch (failure: ConnectionFailure) { throw failure } catch (_: Exception) { fail("INVALID_CERTIFICATE") }
    }
    fun deviceId(cert: X509Certificate): String = sha256(cert.publicKey.encoded)
    fun verifyKey(identity: TransportIdentity) {
        val challenge = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        try {
            val signature = Signature.getInstance("SHA256withECDSA").run { initSign(identity.key); update(challenge); sign() }
            demand(Signature.getInstance("SHA256withECDSA").run { initVerify(identity.certificate.publicKey); update(challenge); verify(signature) }, "IDENTITY_CHANGED")
        } catch (failure: ConnectionFailure) { throw failure } catch (_: Exception) { fail("KEYSTORE_UNAVAILABLE") } finally { challenge.fill(0) }
    }
}
class TransportIdentity(val certificate: X509Certificate, val key: PrivateKey, val protection: String) {
    val fingerprint: String get() = sha256(certificate.encoded)
    val deviceId: String get() = Certificates.deviceId(certificate)
    override fun toString() = "TransportIdentity([private key redacted])"
}
data class PeerPin(val fingerprint: String, val deviceId: String) {
    init { validHash(fingerprint); validHash(deviceId) }
}

/** Exact out-of-band pin + current self-signature/profile + PKIX/hostname checks.
 * The TLS implementation still verifies CertificateVerify/private-key possession. */
class PinnedTrustManager(private val pin: PeerPin) : X509ExtendedTrustManager() {
    private fun delegate(chain: Array<X509Certificate>?): X509TrustManager {
        try {
            demand(chain != null && chain.size == 1, "IDENTITY_REJECTED")
            val cert = Certificates.parse(chain!![0].encoded, server = true)
            demand(MessageDigest.isEqual(sha256(cert.encoded).toByteArray(), pin.fingerprint.toByteArray()) && MessageDigest.isEqual(Certificates.deviceId(cert).toByteArray(), pin.deviceId.toByteArray()), "IDENTITY_REJECTED")
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("exact-pinned-peer", cert) }
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            return factory.trustManagers.filterIsInstance<X509TrustManager>().single()
        } catch (_: Exception) { throw CertificateException("NARADA_PIN_OR_CERTIFICATE_REJECTED") }
    }
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) { throw CertificateException("CLIENT_VALIDATION_NOT_SUPPORTED") }
    override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?, socket: Socket?) = checkClientTrusted(chain, authType)
    override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?, engine: SSLEngine?) = checkClientTrusted(chain, authType)
    override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) { delegate(chain).checkServerTrusted(chain, authType) }
    override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?, socket: Socket?) {
        val trust = delegate(chain)
        if (trust is X509ExtendedTrustManager) trust.checkServerTrusted(chain, authType, socket) else trust.checkServerTrusted(chain, authType)
    }
    override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?, engine: SSLEngine?) {
        val trust = delegate(chain)
        if (trust is X509ExtendedTrustManager) trust.checkServerTrusted(chain, authType, engine) else trust.checkServerTrusted(chain, authType)
    }
}
private class IdentityKeyManager(private val identity: TransportIdentity) : X509ExtendedKeyManager() {
    private fun suitable(types: Array<out String>?) = types?.any { it == "EC" || it.startsWith("EC_") } == true
    override fun getClientAliases(keyType: String?, issuers: Array<out java.security.Principal>?): Array<String>? = if (suitable(keyType?.let { arrayOf(it) })) arrayOf("narada") else null
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out java.security.Principal>?, socket: Socket?): String? = if (suitable(keyType)) "narada" else null
    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out java.security.Principal>?, engine: SSLEngine?): String? = if (suitable(keyType)) "narada" else null
    override fun getServerAliases(keyType: String?, issuers: Array<out java.security.Principal>?): Array<String>? = null
    override fun chooseServerAlias(keyType: String?, issuers: Array<out java.security.Principal>?, socket: Socket?): String? = null
    override fun getCertificateChain(alias: String?): Array<X509Certificate>? = if (alias == "narada") arrayOf(identity.certificate) else null
    override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == "narada") identity.key else null
}

/** Platform supplies an already network-bound, unconnected socket. No DNS/proxy fallback. */
fun interface SocketSource { fun create(endpoint: Endpoint): Socket }
interface PeerSession : Closeable {
    val certificate: ByteArray
    fun request(path: String, body: ByteArray): ByteArray
    fun getChunk(transferId: String, index: Int, expectedLength: Int): ByteArray = fail("NO_PEER_ROUTE")
    fun getBlobChunk(blobId: String, index: Int, expectedLength: Int): ByteArray = fail("NO_PEER_ROUTE")
    fun putAttachmentChunk(messageId: String, blobId: String, index: Int, bytes: ByteArray): ByteArray = fail("NO_PEER_ROUTE")
}
interface PeerConnector { fun connect(endpoint: Endpoint, pin: PeerPin, identity: TransportIdentity?): PeerSession; fun closeAll() }
class TlsConnector(private val sockets: SocketSource, private val configure: (SSLSocket) -> Unit = {}) : PeerConnector {
    private val lifecycle = Any()
    private val live = mutableSetOf<Socket>()
    private var generation = 0L
    override fun closeAll() {
        val closing = synchronized(lifecycle) { generation++; live.toList().also { live.clear() } }
        // Do not clear a newer generation's sockets after this snapshot was taken.
        closing.forEach { runCatching { it.close() } }
    }
    override fun connect(endpoint: Endpoint, pin: PeerPin, identity: TransportIdentity?): PeerSession {
        val turn = synchronized(lifecycle) { generation }
        val raw = sockets.create(endpoint)
        synchronized(lifecycle) {
            if (turn != generation) { raw.close(); fail("CONNECTION_INTERRUPTED") }
            live.add(raw)
        }
        fun remove() = synchronized(lifecycle) { live.remove(raw) }
        fun interrupted() = synchronized(lifecycle) { turn != generation }
        val expired = java.util.concurrent.atomic.AtomicBoolean(false)
        fun stop(code: String): Nothing { runCatching { raw.close() }; remove(); fail(if (interrupted()) "CONNECTION_INTERRUPTED" else if (expired.get()) "PEER_TIMEOUT" else code) }
        var tlsStarted = false
        val deadline = deadlines.schedule({ expired.set(true); runCatching { raw.close() } }, 9, TimeUnit.SECONDS)
        try {
            raw.tcpNoDelay = true; raw.soTimeout = 5000
            raw.connect(InetSocketAddress(endpoint.address(), endpoint.port), 4000)
            // A fresh context per connection prevents cross-connection session resumption.
            val context = SSLContext.getInstance("TLSv1.3")
            context.init(identity?.let { arrayOf<KeyManager>(IdentityKeyManager(it)) } ?: emptyArray(), arrayOf(PinnedTrustManager(pin)), java.security.SecureRandom())
            val socket = context.socketFactory.createSocket(raw, "narada.local", endpoint.port, true) as SSLSocket
            socket.useClientMode = true; socket.enabledProtocols = arrayOf("TLSv1.3"); socket.soTimeout = 5000
            socket.sslParameters = socket.sslParameters.apply {
                endpointIdentificationAlgorithm = "HTTPS"
                serverNames = listOf(SNIHostName("narada.local"))
                applicationProtocols = arrayOf("http/1.1")
            }
            configure(socket); tlsStarted = true; socket.startHandshake()
            demand(socket.session.protocol == "TLSv1.3" && socket.applicationProtocol == "http/1.1", "PROTOCOL_INCOMPATIBLE")
            demand(!interrupted(), "CONNECTION_INTERRUPTED")
            return HttpSession(socket, endpoint, { remove() })
        } catch (failure: ConnectionFailure) { stop(failure.code)
        } catch (_: SocketTimeoutException) { stop("PEER_TIMEOUT")
        } catch (_: SSLException) { stop("IDENTITY_REJECTED")
        } catch (_: Exception) { stop(if (tlsStarted) "PEER_AUTH_UNCONFIRMED" else "PEER_UNREACHABLE")
        } finally { deadline.cancel(false) }
    }
    companion object {
        internal val deadlines = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "narada-bounded-socket-deadline").apply { isDaemon = true } }.apply { removeOnCancelPolicy = true }
    }
}
private class HttpSession(private val socket: SSLSocket, private val endpoint: Endpoint, private val removed: () -> Unit) : PeerSession {
    override val certificate = (socket.session.peerCertificates.single() as X509Certificate).encoded
    private var confirmed = false
    override fun close() { runCatching { socket.session.invalidate() }; runCatching { socket.close() }; removed() }
    @Synchronized override fun request(path: String, body: ByteArray): ByteArray {
        demand(path in setOf("/pair/v1/claim", "/pair/v1/prove", "/pair/v1/ack", "/api/v1/hello", "/api/v1/health", "/api/v1/capabilities", FileRules.PATH, TransferRules.PATH, ChatRules.PATH, AttachmentRules.PATH, SyncRules.PATH), "NO_PEER_ROUTE")
        demand(body.size <= Wire.MAX_BODY, "BODY_TOO_LARGE")
        return exchange("POST", path, body, if (path == FileRules.PATH || path == SyncRules.PATH) FileRules.MAX_RESPONSE else Wire.MAX_BODY, null)
    }
    @Synchronized override fun getChunk(transferId: String, index: Int, expectedLength: Int): ByteArray {
        val path = TransferRules.chunkPath(transferId, index)
        demand(expectedLength in 1..TransferRules.CHUNK, "INVALID_CHUNK")
        return exchange("GET", path, byteArrayOf(), expectedLength, expectedLength)
    }
    @Synchronized override fun getBlobChunk(blobId: String, index: Int, expectedLength: Int): ByteArray {
        demand(expectedLength in 1..TransferRules.CHUNK, "INVALID_CHUNK")
        return exchange("GET", AttachmentRules.downloadPath(blobId, index), byteArrayOf(), expectedLength, expectedLength)
    }
    @Synchronized override fun putAttachmentChunk(messageId: String, blobId: String, index: Int, bytes: ByteArray): ByteArray {
        demand(bytes.size in 1..TransferRules.CHUNK, "INVALID_CHUNK")
        return exchange("PUT", AttachmentRules.uploadPath(messageId, blobId, index), bytes, Wire.MAX_BODY, null, upload = true)
    }
    private fun exchange(method: String, path: String, body: ByteArray, responseLimit: Int, binaryLength: Int?, upload: Boolean = false): ByteArray {
        val expired = java.util.concurrent.atomic.AtomicBoolean(false)
        val deadline = TlsConnector.deadlines.schedule({ expired.set(true); close() }, 8, TimeUnit.SECONDS)
        try {
            val headers = "$method $path HTTP/1.1\r\nHost: ${endpoint.authority}\r\nContent-Type: ${if (binaryLength != null || upload) "application/octet-stream" else "application/json"}\r\nContent-Length: ${body.size}\r\nX-Narada-Transfer-Version: 1\r\n${if (upload) "X-Narada-Upload-Version: 1\r\nX-Narada-Sha256: ${sha256(body)}\r\n" else ""}Cache-Control: no-store\r\n\r\n"
            socket.outputStream.write(headers.toByteArray(Charsets.US_ASCII)); socket.outputStream.write(body); socket.outputStream.flush()
            val input = socket.inputStream
            var headerBytes = 0
            fun line(): String {
                val bytes = ByteArrayOutputStream()
                while (true) {
                    val next = input.read(); if (next < 0) throw EOFException()
                    demand(++headerBytes <= 16384, "BODY_TOO_LARGE")
                    if (next == 13) { demand(input.read() == 10); headerBytes++; break }
                    demand(next in 32..126 || next == 9); bytes.write(next)
                }
                return bytes.toString(Charsets.US_ASCII.name())
            }
            val status = line().split(' ', limit = 3)
            demand(status.size >= 2 && status[0] == "HTTP/1.1" && status[1].matches(Regex("[1-5][0-9]{2}")))
            val code = status[1].toInt(); demand(code !in 300..399, "REDIRECT_REJECTED")
            val responseHeaders = mutableMapOf<String, String>()
            while (true) {
                val header = line(); if (header.isEmpty()) break
                val split = header.indexOf(':'); demand(split > 0)
                val name = header.substring(0, split).lowercase(java.util.Locale.ROOT)
                demand(name.matches(Regex("[a-z0-9-]+")) && !responseHeaders.containsKey(name))
                responseHeaders[name] = header.substring(split + 1).trim()
            }
            val binarySuccess = binaryLength != null && code == 200
            demand(responseHeaders["content-type"] == if (binarySuccess) "application/octet-stream" else "application/json")
            demand(!responseHeaders.containsKey("content-encoding"))
            val actualLimit = if (binaryLength != null && !binarySuccess) Wire.MAX_BODY else responseLimit
            val length = responseHeaders["content-length"]; val transfer = responseHeaders["transfer-encoding"]
            demand((length != null) != (transfer != null))
            if (binarySuccess) demand(length == binaryLength.toString() && transfer == null, "INVALID_CHUNK")
            val result = ByteArrayOutputStream()
            fun take(count: Int) {
                demand(count >= 0 && result.size() + count <= actualLimit, "BODY_TOO_LARGE")
                val buffer = ByteArray(minOf(8192, count.coerceAtLeast(1))); var remaining = count
                try { while (remaining > 0) { val n = input.read(buffer, 0, minOf(remaining, buffer.size)); if (n < 0) throw EOFException(); demand(n > 0); result.write(buffer, 0, n); remaining -= n } } finally { buffer.fill(0) }
            }
            if (length != null) {
                demand(length.matches(Regex("0|[1-9][0-9]{0,5}"))); take(length.toInt())
            } else {
                demand(transfer == "chunked")
                while (true) {
                    val chunk = line(); demand(chunk.matches(Regex("[0-9a-fA-F]{1,4}")))
                    val size = chunk.toInt(16)
                    if (size == 0) { demand(line().isEmpty()); break }
                    take(size); demand(input.read() == 13 && input.read() == 10)
                }
            }
            val bytes = result.toByteArray()
            if (binarySuccess) {
                try { demand(responseHeaders["x-narada-sha256"] == sha256(bytes), "HASH_MISMATCH") } catch (e: Exception) { bytes.fill(0); throw e }
                confirmed = true
                if (responseHeaders["connection"]?.equals("close", true) == true) close()
                return bytes
            }
            // Validate HTTP status against the envelope before exposing it to typed callers.
            val envelope = Wire.decode<Envelope<kotlinx.serialization.json.JsonElement>>(bytes, actualLimit)
            demand((envelope.ok && code == 200 && envelope.data != null && envelope.error == null) || (!envelope.ok && code in 400..599 && envelope.data == null && envelope.error != null))
            if (binaryLength != null) {
                try { if (path.startsWith("/api/v1/blobs/")) attachmentRemoteFailure(envelope.error?.code ?: "INVALID_HANDSHAKE") else transferRemoteFailure(envelope.error?.code ?: "INVALID_HANDSHAKE") } finally { bytes.fill(0) }
            }
            confirmed = true
            if (responseHeaders["connection"]?.equals("close", true) == true) close()
            return bytes
        } catch (failure: ConnectionFailure) { close(); throw failure
        } catch (_: SocketTimeoutException) { close(); fail("PEER_TIMEOUT")
        } catch (_: SSLException) { close(); fail(if (expired.get()) "PEER_TIMEOUT" else "IDENTITY_REJECTED")
        } catch (_: Exception) { close(); fail(if (expired.get()) "PEER_TIMEOUT" else if (confirmed) "CONNECTION_INTERRUPTED" else "PEER_AUTH_UNCONFIRMED")
        } finally { deadline.cancel(false) }
    }
}
inline fun <reified Request, reified Reply> PeerSession.post(path: String, value: Request): Reply {
    val encoded = Wire.encode(value)
    val response = try { request(path, encoded) } finally { encoded.fill(0) }
    val envelope = try { Wire.decode<Envelope<Reply>>(response, if (path == FileRules.PATH || path == SyncRules.PATH) FileRules.MAX_RESPONSE else Wire.MAX_BODY) } finally { response.fill(0) }
    demand(envelope.ok == (envelope.data != null) && envelope.ok == (envelope.error == null))
    envelope.error?.let { error ->
        val known = setOf("INVITATION_EXPIRED", "INVITATION_INVALID", "INVITATION_USED", "OWNER_APPROVAL_REQUIRED", "PROOF_REQUIRED", "PAIRING_IDENTITY_MISMATCH", "ALREADY_PAIRED", "PEER_LIMIT", "DEVICE_REVOKED", "PERMISSION_DENIED", "PROTOCOL_INCOMPATIBLE", "CAPABILITIES_INCOMPATIBLE", "RATE_LIMITED", "INVALID_HANDSHAKE", "IDENTITY_REJECTED", "NETWORK_DISABLED")
        if (path == AttachmentRules.PATH) attachmentRemoteFailure(error.code)
        if (path == SyncRules.PATH) syncRemoteFailure(error.code)
        if (path == ChatRules.PATH) chatRemoteFailure(error.code)
        if (path == TransferRules.PATH) transferRemoteFailure(error.code)
        if (path == FileRules.PATH) {
            if (error.code == "STORAGE_UNAVAILABLE") fail("FILE_REMOTE_STORAGE")
            if (error.code in fileErrorCodes) fail(error.code)
        }
        fail(if (error.code in known) error.code else "PEER_REJECTED")
    }
    return envelope.data ?: fail("INVALID_HANDSHAKE")
}

/** Only protocol codes, never server-supplied prose, reach UI or diagnostics. */
val fileErrorCodes = setOf("ACCESS_DENIED", "ROOT_UNAVAILABLE", "ROOT_NOT_FOUND", "OBJECT_UNAVAILABLE", "OBJECT_NOT_FOUND", "NOT_A_DIRECTORY", "INDEX_UNAVAILABLE", "CURSOR_STALE", "STALE_VERSION", "PREVIEW_UNSUPPORTED", "PREVIEW_LIMIT", "FILE_BUSY", "FILE_WORK_FAILED", "FILE_IO", "UNSAFE_OBJECT", "UNSAFE_ROOT", "UNSAFE_NAME", "EXCLUDED_NAME", "NAME_COLLISION", "DIRECTORY_NOT_EMPTY", "DIRECTORY_UNSUPPORTED", "TRASH_QUOTA", "TRASH_UNAVAILABLE", "TRASH_CHANGED", "OPERATION_INDETERMINATE", "OPERATION_NOT_APPLIED", "OPERATION_HISTORY_LIMIT", "IDEMPOTENCY_CONFLICT", "INVALID_QUERY", "INVALID_NAME", "BODY_TOO_LARGE", "TRANSFER_BUSY", "TRANSFER_RECOVERY_REQUIRED")

/** Never expose arbitrary peer prose or confuse remote storage with the local identity latch. */
fun transferRemoteFailure(code: String): Nothing = fail(when (code) {
    "STORAGE_UNAVAILABLE" -> "TRANSFER_REMOTE_STORAGE"
    "STORAGE_FULL" -> "TRANSFER_REMOTE_FULL"
    in transferErrorCodes, "DEVICE_REVOKED", "PERMISSION_DENIED", "RATE_LIMITED", "IDENTITY_REJECTED", "NETWORK_DISABLED" -> code
    else -> "PEER_REJECTED"
})

/** A peer's locked vault or crypto state is never the phone's protected-storage failure. */
fun chatRemoteFailure(code: String): Nothing = fail(when (code) {
    "STORAGE_UNAVAILABLE", "CONTENT_STORE_LOCKED", "CONTENT_STORE_MISSING", "CONTENT_IDENTITY_CHANGED", "CRYPTO_STATE_INVALID", "CHAT_STATE_INVALID" -> "CHAT_REMOTE_STORAGE"
    "CRYPTO_UNAVAILABLE" -> "CHAT_REMOTE_CRYPTO"
    in setOf("ACCESS_DENIED", "DEVICE_REVOKED", "PERMISSION_DENIED", "RATE_LIMITED", "IDENTITY_REJECTED", "NETWORK_DISABLED", "PROTOCOL_INCOMPATIBLE", "CAPABILITIES_INCOMPATIBLE", "INVALID_MESSAGE", "MESSAGE_TOO_LARGE", "MESSAGE_ID_CONFLICT", "CHAT_KEY_INVALID", "CHAT_KEY_CHANGED", "CHAT_SESSION_FAILED", "CHAT_SESSION_MISSING", "CHAT_SESSION_LIMIT", "CHAT_DECRYPT_FAILED", "CONTENT_QUOTA", "INVALID_RECEIPT", "MESSAGE_UNAVAILABLE", "SESSION_INVALIDATED") -> code
    else -> "PEER_REJECTED"
})
