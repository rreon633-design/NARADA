package com.narada.connection

import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ChatProtocolTest {
    @Test fun textIsUtf8BoundedAndRejectsUnpairedSurrogates() { ChatRules.text("é".repeat(2048)); expectFailure("INVALID_MESSAGE") { ChatRules.text("é".repeat(2049)) }; expectFailure("INVALID_MESSAGE") { ChatRules.text("\uD800") } }
    @Test fun blankIsOnlyAllowedForADraft() { ChatRules.text("", true); expectFailure("INVALID_MESSAGE") { ChatRules.text(" \t\n") } }
    @Test fun nonDisplayControlsAreRejectedButNewlinesTabsAreAllowed() { ChatRules.text("a\nb\tc"); listOf("x\u0000", "x\u007f", "x\u0085").forEach { expectFailure("INVALID_MESSAGE") { ChatRules.text(it) } } }
    @Test fun canonicalKeyBase64CannotBePaddedOrSubstituted() { val s = java.util.Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32)); ChatRules.key(s,32); expectFailure("CHAT_KEY_INVALID") { ChatRules.key(s+"=",32) }; expectFailure("CHAT_KEY_INVALID") { ChatRules.key("!",32) } }
    @Test fun envelopeDigestUsesTheDesktopFieldOrder() { val s=java.util.Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32));val e=ChatEnvelope(message_id="00000000-0000-4000-8000-000000000001",sender="a".repeat(64),recipient="b".repeat(64),session_id=s,message_type=0,ciphertext="AQID");val expected="{\"version\":1,\"message_id\":\"${e.message_id}\",\"sender\":\"${e.sender}\",\"recipient\":\"${e.recipient}\",\"session_id\":\"$s\",\"message_type\":0,\"ciphertext\":\"AQID\"}";assertEquals(sha256(expected.toByteArray()),e.digest()) }
    @Test fun receiptCannotAcknowledgeAnotherCiphertext() { val f=ChatFixture();val e=f.remoteMessage();val r=ChatReceipt(e.message_id,e.sender,e.recipient,e.digest(),committed_at="2026-10-31T00:00:00Z");r.matches(e);expectFailure("INVALID_RECEIPT") {r.copy(digest="f".repeat(64)).matches(e)} }
    @Test fun invalidReceiptTimeAndReadClaimsAreRejected() { val r=ChatReceipt(UUID.randomUUID().toString(),"a".repeat(64),"b".repeat(64),"c".repeat(64),committed_at="2026-10-31T00:00:00Z");expectFailure("INVALID_RECEIPT") {r.copy(state="read").validate()};expectFailure("INVALID_RECEIPT") {r.copy(committed_at="not a valid receipt clock").validate()} }
    @Test fun strictWireRejectsQuotedNumbersUnknownFieldsAndDuplicateKeys() { val f=ChatFixture();val raw=Wire.encode(f.remoteBundle).decodeToString();listOf(raw.replace("\"version\":1","\"version\":\"1\""),raw.replaceFirst("{","{\"unexpected\":true,"),raw.replaceFirst("{","{\"version\":1,")).forEach {expectFailure {Wire.decode<ChatBundle>(it.toByteArray())}} }
    @Test fun debugStringsNeverExposePlaintextOrRatchets()=runBlocking<Unit>{val f=ChatFixture();val m=f.queue("secret sentinel");f.engine.saveDraft(f.peerId,"draft sentinel");assertFalse(f.memory.document.toString().contains("sentinel"));assertFalse(m.toString().contains("sentinel"));assertFalse(f.memory.document.chat.toString().contains("account".uppercase()));assertFalse(f.memory.document.chat!!.drafts.single().toString().contains("sentinel"))}
    @Test fun oldPayloadCannotSmuggleAnOlmAccount() {val f=ChatFixture();expectFailure("TRUST_STORE_INVALID") {f.memory.document.copy(version=3).validate()};f.memory.document.validate()}
    @Test fun accountAndHistoryCannotBeSilentlyDroppedByAnotherMutation()=runBlocking<Unit>{val f=ChatFixture();f.queue();expectFailure("CRYPTO_STATE_INVALID") {runBlocking{f.memory.mutate{it.copy(chat=null)}}};expectFailure("CHAT_STATE_INVALID") {runBlocking{f.memory.mutate{it.copy(chat=it.chat!!.copy(messages=emptyList()))}}}}
    @Test fun remoteStorageErrorsNeverBecomeLocalCryptoStorageErrors() {for(code in listOf("CRYPTO_STATE_INVALID","CHAT_STATE_INVALID","CONTENT_STORE_LOCKED","STORAGE_UNAVAILABLE"))expectFailure("CHAT_REMOTE_STORAGE"){chatRemoteFailure(code)};expectFailure("PEER_REJECTED"){chatRemoteFailure("peer supplied plaintext detail")}}
}
