package com.narada.connection

/** Only idempotent ordinary-download network work. NOT uploads, chat, pairing, provider creates,
 * finish acknowledgements or restart recovery. The owner must also fence lifecycle/authority. */
object TransferRetryPolicy {
    const val MAX_RETRIES = 2
    fun delayAfter(code: String, failedAttempt: Int): Long? {
        if (code !in setOf("PEER_UNREACHABLE", "PEER_TIMEOUT", "CONNECTION_INTERRUPTED", "RATE_LIMITED")) return null
        return when (failedAttempt) { 1 -> 1500L; 2 -> 3000L; else -> null }
    }
}
