package com.lezi.babylog.designsystem

import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

internal const val LOCAL_PHOTO_DIAGNOSTIC_SAMPLE_CAPACITY = 64

internal enum class LocalPhotoDiagnosticEvent {
    WindowStarted,
    Ready,
    Unavailable,
    Unpinned,
    WindowStopped,
}

/** No source identity, absolute timestamp, payload, exception, or process-memory estimate. */
internal data class LocalPhotoDiagnosticSample(
    val event: LocalPhotoDiagnosticEvent,
    val elapsedNanos: Long,
    val cache: LocalPhotoCacheSnapshot,
)

internal data class LocalPhotoDiagnosticReport(
    val active: Boolean,
    /** Retained even when the oldest ring samples are overwritten. */
    val baseline: LocalPhotoCacheSnapshot,
    val samples: List<LocalPhotoDiagnosticSample>,
    val overwrittenSamples: Long,
    /** Collector contention, supplier failure, or an obsolete/inconsistent observation. */
    val droppedSamples: Long,
)

/**
 * Explicit in-memory measurement windows; disabled callbacks do not read either supplier.
 * The collector never waits for its lock and drops contended/reentrant observations.
 * The product snapshot supplier still takes the cache's existing short synchronized lock;
 * opt-in sampling is not a promise that the complete observation is non-blocking.
 * No timer, worker, log, persistence, or automatic enabling is owned by this class.
 */
internal class LocalPhotoDiagnosticSampler(
    private val snapshotSupplier: () -> LocalPhotoCacheSnapshot,
    private val monotonicNanos: () -> Long = System::nanoTime,
) {
    private val lock = ReentrantLock()
    private val activeWindow = AtomicReference<Window?>(null)
    // All ring access, including the latest closed window, stays under the collector lock.
    private var latestWindow: Window? = null

    fun startWindow(): Boolean {
        if (!tryEnter()) return false
        try {
            if (activeWindow.get() != null) return false
            val origin = monotonicNanos()
            val baseline = snapshotSupplier()
            val window = Window(origin, baseline)
            window.append(LocalPhotoDiagnosticSample(LocalPhotoDiagnosticEvent.WindowStarted, 0L, baseline))
            latestWindow = window
            activeWindow.set(window)
            return true
        } catch (_: Throwable) {
            // Optional diagnostics must not throw into explicit callers or the photo producer.
            return false
        } finally {
            lock.unlock()
        }
    }

    fun record(event: LocalPhotoDiagnosticEvent) {
        // Only start/stop controls may emit window boundaries.
        if (event == LocalPhotoDiagnosticEvent.WindowStarted || event == LocalPhotoDiagnosticEvent.WindowStopped) return
        val window = activeWindow.get() ?: return
        if (!tryEnter()) {
            window.drop()
            return
        }
        try {
            if (activeWindow.get() !== window) {
                window.drop()
                return
            }
            val sample = capture(window, event) ?: return
            if (activeWindow.get() === window) window.append(sample) else window.drop()
        } catch (_: Throwable) {
            window.drop()
        } finally {
            lock.unlock()
        }
    }

    /** Disables immediately, even if an in-flight observation prevents returning its report. */
    fun stopWindow(): LocalPhotoDiagnosticReport? {
        val window = activeWindow.getAndSet(null) ?: return readWindow()
        if (!tryEnter()) {
            window.drop()
            return null
        }
        try {
            if (latestWindow === window && activeWindow.get() == null) {
                capture(window, LocalPhotoDiagnosticEvent.WindowStopped)?.let(window::append)
            } else {
                window.drop()
            }
            return window.report(active = false)
        } catch (_: Throwable) {
            window.drop()
            return null
        } finally {
            lock.unlock()
        }
    }

    /** Null means no window or busy/failed read; it never synthesizes zero measurements. */
    fun readWindow(): LocalPhotoDiagnosticReport? {
        if (!tryEnter()) return null
        try {
            val window = latestWindow ?: return null
            return window.report(active = activeWindow.get() === window)
        } catch (_: Throwable) {
            return null
        } finally {
            lock.unlock()
        }
    }

    private fun tryEnter(): Boolean = !lock.isHeldByCurrentThread && lock.tryLock()

    private fun capture(window: Window, event: LocalPhotoDiagnosticEvent): LocalPhotoDiagnosticSample? {
        try {
            val elapsed = monotonicNanos() - window.originNanos
            if (elapsed < 0L || elapsed < window.lastElapsedNanos) {
                window.drop()
                return null
            }
            // The relative time names the observation boundary, before the consistent cache read.
            return LocalPhotoDiagnosticSample(event, elapsed, snapshotSupplier())
        } catch (_: Throwable) {
            window.drop()
            return null
        }
    }

    private class Window(val originNanos: Long, val baseline: LocalPhotoCacheSnapshot) {
        private val ring = arrayOfNulls<LocalPhotoDiagnosticSample>(LOCAL_PHOTO_DIAGNOSTIC_SAMPLE_CAPACITY)
        private var first = 0
        private var size = 0
        private var overwritten = 0L
        private val dropped = AtomicLong(0L)
        var lastElapsedNanos = 0L
            private set

        fun drop() {
            dropped.updateAndGet { if (it == Long.MAX_VALUE) it else it + 1L }
        }

        fun append(sample: LocalPhotoDiagnosticSample) {
            if (size < ring.size) {
                ring[(first + size) % ring.size] = sample
                size += 1
            } else {
                ring[first] = sample
                first = (first + 1) % ring.size
                if (overwritten < Long.MAX_VALUE) overwritten += 1L
            }
            lastElapsedNanos = sample.elapsedNanos
        }

        fun report(active: Boolean): LocalPhotoDiagnosticReport = LocalPhotoDiagnosticReport(
            active = active,
            baseline = baseline,
            samples = Collections.unmodifiableList(List(size) { requireNotNull(ring[(first + it) % ring.size]) }),
            overwrittenSamples = overwritten,
            droppedSamples = dropped.get(),
        )
    }
}
