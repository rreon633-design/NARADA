package com.narada.android.transfers

import com.narada.connection.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*

/** At most one provider worker and one cancellation helper. Android cannot forcibly terminate a
 * noncooperative provider. Caller wait is bounded; a stuck worker remains quarantined, never replaced
 * with an unbounded new thread. Its durable export intent stays uncertain until user review. */
class BoundedProviderIo(private val timeoutMillis: Long = 90_000, private val monotonicNanos: () -> Long = System::nanoTime) {
    private val occupied = AtomicBoolean()
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "narada-provider-copy").apply { isDaemon = true } }
    val busy: Boolean get() = occupied.get()
    var onIdle: () -> Unit = {}
    private val idleListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    fun addIdleListener(listener: () -> Unit) { idleListeners.add(listener) }
    suspend fun run(cancel: () -> Unit, action: suspend (() -> Unit) -> Unit) {
        demand(occupied.compareAndSet(false, true), "PROVIDER_BUSY")
        val aborted = AtomicBoolean(); val workerDone = AtomicBoolean(); val callerDone = AtomicBoolean(); val cancelDone = AtomicBoolean(true)
        val thread = AtomicReference<Thread?>(); val result = CompletableFuture<Result<Unit>>()
        val deadline = monotonicNanos() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        fun release() { if (workerDone.get() && callerDone.get() && cancelDone.get() && occupied.compareAndSet(true, false)) { onIdle(); idleListeners.forEach { it() } } }
        executor.execute {
            thread.set(Thread.currentThread())
            // Transfer failure as data: coroutine stack recovery can nest ExecutionException wrappers
            // around Future.get(), which must not erase a protected-storage error classification.
            try { result.complete(runCatching { runBlocking { action { demand(!aborted.get() && monotonicNanos() - deadline < 0, "EXPORT_INTERRUPTED") } } }) }
            finally { workerDone.set(true); release(); Thread.interrupted() }
        }
        try { runInterruptible(Dispatchers.IO) { result.get(timeoutMillis, TimeUnit.MILLISECONDS) }.getOrThrow() }
        catch (e: Exception) {
            if (e is TimeoutException || e is CancellationException || e is InterruptedException) {
                cancelDone.set(false); aborted.set(true); thread.get()?.interrupt()
                Thread({ try { cancel() } finally { cancelDone.set(true); release() } }, "narada-provider-cancel").apply { isDaemon = true; start() }
                if (e is CancellationException) throw e
                fail("PROVIDER_TIMEOUT")
            }
            if (e is ConnectionFailure) throw e
            fail("PROVIDER_UNAVAILABLE")
        } finally { callerDone.set(true); release() }
    }
}
