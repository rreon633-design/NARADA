package com.narada.connection

import java.net.InetAddress
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import kotlinx.serialization.serializer
import kotlinx.serialization.descriptors.*

class ConnectionFailure(val code: String) : Exception(code) {
    override fun toString(): String = "ConnectionFailure($code)"
}
fun fail(code: String): Nothing = throw ConnectionFailure(code)
fun demand(condition: Boolean, code: String = "INVALID_HANDSHAKE") { if (!condition) fail(code) }

object Wire {
    const val MAX_BODY = 16_384
    const val MAX_CODE = 4096
    const val MAX_STORE = 2_097_152
    val json = Json { ignoreUnknownKeys = false; isLenient = false; coerceInputValues = false; encodeDefaults = true; explicitNulls = true }
    inline fun <reified T> decode(bytes: ByteArray, limit: Int = MAX_BODY): T {
        demand(bytes.size <= limit, "BODY_TOO_LARGE")
        val text = try { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString() } catch (_: Exception) { fail("INVALID_HANDSHAKE") }
        StrictJson.check(text, if (limit == MAX_STORE) 65_536 else 4096)
        return try { val element = json.parseToJsonElement(text); StrictShape.check(element, serializer<T>().descriptor); json.decodeFromJsonElement<T>(element) } catch (failure: ConnectionFailure) { throw failure } catch (_: Exception) { fail("INVALID_HANDSHAKE") }
    }
    inline fun <reified T> encode(value: T, limit: Int = MAX_BODY): ByteArray = json.encodeToString(value).toByteArray(Charsets.UTF_8).also { demand(it.size <= limit, "BODY_TOO_LARGE") }
}

/** Preflight rejects duplicate (including escaped) keys and deep/oversized collections.
 * Kotlin serialization remains the authoritative syntax/type/unknown-field validator. */
object StrictJson {
    fun check(text: String, maxNodes: Int = 4096) {
        var pos = 0; var nodes = 0
        fun whitespace() { while (pos < text.length && text[pos] in " \r\n\t") pos++ }
        fun string(): String {
            val start = pos
            demand(pos < text.length && text[pos++] == '"')
            var escaped = false
            while (pos < text.length) {
                val ch = text[pos++]
                if (!escaped && ch == '"') return try { Wire.json.decodeFromString<String>(text.substring(start, pos)) } catch (_: Exception) { fail("INVALID_HANDSHAKE") }
                if (ch == '\\' && !escaped) escaped = true else escaped = false
            }
            fail("INVALID_HANDSHAKE")
        }
        fun value(depth: Int): Unit {
            demand(depth <= 12 && ++nodes <= maxNodes)
            whitespace(); demand(pos < text.length)
            when (text[pos]) {
                '{' -> {
                    pos++; whitespace(); val keys = mutableSetOf<String>()
                    if (pos < text.length && text[pos] == '}') { pos++; return }
                    while (true) {
                        whitespace(); val key = string(); demand(keys.add(key), "DUPLICATE_FIELD")
                        whitespace(); demand(pos < text.length && text[pos++] == ':'); value(depth + 1); whitespace()
                        demand(pos < text.length); val next = text[pos++]; if (next == '}') break; demand(next == ',')
                    }
                }
                '[' -> {
                    pos++; whitespace()
                    if (pos < text.length && text[pos] == ']') { pos++; return }
                    while (true) { value(depth + 1); whitespace(); demand(pos < text.length); val next = text[pos++]; if (next == ']') break; demand(next == ',') }
                }
                '"' -> { string() }
                else -> { val start = pos; while (pos < text.length && text[pos] !in ",]} \t\r\n") pos++; demand(pos > start) }
            }
        }
        value(0); whitespace(); demand(pos == text.length)
    }
}

/** Serialization must not coerce quoted numbers/booleans into Rust's strict wire types. */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
object StrictShape {
    fun check(value: JsonElement, descriptor: SerialDescriptor) {
        if (value is JsonNull) { demand(descriptor.isNullable); return }
        if (descriptor.serialName.startsWith("kotlinx.serialization.json.")) return
        when (descriptor.kind) {
            PrimitiveKind.STRING, PrimitiveKind.CHAR, SerialKind.ENUM -> demand(value is JsonPrimitive && value.isString)
            PrimitiveKind.BOOLEAN -> demand(value is JsonPrimitive && !value.isString && value.content in setOf("true", "false"))
            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> demand(value is JsonPrimitive && !value.isString && value.content.matches(Regex("-?(0|[1-9][0-9]*)")))
            StructureKind.LIST -> { demand(value is JsonArray); (value as JsonArray).forEach { check(it, descriptor.getElementDescriptor(0)) } }
            StructureKind.CLASS, StructureKind.OBJECT -> {
                demand(value is JsonObject)
                (value as JsonObject).forEach { (key, child) ->
                    val index = descriptor.getElementIndex(key); demand(index >= 0)
                    check(child, descriptor.getElementDescriptor(index))
                }
            }
            else -> fail("INVALID_HANDSHAKE")
        }
    }
}

