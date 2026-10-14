package com.lezi.babylog.core.common.diagnostics

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.CancellationException

/** Diagnostic protocol v2: process-local spans, never entity or cross-device identities. */
enum class CareLatencyStage {
    LOCAL_TRANSACTION,
    COMPOSER_WRITE,
    COMPOSER_UI_STATE,
    MEDIA_RECEIPTS,
    COMMIT_ROUND_TRIP,
    PROOF_VALIDATION,
    LOCAL_SETTLEMENT,
    PAGE_MEDIA_STAGING,
    PAGE_APPLY,
}

enum class CareLatencyOperation { CREATE_RECORD, EDIT_RECORD, COMMIT_BATCH, PULL_PAGE }
enum class CareLatencyOutcome { SUCCESS, FAILED, CANCELLED, ACCEPTED, MERGED, BRANCHED, MIXED, UI_NOT_DELIVERED }
enum class CareLatencyBytes { UNKNOWN, ZERO, LE_64_KIB, LE_1_MIB, LE_10_MIB, GT_10_MIB }

fun interface CareLatencyClock {
    fun nowNanos(): Long
}

/** Closed numeric/enum shape. No content, identity, URI, hash, credential or exception message. */
data class CareLatencySample(
    val spanSequence: Long,
    val stage: CareLatencyStage,
    val operation: CareLatencyOperation,
    val outcome: CareLatencyOutcome,
    val durationNanos: Long,
    /** -1 means unknown/unchanged; real counts, including one or two photos, are retained. */
    val mediaCount: Int,
    val rootCount: Int,
    val byteBucket: CareLatencyBytes,
)

data class CareLatencySnapshot(
    val samples: List<CareLatencySample>,
    val overwritten: Long,
    val capacityDropped: Long,
    val contentionDropped: Long,
    val rejected: Long,
    val incomplete: Long,
)

/**
 * Disabled until an explicit debugger/test window is opened; no settings, UI, wire or durable log.
 * All endpoints in one window use one monotonic clock. System.nanoTime excludes device deep sleep;
 * durations are not event wall time, UI frames, server durability or cross-device visibility.
 */
object CareLatencyDiagnostics {
    private val current = AtomicReference<CareLatencyWindow?>(null)

    /** Allows hooks to skip even count aggregation when diagnostics are disabled. */
    val isRecording: Boolean get() = current.get() != null

    fun openDebugWindow(
        clock: CareLatencyClock = CareLatencyClock(System::nanoTime),
    ): CareLatencyWindow {
        val window = CareLatencyWindow(clock)
        check(current.compareAndSet(null, window)) { "A diagnostic window is already open" }
        return window
    }

    fun begin(
        stage: CareLatencyStage,
        operation: CareLatencyOperation,
        mediaCount: Int = -1,
        rootCount: Int = -1,
        bytes: CareLatencyBytes = CareLatencyBytes.UNKNOWN,
    ): CareLatencySpan? {
        val window = current.get() ?: return null
        return window.begin(stage, operation, mediaCount, rootCount, bytes)
    }

    /** Inline so the disabled path does not allocate a diagnostics block or record. */
    inline fun <T> measure(
        stage: CareLatencyStage,
        operation: CareLatencyOperation,
        mediaCount: Int = -1,
        rootCount: Int = -1,
        bytes: CareLatencyBytes = CareLatencyBytes.UNKNOWN,
        block: () -> T,
    ): T {
        val span = begin(stage, operation, mediaCount, rootCount, bytes)
        try {
            val result = block()
            span?.finish(CareLatencyOutcome.SUCCESS)
            return result
        } catch (failure: Throwable) {
            span?.finish(
                if (failure is CancellationException) CareLatencyOutcome.CANCELLED else CareLatencyOutcome.FAILED,
            )
            throw failure
        }
    }

    internal fun release(window: CareLatencyWindow) {
        current.compareAndSet(window, null)
    }
}

