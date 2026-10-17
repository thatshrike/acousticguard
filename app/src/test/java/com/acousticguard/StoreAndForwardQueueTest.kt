package com.acousticguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreAndForwardQueueTest {
    private var now = 1_000L

    @Test
    fun boundsEntryCountAndPayloadBytesWithoutEvictingOlderEvents() {
        val queue = queue(maxEntries = 2, maxPayloadBytes = 4, maxTotalPayloadBytes = 6)

        assertEquals(EnqueueResult.ENQUEUED, queue.enqueue("one", byteArrayOf(1, 2), now + 10_000L))
        assertEquals(EnqueueResult.ENQUEUED, queue.enqueue("two", byteArrayOf(3, 4, 5), now + 10_000L))
        assertEquals(
            EnqueueResult.REJECTED_FULL,
            queue.enqueue("three", byteArrayOf(6), now + 10_000L)
        )

        assertEquals(listOf("one", "two"), queue.snapshot(now).map { it.eventId })
        assertEquals(5, queue.payloadBytes(now))
    }

    @Test
    fun rejectsPayloadWhenTotalByteBudgetWouldBeExceeded() {
        val queue = queue(maxEntries = 3, maxPayloadBytes = 4, maxTotalPayloadBytes = 4)

        assertEquals(EnqueueResult.ENQUEUED, queue.enqueue("first", byteArrayOf(1, 2, 3), now + 10_000L))
        assertEquals(
            EnqueueResult.REJECTED_FULL,
            queue.enqueue("second", byteArrayOf(4, 5), now + 10_000L)
        )

        assertEquals(listOf("first"), queue.snapshot(now).map { it.eventId })
        assertEquals(3, queue.payloadBytes(now))
    }

    @Test
    fun deduplicatesEventIdAndKeepsTheOriginalEncryptedEnvelope() {
        val storage = InMemoryStoreAndForwardStorage()
        val queue = queue(storage)
        val first = byteArrayOf(1, 2, 3)
        val replacement = byteArrayOf(9, 9, 9)

        assertEquals(EnqueueResult.ENQUEUED, queue.enqueue("same-event", first, now + 10_000L))
        assertEquals(EnqueueResult.DUPLICATE, queue.enqueue("same-event", replacement, now + 20_000L))

        val stored = StoreAndForwardQueue(
            storage = storage,
            nowEpochMs = { now }
        ).snapshot(now).single()
        assertTrue(first.contentEquals(stored.encryptedPayload))
        assertFalse(replacement.contentEquals(stored.encryptedPayload))
    }

    @Test
    fun expiryRemovesEventBeforeItCanBeClaimed() {
        val queue = queue()
        assertEquals(EnqueueResult.ENQUEUED, queue.enqueue("expiring", byteArrayOf(1), now + 100L))

        now += 100L

        assertEquals(1, queue.purgeExpired(now))
        assertTrue(queue.claimReady(now).isEmpty())
        assertTrue(queue.snapshot(now).isEmpty())
        assertEquals(EnqueueResult.ENQUEUED, queue.enqueue("expiring", byteArrayOf(2), now + 100L))
    }

    @Test
    fun retryStatePersistsAcrossQueueInstancesAndIncrementsAttempts() {
        val storage = InMemoryStoreAndForwardStorage()
        val queue = queue(storage)
        assertEquals(EnqueueResult.ENQUEUED, queue.enqueue("retry", byteArrayOf(7), now + 10_000L))

        val firstClaim = queue.claimReady(now).single()
        assertEquals(1, firstClaim.attemptCount)
        assertEquals(QueueDeliveryState.IN_FLIGHT, firstClaim.state)
        assertEquals(now, firstClaim.lastAttemptAtEpochMs)

        assertTrue(queue.markRetry("retry", now + 500L))
        val afterRetry = StoreAndForwardQueue(storage, { now }).snapshot(now).single()
        assertEquals(QueueDeliveryState.READY, afterRetry.state)
        assertEquals(now + 500L, afterRetry.nextAttemptAtEpochMs)
        assertEquals(1, afterRetry.attemptCount)

        assertTrue(StoreAndForwardQueue(storage, { now }).claimReady(now + 499L).isEmpty())
        val secondClaim = StoreAndForwardQueue(storage, { now }).claimReady(now + 500L).single()
        assertEquals(2, secondClaim.attemptCount)
        assertEquals(QueueDeliveryState.IN_FLIGHT, secondClaim.state)
        assertTrue(StoreAndForwardQueue(storage, { now }).markDelivered("retry"))
        assertNull(StoreAndForwardQueue(storage, { now }).snapshot(now).firstOrNull())
    }

    @Test
    fun abandonedInFlightClaimCanBeReclaimedAfterItsLease() {
        val queue = queue(inFlightLeaseMs = 100L)
        assertEquals(EnqueueResult.ENQUEUED, queue.enqueue("lease", byteArrayOf(8), now + 10_000L))
        assertEquals(1, queue.claimReady(now).single().attemptCount)

        assertTrue(queue.claimReady(now + 99L).isEmpty())
        val reclaimed = queue.claimReady(now + 100L).single()
        assertEquals(2, reclaimed.attemptCount)
        assertEquals(now + 100L, reclaimed.lastAttemptAtEpochMs)
    }

    @Test
    fun connectivityBoundaryDefersFailedDeliveryAndAcknowledgesSuccess() {
        val queue = queue()
        assertEquals(EnqueueResult.ENQUEUED, queue.enqueue("boundary", byteArrayOf(4), now + 10_000L))
        var shouldDeliver = false
        val boundary = SosQueueFlushBoundary(queue, { shouldDeliver })

        val failed = boundary.onConnectivityAvailable(now)
        assertEquals(1, failed.attempted)
        assertEquals(1, failed.deferred)
        assertEquals(1, queue.snapshot(now).size)

        shouldDeliver = true
        val beforeRetry = boundary.onConnectivityAvailable(now + 4_999L)
        assertEquals(0, beforeRetry.attempted)
        val delivered = boundary.onConnectivityAvailable(now + 5_000L)
        assertEquals(1, delivered.attempted)
        assertEquals(1, delivered.delivered)
        assertTrue(queue.snapshot(now + 5_000L).isEmpty())
    }

    private fun queue(
        storage: StoreAndForwardStorage = InMemoryStoreAndForwardStorage(),
        maxEntries: Int = 64,
        maxPayloadBytes: Int = 128,
        maxTotalPayloadBytes: Int = 8 * 1024,
        inFlightLeaseMs: Long = StoreAndForwardQueue.DEFAULT_IN_FLIGHT_LEASE_MS
    ) = StoreAndForwardQueue(
        storage = storage,
        nowEpochMs = { now },
        maxEntries = maxEntries,
        maxPayloadBytes = maxPayloadBytes,
        maxTotalPayloadBytes = maxTotalPayloadBytes,
        inFlightLeaseMs = inFlightLeaseMs
    )

}
