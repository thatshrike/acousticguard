package com.acousticguard

import android.content.Context
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

/**
 * A small durable outbox for already-encrypted SOS envelopes.
 *
 * The queue deliberately accepts an encrypted payload rather than audio or
 * labels. Its serialized form contains only the event id, envelope bytes,
 * expiry, and delivery-attempt metadata. A bounded queue refuses new entries
 * when it is full; it never evicts an older unexpired SOS silently.
 */
interface StoreAndForwardStorage {
    fun read(): String?

    fun write(value: String)
}

/** SharedPreferences is used only as the persistence adapter; no PCM is stored. */
class SharedPreferencesStoreAndForwardStorage(context: Context) : StoreAndForwardStorage {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES,
        Context.MODE_PRIVATE
    )

    override fun read(): String? = preferences.getString(KEY_QUEUE, null)

    override fun write(value: String) {
        check(preferences.edit().putString(KEY_QUEUE, value).commit()) {
            "Could not persist AcousticGuard SOS queue"
        }
    }

    companion object {
        private const val PREFERENCES = "acousticguard_store_and_forward"
        private const val KEY_QUEUE = "encrypted_sos_queue_v1"
    }
}

/** In-memory adapter used by pure JVM tests and by callers that need a small host-side seam. */
class InMemoryStoreAndForwardStorage(initialValue: String? = null) : StoreAndForwardStorage {
    private var value: String? = initialValue

    override fun read(): String? = value

    override fun write(value: String) {
        this.value = value
    }
}

enum class QueueDeliveryState {
    READY,
    IN_FLIGHT
}

data class QueuedSosEvent(
    val eventId: String,
    val encryptedPayload: ByteArray,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val attemptCount: Int,
    val lastAttemptAtEpochMs: Long?,
    val nextAttemptAtEpochMs: Long,
    val state: QueueDeliveryState
) {
    fun isExpiredAt(nowEpochMs: Long): Boolean = nowEpochMs >= expiresAtEpochMs

    fun payloadCopy(): ByteArray = encryptedPayload.copyOf()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is QueuedSosEvent) return false
        return eventId == other.eventId &&
            encryptedPayload.contentEquals(other.encryptedPayload) &&
            createdAtEpochMs == other.createdAtEpochMs &&
            expiresAtEpochMs == other.expiresAtEpochMs &&
            attemptCount == other.attemptCount &&
            lastAttemptAtEpochMs == other.lastAttemptAtEpochMs &&
            nextAttemptAtEpochMs == other.nextAttemptAtEpochMs &&
            state == other.state
    }

    override fun hashCode(): Int {
        var result = eventId.hashCode()
        result = 31 * result + encryptedPayload.contentHashCode()
        result = 31 * result + createdAtEpochMs.hashCode()
        result = 31 * result + expiresAtEpochMs.hashCode()
        result = 31 * result + attemptCount
        result = 31 * result + (lastAttemptAtEpochMs?.hashCode() ?: 0)
        result = 31 * result + nextAttemptAtEpochMs.hashCode()
        result = 31 * result + state.hashCode()
        return result
    }
}

enum class EnqueueResult {
    ENQUEUED,
    DUPLICATE,
    REJECTED_INVALID,
    REJECTED_EXPIRED,
    REJECTED_FULL
}

/**
 * Bounded, process-local queue for encrypted SOS envelopes.
 *
 * The queue is intentionally transport-neutral. A future WorkManager job or
 * foreground service can call [claimReady], deliver the returned envelopes,
 * then call [markDelivered] or [markRetry]. [SosQueueFlushBoundary] supplies
 * the connectivity-triggered orchestration seam without choosing a scheduler.
 * All queue instances in this app process share a small lock so a receiver and
 * a future worker cannot lose a read-modify-write update in SharedPreferences.
 * Cross-process access is outside the contract; Android components should use
 * the normal single application process.
 */