class CareLatencySpan internal constructor(
    internal val window: CareLatencyWindow,
    internal val sequence: Long,
    internal val startedNanos: Long,
    internal val stage: CareLatencyStage,
    internal val operation: CareLatencyOperation,
    internal val mediaCount: Int,
    internal val rootCount: Int,
    internal val bytes: CareLatencyBytes,
) {
    internal val finished = AtomicBoolean(false)

    /** Best effort, idempotent, never waits for a diagnostic lock or changes business failure. */
    fun finish(outcome: CareLatencyOutcome = CareLatencyOutcome.SUCCESS) {
        if (finished.compareAndSet(false, true)) window.finish(this, outcome)
    }
}

class CareLatencyWindow internal constructor(private val clock: CareLatencyClock) : AutoCloseable {
    private val lock = ReentrantLock()
    private val active = AtomicBoolean(true)
    private val spans = LinkedHashMap<Long, CareLatencySpan>()
    private val samples = ArrayDeque<CareLatencySample>(CAPACITY)
    private var sequence = 0L
    private val overwritten = AtomicLong()
    private val capacityDropped = AtomicLong()
    private val contentionDropped = AtomicLong()
    private val rejected = AtomicLong()
    private var incomplete = 0L

    internal fun begin(
        stage: CareLatencyStage,
        operation: CareLatencyOperation,
        mediaCount: Int,
        rootCount: Int,
        bytes: CareLatencyBytes,
    ): CareLatencySpan? {
        if (!active.get()) return null
        if (!lock.tryLock()) {
            contentionDropped.incrementBounded()
            return null
        }
        try {
            if (!active.get()) return null
            if (mediaCount !in -1..MAX_COUNT || rootCount !in -1..MAX_COUNT || sequence == Long.MAX_VALUE) {
                rejected.incrementBounded()
                return null
            }
            // A finish dropped on lock contention is retired here, keeping open tokens bounded.
            spans.entries.removeAll { it.value.finished.get() }
            if (spans.size == MAX_OPEN) {
                capacityDropped.incrementBounded()
                return null
            }
            val started = clock.nowNanos()
            val span = CareLatencySpan(this, ++sequence, started, stage, operation, mediaCount, rootCount, bytes)
            spans[span.sequence] = span
            return span
        } catch (_: Throwable) {
            rejected.incrementBounded()
            return null
        } finally {
            lock.unlock()
        }
    }

    internal fun finish(span: CareLatencySpan, outcome: CareLatencyOutcome) {
        if (!active.get()) return
        if (!lock.tryLock()) {
            contentionDropped.incrementBounded()
            return
        }
        try {
            if (!active.get() || spans.remove(span.sequence) !== span) return
            val duration = clock.nowNanos() - span.startedNanos
            if (duration !in 0..MAX_DURATION_NANOS) {
                rejected.incrementBounded()
                return
            }
            val sample = CareLatencySample(
                span.sequence, span.stage, span.operation, outcome, duration,
                span.mediaCount, span.rootCount, span.bytes,
            )
            if (samples.size == CAPACITY) {
                samples.removeFirst()
                overwritten.incrementBounded()
            }
            samples.addLast(sample)
        } catch (_: Throwable) {
            rejected.incrementBounded()
        } finally {
            lock.unlock()
        }
    }

    /** Explicit debugger/test read; business hooks do not copy or export the ring. */
    fun snapshot(): CareLatencySnapshot {
        lock.lock()
        try {
            return CareLatencySnapshot(
                samples.toList(), overwritten.get(), capacityDropped.get(), contentionDropped.get(),
                rejected.get(), incomplete,
            )
        } finally {
            lock.unlock()
        }
    }

    override fun close() {
        lock.lock()
        try {
            if (active.compareAndSet(true, false)) {
                incomplete = spans.values.count { !it.finished.get() }.toLong()
                spans.clear()
            }
        } finally {
            lock.unlock()
            CareLatencyDiagnostics.release(this)
        }
    }

    private fun AtomicLong.incrementBounded() {
        while (true) {
            val value = get()
            if (value == Long.MAX_VALUE || compareAndSet(value, value + 1)) return
        }
    }

    companion object {
        const val CAPACITY = 256
        const val MAX_OPEN = 16
        private const val MAX_COUNT = 4096
        private const val MAX_DURATION_NANOS = 6L * 60 * 60 * 1_000_000_000
    }
}
