package com.narada.connection

import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*

/** Actual host Olm JNI, fixture transport. This is not a physical-device/LAN test. */
class ChatReceiveOnlyTest {
    @Test fun receivesAndAcknowledgesWithoutSendingQueuedText() = runBlocking<Unit> {
        val f = ChatFixture(); val queued = f.queue("not consented for automatic sending")
        val incoming = f.remoteMessage("computer to phone")
        f.engine.receiveOnly(f.peerId, f.gateway)
        assertEquals(queued, f.memory.document.chat!!.message(queued.id))
        assertTrue(f.pushed.isEmpty()); assertEquals(1, f.acknowledgments)
        assertTrue(f.memory.document.chat!!.message(incoming.message_id).acknowledged)
    }
    @Test fun neverRetriesUncertainOutgoingEvenWhenRetryTimerHasElapsed() = runBlocking<Unit> {
        val f = ChatFixture(); val m = f.queue(); f.afterPush = { fail("PEER_TIMEOUT") }
        expectFailure("PEER_TIMEOUT") { runBlocking { f.sync() } }
        val saved = f.memory.document.chat!!.message(m.id)
        f.afterPush = {}; f.clock += 10_000
        f.engine.receiveOnly(f.peerId, f.gateway)
        assertEquals(saved, f.memory.document.chat!!.message(m.id)); assertEquals(1, f.pushed.size)
    }
    @Test fun receivesAtMostThreePerCheckAndRetainsTheRestAtTheSender() = runBlocking<Unit> {
        val f = ChatFixture(); repeat(5) { f.remoteMessage("incoming $it") }
        f.engine.receiveOnly(f.peerId, f.gateway)
        assertEquals(3, f.memory.document.chat!!.messages.size); assertEquals(2, f.remoteOutbox.size)
        f.engine.receiveOnly(f.peerId, f.gateway)
        assertEquals(5, f.memory.document.chat!!.messages.size); assertTrue(f.remoteOutbox.isEmpty()); assertTrue(f.pushed.isEmpty())
    }
    @Test fun changedAuthorityStillBlocksInboxCommitAndAcknowledgement() = runBlocking<Unit> {
        val f = ChatFixture(); f.remoteMessage()
        f.afterPull = { val p = f.memory.document.peer(f.peerId); f.memory.document = f.memory.document.replace(p.copy(remoteRevision = p.remoteRevision + 1, remoteEpoch = p.remoteEpoch + 1)) }
        expectFailure("CHAT_ACCESS_CHANGED") { runBlocking { f.engine.receiveOnly(f.peerId, f.gateway) } }
        assertTrue(f.memory.document.chat!!.messages.isEmpty()); assertEquals(0, f.acknowledgments)
    }
    @Test fun explicitNewMessageDoesNotReplayOlderQueuedOrUncertainText() = runBlocking<Unit> {
        val f = ChatFixture(); val old = f.queue("old unknown outcome")
        f.afterPush = { fail("PEER_TIMEOUT") }; expectFailure("PEER_TIMEOUT") { runBlocking { f.sync() } }
        val uncertain = f.memory.document.chat!!.message(old.id)
        val queued = f.queue("old queued text"); val current = f.queue("send this one")
        f.afterPush = {}; f.clock += 10_000
        f.engine.sendOne(f.peerId, current.id, f.gateway)
        assertEquals(uncertain, f.memory.document.chat!!.message(old.id))
        assertEquals(queued, f.memory.document.chat!!.message(queued.id))
        assertEquals(listOf(old.id, current.id), f.pushed.map { it.message_id })
        assertEquals(ChatStatus.DELIVERED, f.memory.document.chat!!.message(current.id).status)
    }
    @Test fun repeatedExplicitSendKeepsTheSameDurableReceiptWithoutAnotherPush() = runBlocking<Unit> {
        val f = ChatFixture(); val message = f.queue()
        f.engine.sendOne(f.peerId, message.id, f.gateway)
        val saved = f.memory.document.chat!!.message(message.id)
        f.clock += 10_000; f.engine.sendOne(f.peerId, message.id, f.gateway)
        assertEquals(saved, f.memory.document.chat!!.message(message.id)); assertEquals(1, f.pushed.size)
    }

}