class StoreAndForwardQueue(
    private val storage: StoreAndForwardStorage,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxPayloadBytes: Int = DEFAULT_MAX_PAYLOAD_BYTES,
    private val maxTotalPayloadBytes: Int = DEFAULT_MAX_TOTAL_PAYLOAD_BYTES,
    private val inFlightLeaseMs: Long = DEFAULT_IN_FLIGHT_LEASE_MS
) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
        require(maxPayloadBytes > 0) { "maxPayloadBytes must be positive" }
        require(maxTotalPayloadBytes >= maxPayloadBytes) {
            "maxTotalPayloadBytes must fit at least one payload"
        }
        require(inFlightLeaseMs > 0L) { "inFlightLeaseMs must be positive" }
    }

    fun enqueue(
        eventId: String,
        encryptedPayload: ByteArray,
        expiresAtEpochMs: Long = safeAdd(nowEpochMs(), DEFAULT_EVENT_TTL_MS)
    ): EnqueueResult = synchronized(QUEUE_LOCK) {
        val now = nowEpochMs()
        val events = loadLocked()
        var changed = purgeExpiredLocked(events, now)
        if (eventId.isBlank() || encryptedPayload.isEmpty() || encryptedPayload.size > maxPayloadBytes) {
            if (changed) persistLocked(events)
            return@synchronized EnqueueResult.REJECTED_INVALID
        }
        if (expiresAtEpochMs <= now) {
            if (changed) persistLocked(events)
            return@synchronized EnqueueResult.REJECTED_EXPIRED
        }
        if (events.any { it.eventId == eventId }) {
            if (changed) persistLocked(events)
            return@synchronized EnqueueResult.DUPLICATE
        }
        if (events.size >= maxEntries || totalPayloadBytes(events) + encryptedPayload.size > maxTotalPayloadBytes) {
            if (changed) persistLocked(events)
            return@synchronized EnqueueResult.REJECTED_FULL
        }

        events += QueuedSosEvent(
            eventId = eventId,
            encryptedPayload = encryptedPayload.copyOf(),
            createdAtEpochMs = now,
            expiresAtEpochMs = expiresAtEpochMs,
            attemptCount = 0,
            lastAttemptAtEpochMs = null,
            nextAttemptAtEpochMs = now,
            state = QueueDeliveryState.READY
        )
        changed = true
        if (changed) persistLocked(events)
        EnqueueResult.ENQUEUED
    }

    fun enqueue(
        eventId: UUID,
        encryptedPayload: ByteArray,
        expiresAtEpochMs: Long = safeAdd(nowEpochMs(), DEFAULT_EVENT_TTL_MS)
    ): EnqueueResult = enqueue(eventId.toString(), encryptedPayload, expiresAtEpochMs)

    /**
     * Claims at most [limit] ready events and records an attempt atomically.
     * An abandoned in-flight claim becomes eligible after [inFlightLeaseMs].
     */
    fun claimReady(
        now: Long = nowEpochMs(),
        limit: Int = DEFAULT_MAX_ENTRIES
    ): List<QueuedSosEvent> = synchronized(QUEUE_LOCK) {
        require(limit > 0) { "limit must be positive" }
        val events = loadLocked()
        var changed = purgeExpiredLocked(events, now)
        val claimable = events
            .filter { event ->
                when (event.state) {
                    QueueDeliveryState.READY -> event.nextAttemptAtEpochMs <= now
                    QueueDeliveryState.IN_FLIGHT ->
                        event.lastAttemptAtEpochMs?.let { now - it >= inFlightLeaseMs } == true
                }
            }
            .sortedWith(compareBy<QueuedSosEvent> { it.createdAtEpochMs }.thenBy { it.eventId })
            .take(limit)

        val claimedIds = claimable.mapTo(HashSet()) { it.eventId }
        if (claimedIds.isNotEmpty()) {
            for (index in events.indices) {
                val event = events[index]
                if (event.eventId !in claimedIds) continue
                events[index] = event.copy(
                    attemptCount = event.attemptCount + 1,
                    lastAttemptAtEpochMs = now,
                    nextAttemptAtEpochMs = now,
                    state = QueueDeliveryState.IN_FLIGHT
                )
            }
            changed = true
        }
        if (changed) persistLocked(events)
        events.filter { it.eventId in claimedIds }.map(QueuedSosEvent::deepCopy)
    }

    fun markDelivered(eventId: String): Boolean = synchronized(QUEUE_LOCK) {
        val events = loadLocked()
        val removed = events.removeAll { it.eventId == eventId }
        if (removed) persistLocked(events)
        removed
    }

    /** Returns the event to READY state after a failed transport attempt. */
    fun markRetry(
        eventId: String,
        nextAttemptAtEpochMs: Long,
        now: Long = nowEpochMs()
    ): Boolean = synchronized(QUEUE_LOCK) {
        val events = loadLocked()
        var changed = purgeExpiredLocked(events, now)
        val index = events.indexOfFirst { it.eventId == eventId }
        if (index < 0) {
            if (changed) persistLocked(events)
            return@synchronized false
        }
        val event = events[index]
        events[index] = event.copy(
            nextAttemptAtEpochMs = maxOf(now, nextAttemptAtEpochMs),
            state = QueueDeliveryState.READY
        )
        changed = true
        if (changed) persistLocked(events)
        true
    }

    fun purgeExpired(now: Long = nowEpochMs()): Int = synchronized(QUEUE_LOCK) {
        val events = loadLocked()
        val before = events.size
        val removed = purgeExpiredLocked(events, now)
        if (removed) persistLocked(events)
        before - events.size
    }

    fun snapshot(now: Long = nowEpochMs()): List<QueuedSosEvent> = synchronized(QUEUE_LOCK) {
        val events = loadLocked()
        val changed = purgeExpiredLocked(events, now)
        if (changed) persistLocked(events)
        events.map(QueuedSosEvent::deepCopy)
    }

    fun payloadBytes(now: Long = nowEpochMs()): Int = synchronized(QUEUE_LOCK) {
        val events = loadLocked()
        val changed = purgeExpiredLocked(events, now)
        if (changed) persistLocked(events)
        totalPayloadBytes(events)
    }

    private fun loadLocked(): MutableList<QueuedSosEvent> {
        val serialized = storage.read().orEmpty()
        if (serialized.isBlank()) return mutableListOf()
        val deduplicated = LinkedHashMap<String, QueuedSosEvent>()
        for (record in serialized.split(RECORD_SEPARATOR)) {
            decode(record)?.let { event ->
                if (event.encryptedPayload.size <= maxPayloadBytes && event.eventId.isNotBlank()) {
                    deduplicated.putIfAbsent(event.eventId, event)
                }
            }
        }
        val result = mutableListOf<QueuedSosEvent>()
        var bytes = 0
        for (event in deduplicated.values) {
            if (result.size >= maxEntries) break
            if (bytes + event.encryptedPayload.size > maxTotalPayloadBytes) continue
            result += event
            bytes += event.encryptedPayload.size
        }
        return result
    }

    private fun persistLocked(events: List<QueuedSosEvent>) {
        storage.write(events.joinToString(RECORD_SEPARATOR) { encode(it) })
    }

    private fun purgeExpiredLocked(events: MutableList<QueuedSosEvent>, now: Long): Boolean =
        events.removeAll { it.isExpiredAt(now) }

    private fun totalPayloadBytes(events: List<QueuedSosEvent>): Int =
        events.sumOf { it.encryptedPayload.size }

    private fun encode(event: QueuedSosEvent): String = listOf(
        FORMAT_VERSION,
        encodeText(event.eventId),
        encodeBytes(event.encryptedPayload),
        event.createdAtEpochMs.toString(),
        event.expiresAtEpochMs.toString(),
        event.attemptCount.toString(),
        (event.lastAttemptAtEpochMs ?: NO_TIMESTAMP).toString(),
        event.nextAttemptAtEpochMs.toString(),
        event.state.name
    ).joinToString(FIELD_SEPARATOR)

    private fun decode(record: String): QueuedSosEvent? {
        val fields = record.split(FIELD_SEPARATOR, limit = 9)
        if (fields.size != 9 || fields[0] != FORMAT_VERSION) return null
        return runCatching {
            val eventId = decodeText(fields[1])
            val payload = decodeBytes(fields[2])
            val createdAt = fields[3].toLong()
            val expiresAt = fields[4].toLong()
            val attempts = fields[5].toInt()
            val lastAttempt = fields[6].toLong().takeUnless { it == NO_TIMESTAMP }
            val nextAttempt = fields[7].toLong()
            val state = QueueDeliveryState.valueOf(fields[8])
            if (eventId.isBlank() || payload.isEmpty() || attempts < 0) return null
            QueuedSosEvent(
                eventId = eventId,
                encryptedPayload = payload,
                createdAtEpochMs = createdAt,
                expiresAtEpochMs = expiresAt,
                attemptCount = attempts,
                lastAttemptAtEpochMs = lastAttempt,
                nextAttemptAtEpochMs = nextAttempt,
                state = state
            )
        }.getOrNull()
    }

    private fun encodeText(value: String): String = encodeBytes(value.toByteArray(StandardCharsets.UTF_8))

    private fun decodeText(value: String): String = String(decodeBytes(value), StandardCharsets.UTF_8)

    private fun encodeBytes(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    private fun decodeBytes(value: String): ByteArray = Base64.getUrlDecoder().decode(value)

    companion object {
        const val DEFAULT_MAX_ENTRIES = 64
        const val DEFAULT_MAX_PAYLOAD_BYTES = 128
        const val DEFAULT_MAX_TOTAL_PAYLOAD_BYTES = 8 * 1024
        const val DEFAULT_IN_FLIGHT_LEASE_MS = 60_000L
        const val DEFAULT_EVENT_TTL_MS = 24L * 60L * 60L * 1_000L

        private const val FORMAT_VERSION = "1"
        private const val FIELD_SEPARATOR = "|"
        private const val RECORD_SEPARATOR = "\n"
        private const val NO_TIMESTAMP = -1L
        private val QUEUE_LOCK = Any()

        private fun safeAdd(value: Long, increment: Long): Long =
            if (value > Long.MAX_VALUE - increment) Long.MAX_VALUE else value + increment
    }
}

private fun QueuedSosEvent.deepCopy(): QueuedSosEvent = copy(encryptedPayload = encryptedPayload.copyOf())
