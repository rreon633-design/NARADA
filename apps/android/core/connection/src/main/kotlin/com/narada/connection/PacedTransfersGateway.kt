package com.narada.connection

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The unchanged desktop accepts at most 12 new TLS connections per second. Each native content
 * lease can also restart the owner's health session. Space transfer leases by 300 ms (at most four
 * starts in a one-second interval), including finish acknowledgments and adjacent queued jobs.
 * This is cancellable admission pacing, NOT a retry, identity fallback or delivery acknowledgment.
 * Other phones can still exhaust the desktop's global budget; failure then pauses for explicit review. */
class ContentAdmission(private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val gate = Mutex()
    private var started: Long? = null
    suspend fun <T> admit(action: suspend () -> T): T = gate.withLock {
        started?.let { last -> val wait = 300 - (monotonicMillis() - last).coerceAtLeast(0); if (wait > 0) delay(wait) }
        currentCoroutineContext().ensureActive(); started = monotonicMillis(); action()
    }
}
class PacedTransfersGateway(
    private val delegate: TransfersGateway,
    admission: ContentAdmission? = null,
    monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) : TransfersGateway {
    private val admission = admission ?: ContentAdmission(monotonicMillis)
    override suspend fun prepare(record: DownloadRecord) = admission.admit { delegate.prepare(record) }
    override suspend fun chunk(record: DownloadRecord, index: Int) = admission.admit { delegate.chunk(record, index) }
    override suspend fun finish(record: DownloadRecord) = admission.admit { delegate.finish(record) }
    override fun cancel() = delegate.cancel()
}
