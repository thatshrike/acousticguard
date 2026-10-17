package com.acousticguard

/** Result of one explicit connectivity-triggered outbox flush. */
data class QueueFlushResult(
    val attempted: Int,
    val delivered: Int,
    val deferred: Int,
    val expired: Int
)

/**
 * Transport-neutral boundary for a future WorkManager or foreground-service
 * integration. The caller invokes [onConnectivityAvailable] after observing a
 * usable connection; this class does not register a scheduler or own a
 * background lifetime.
 */
class SosQueueFlushBoundary(
    private val queue: StoreAndForwardQueue,
    private val deliver: (QueuedSosEvent) -> Boolean,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
    private val maxEventsPerFlush: Int = StoreAndForwardQueue.DEFAULT_MAX_ENTRIES
) {
    init {
        require(maxEventsPerFlush > 0) { "maxEventsPerFlush must be positive" }
    }

    fun onConnectivityAvailable(now: Long = nowEpochMs()): QueueFlushResult {
        val expiredBeforeClaim = queue.purgeExpired(now)
        val claimed = queue.claimReady(now, maxEventsPerFlush)
        var delivered = 0
        var deferred = 0
        for (event in claimed) {
            val accepted = runCatching { deliver(event) }.getOrDefault(false)
            if (accepted) {
                if (queue.markDelivered(event.eventId)) delivered++
            } else {
                val retryAt = safeAdd(now, retryDelayMs(event.attemptCount))
                if (queue.markRetry(event.eventId, retryAt, now)) deferred++
            }
        }
        val expiredAfterDelivery = queue.purgeExpired(now)
        return QueueFlushResult(
            attempted = claimed.size,
            delivered = delivered,
            deferred = deferred,
            expired = expiredBeforeClaim + expiredAfterDelivery
        )
    }

    private fun retryDelayMs(attemptCount: Int): Long {
        val exponent = (attemptCount - 1).coerceIn(0, 6)
        return (INITIAL_RETRY_DELAY_MS shl exponent).coerceAtMost(MAX_RETRY_DELAY_MS)
    }

    companion object {
        private const val INITIAL_RETRY_DELAY_MS = 5_000L
        private const val MAX_RETRY_DELAY_MS = 15L * 60L * 1_000L

        private fun safeAdd(value: Long, increment: Long): Long =
            if (value > Long.MAX_VALUE - increment) Long.MAX_VALUE else value + increment
    }
}
