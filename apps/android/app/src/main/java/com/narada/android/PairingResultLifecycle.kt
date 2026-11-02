package com.narada.android

import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.yield

/** ActivityResult pending results may arrive inside super.onStart, before the connection
 * owner publishes foreground consent. Keep the code in memory and defer consumption until
 * that callback has returned. The caller's lifecycleScope cancels on destruction. */
internal suspend fun withStartedPairingResult(lifecycle: Lifecycle, consume: suspend () -> Unit) {
    lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.STARTED) || it == Lifecycle.State.DESTROYED }
    yield()
    if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) consume()
}

/** One external QR input at a time. A late readiness result must not launch over a
 * backgrounded/destroyed Activity. State is memory-only and contains no invitation. */
internal class PairingInputGate {
    private val active = java.util.concurrent.atomic.AtomicBoolean(false)
    private val generation = java.util.concurrent.atomic.AtomicLong()
    fun invalidate() { generation.incrementAndGet() }
    suspend fun start(isStarted: () -> Boolean, prepare: suspend () -> Boolean, open: () -> Unit): Boolean {
        if (!isStarted() || !active.compareAndSet(false, true)) return false
        val turn = generation.get()
        var opened = false
        try {
            if (!prepare() || !isStarted() || turn != generation.get()) return false
            open(); opened = true
            return true
        } finally { if (!opened) active.set(false) }
    }
    fun returned() { active.set(false) }
}