@Serializable data class Hello(val version: Int = 1, val capabilities: List<String> = listOf("connection.health", "connection.permissions")) {
    fun validate() {
        demand(version == 1, "PROTOCOL_INCOMPATIBLE")
        demand(capabilities.size in 2..16 && capabilities.distinct().size == capabilities.size && capabilities.all { it.length <= 64 && it.matches(Regex("[a-z.]+")) } && capabilities.containsAll(listOf("connection.health", "connection.permissions")), "CAPABILITIES_INCOMPATIBLE")
    }
}
@Serializable data class RootGrant(val root_id: String, val capabilities: List<String>)
@Serializable data class Permissions(val chat: Boolean = false, val attachment: Boolean = false, val roots: List<RootGrant> = emptyList()) {
    fun validate() {
        demand(roots.size <= 8 && roots.map { it.root_id }.distinct().size == roots.size, "INVALID_GRANTS")
        roots.forEach { root ->
            canonicalUuid(root.root_id)
            demand(root.capabilities.size <= 6 && root.capabilities.distinct().size == root.capabilities.size && root.capabilities.all { it in setOf("browse", "read", "create", "modify", "trash", "sync_read") }, "INVALID_GRANTS")
        }
    }
}
@Serializable class PairCode(
    val format: String, val version: Int, val invitation_id: String, val token: String,
    val endpoint: String, val fingerprint: String, val device_id: String, val name: String, val expires_at: String,
) {
    override fun toString() = "PairCode([redacted])"
    fun validate(now: Long = System.currentTimeMillis()): PairCode {
        demand(format == "narada-pairing" && version == 1, "PROTOCOL_INCOMPATIBLE")
        canonicalUuid(invitation_id); validHash(token); validHash(fingerprint); validHash(device_id); validName(name); Endpoint.parse(endpoint)
        val remaining = expiryMillis() - now
        demand(remaining > 0, "INVITATION_EXPIRED"); demand(remaining <= 150_000, "CLOCK_SKEW")
        return this
    }
    fun expiryMillis(): Long = try { Instant.parse(expires_at).toEpochMilli() } catch (_: Exception) { fail("INVALID_PAIRING_CODE") }
    companion object { fun parse(text: String, now: Long = System.currentTimeMillis()): PairCode = Wire.decode<PairCode>(text.toByteArray(), Wire.MAX_CODE).validate(now) }
}
@Serializable class Claim(val hello: Hello = Hello(), val invitation_id: String, val attempt_id: String, val token: String, val certificate: String, val name: String, val platform: String = "android") {
    override fun toString() = "Claim([redacted])"
}
@Serializable data class ClaimReply(val hello: Hello, val attempt_id: String, val api_port: Int, val name: String, val platform: String, val certificate: String)
@Serializable data class Proof(val hello: Hello = Hello(), val invitation_id: String, val attempt_id: String)
@Serializable data class PairState(val hello: Hello, val state: String, val permissions: Permissions)
@Serializable data class Health(val hello: Hello, val device_id: String, val permissions: Permissions, val grant_revision: Long, val session_epoch: Long, val operations: List<String>) {
    fun validate(expected: String) {
        hello.validate(); demand(device_id == expected, "IDENTITY_REJECTED"); permissions.validate()
        demand(grant_revision in 1..0xffffffffL && session_epoch in 1..0xffffffffL)
        demand(operations in listOf(listOf("health", "permissions"), listOf("health", "permissions", "files.v1"), listOf("health", "permissions", "files.v1", "transfers.v1"), listOf("health", "permissions", "files.v1", "transfers.v1", "chat.olm.v1", "attachments.age.v1", "sync.v1")), "CAPABILITIES_INCOMPATIBLE")
    }
}
@Serializable data class RemoteError(val code: String, val message: String)
@Serializable data class Envelope<T>(val ok: Boolean, val data: T?, val error: RemoteError?)
fun validHash(value: String) { demand(value.matches(Regex("[0-9a-f]{64}")), "INVALID_IDENTITY") }
fun canonicalUuid(value: String) { demand(value.length == 36 && runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)) }
fun validName(value: String) { demand(value.isNotBlank() && value.codePointCount(0, value.length) <= 48 && value.none { it.isISOControl() || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' }) }
fun validPlatform(value: String) { demand(value in setOf("windows", "linux", "macos", "android")) }

data class Endpoint(val host: String, val port: Int) {
    val authority: String get() = "$host:$port"
    fun address(): InetAddress = InetAddress.getByAddress(host.split('.').map { it.toInt().toByte() }.toByteArray()) // Never DNS.
    companion object {
        fun parse(value: String): Endpoint {
            demand(value.length <= 80, "INVALID_ENDPOINT")
            val raw = value.removePrefix("https://")
            val parts = raw.split(':'); demand(parts.size == 2, "INVALID_ENDPOINT")
            val octets = parts[0].split('.')
            demand(octets.size == 4 && octets.all { it.matches(Regex("0|[1-9][0-9]{0,2}")) && it.toInt() <= 255 }, "INVALID_ENDPOINT")
            val nums = octets.map { it.toInt() }
            demand(nums[0] == 127 || nums[0] == 10 || (nums[0] == 172 && nums[1] in 16..31) || (nums[0] == 192 && nums[1] == 168), "ENDPOINT_NOT_LOCAL")
            demand(parts[1].matches(Regex("[1-9][0-9]{0,4}")), "INVALID_ENDPOINT")
            val port = parts[1].toInt(); demand(port in 1..65535, "INVALID_ENDPOINT")
            return Endpoint(parts[0], port)
        }
    }
}

fun transient(code: String) = code in setOf("PEER_UNREACHABLE", "PEER_TIMEOUT", "CONNECTION_INTERRUPTED")
fun retryDelay(attempt: Int, jitter: Int): Long {
    demand(attempt in 1..6 && jitter in 0..250)
    return minOf(30_000L, 1000L shl (attempt - 1)) + jitter
}
