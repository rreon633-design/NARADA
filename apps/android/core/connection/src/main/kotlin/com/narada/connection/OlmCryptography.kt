package com.narada.connection

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable data class OlmInfo(val bridge_version: Int, val vodozemac: String, val mode: String, val mac_bits: Int, val experimental_session_config: Boolean)
@Serializable data class OlmAccount(val account: String, val curve: String, val ed: String) { override fun toString() = "OlmAccount([secret redacted])" }
@Serializable data class OlmPublic(val curve: String, val ed: String)
@Serializable data class OlmBundle(val account: String, val bundle: ChatBundle) { override fun toString() = "OlmBundle([secret redacted])" }
@Serializable data class OlmEncrypted(val session: ChatSession, val envelope: ChatEnvelope) { override fun toString() = "OlmEncrypted([secret redacted])" }
@Serializable data class OlmDecrypted(val account: String, val session: ChatSession, val text: String, val attachment: AttachmentSecret? = null) { override fun toString() = "OlmDecrypted([secret redacted])" }
@Serializable private data class OlmReply(val ok: Boolean, val value: JsonObject?, val error: String?) { override fun toString() = "OlmReply([secret redacted])" }
@Serializable private data class OlmSessionInfo(val id: String, val mac_bits: Int)
@Serializable private data class OlmValid(val valid: Boolean)
interface OlmCryptography {
    fun info(): OlmInfo
    fun create(): OlmAccount
    fun inspect(account: String): OlmPublic
    fun inspectSession(session: ChatSession)
    fun bundle(account: String, localId: String, peerId: String): OlmBundle
    fun verify(bundle: ChatBundle, localId: String, peerId: String)
    fun encrypt(account: String, ours: ChatBundle, remote: ChatBundle, session: ChatSession?, body: ChatBody): OlmEncrypted
    fun decrypt(account: String, ours: ChatBundle, remote: ChatBundle, sessions: List<ChatSession>, envelope: ChatEnvelope): OlmDecrypted
}
/** The sole production path: a bundled library, never a downloaded library, arbitrary file path,
 * software ratchet substitute or TLS-only mode. Failure leaves messaging unavailable. */
object NativeOlmBridge {
    init { System.loadLibrary("narada_olm") }
    @JvmStatic external fun call(input: ByteArray): ByteArray
}
class VodozemacOlm : OlmCryptography {
    private val compatibility by lazy {
        invoke<OlmInfo>(buildJsonObject { put("op", "info") }).also {
            demand(it == OlmInfo(2, "0.11.1", "olm.v1", 64, false), "CRYPTO_INCOMPATIBLE")
        }
    }
    private inline fun <reified T> invoke(request: JsonObject): T {
        val input = Wire.encode(request, 524_288)
        val output = try { NativeOlmBridge.call(input) }
        catch (_: LinkageError) { fail("CRYPTO_UNAVAILABLE") }
        catch (_: RuntimeException) { fail("CRYPTO_UNAVAILABLE") }
        finally { input.fill(0) }
        try {
            val reply = Wire.decode<OlmReply>(output, 524_288)
            if (!reply.ok) {
                demand(reply.value == null, "CRYPTO_UNAVAILABLE")
                val code = reply.error
                fail(if (code in setOf("CRYPTO_STATE_INVALID", "CRYPTO_INPUT_INVALID", "CRYPTO_UNAVAILABLE", "CHAT_STATE_LIMIT", "CHAT_KEY_INVALID", "CHAT_SESSION_FAILED", "CHAT_SESSION_MISSING", "CHAT_SESSION_LIMIT", "CHAT_DECRYPT_FAILED", "CHAT_ATTACHMENT_UNSUPPORTED", "INVALID_MESSAGE")) code!! else "CRYPTO_UNAVAILABLE")
            }
            demand(reply.error == null && reply.value != null, "CRYPTO_UNAVAILABLE")
            val encoded = Wire.encode(reply.value, 524_288)
            return try { Wire.decode<T>(encoded, 524_288) } finally { encoded.fill(0) }
        } finally { output.fill(0) }
    }
    override fun info() = compatibility
    override fun create(): OlmAccount { info(); return invoke(buildJsonObject { put("op", "new_account") }) }
    override fun inspect(account: String): OlmPublic { info(); return invoke(buildJsonObject { put("op", "inspect_account"); put("account", account) }) }
    override fun inspectSession(session: ChatSession) {
        info(); val result = invoke<OlmSessionInfo>(buildJsonObject { put("op", "inspect_session"); put("session", Wire.json.encodeToJsonElement(ChatSession.serializer(), session)) })
        demand(result.id == session.id && result.mac_bits == 64, "CRYPTO_STATE_INVALID")
    }
    override fun bundle(account: String, localId: String, peerId: String): OlmBundle { info(); return invoke(buildJsonObject { put("op", "bundle"); put("account", account); put("local_id", localId); put("peer_id", peerId) }) }
    override fun verify(bundle: ChatBundle, localId: String, peerId: String) {
        info(); bundle.validate(peerId, localId)
        val result = invoke<OlmValid>(buildJsonObject { put("op", "verify_bundle"); put("bundle", Wire.json.encodeToJsonElement(ChatBundle.serializer(), bundle)); put("local_id", localId); put("peer_id", peerId) })
        demand(result.valid, "CHAT_KEY_INVALID")
    }
    override fun encrypt(account: String, ours: ChatBundle, remote: ChatBundle, session: ChatSession?, body: ChatBody): OlmEncrypted {
        info(); return invoke(buildJsonObject {
            put("op", "encrypt"); put("account", account); put("ours", Wire.json.encodeToJsonElement(ChatBundle.serializer(), ours)); put("remote", Wire.json.encodeToJsonElement(ChatBundle.serializer(), remote))
            put("session", session?.let { Wire.json.encodeToJsonElement(ChatSession.serializer(), it) } ?: JsonNull)
            put("body", Wire.json.encodeToJsonElement(ChatBody.serializer(), body))
        })
    }
    override fun decrypt(account: String, ours: ChatBundle, remote: ChatBundle, sessions: List<ChatSession>, envelope: ChatEnvelope): OlmDecrypted {
        info(); return invoke(buildJsonObject {
            put("op", "decrypt"); put("account", account); put("ours", Wire.json.encodeToJsonElement(ChatBundle.serializer(), ours)); put("remote", Wire.json.encodeToJsonElement(ChatBundle.serializer(), remote))
            put("sessions", JsonArray(sessions.map { Wire.json.encodeToJsonElement(ChatSession.serializer(), it) })); put("envelope", Wire.json.encodeToJsonElement(ChatEnvelope.serializer(), envelope))
        })
    }
}

/** Decode persisted pickles on reopen too; a structural JSON reopen must not clear a crypto-storage
 * latch while a damaged/foreign-mode ratchet remains hidden in the sealed aggregate. */
fun verifyProtectedChat(doc: TrustDocument, crypto: OlmCryptography) {
    val chat = doc.chat ?: return
    val public = crypto.inspect(chat.account)
    demand(public.curve == chat.curve && public.ed == chat.ed, "CRYPTO_STATE_INVALID")
    chat.bindings.forEach { b ->
        crypto.verify(b.ours, b.peerId, doc.deviceId)
        b.remote?.let { crypto.verify(it, doc.deviceId, b.peerId) }
        b.sessions.forEach(crypto::inspectSession)
    }
}
