package com.lezi.babylog.designsystem

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPhotoDiagnosticSamplerTest {
    private val empty = LocalPhotoCacheSnapshot(0, 0, 0, 0L)

    @Test
    fun disabledCallbacksAndReadsNeverReadClockOrCache() {
        val sampler = LocalPhotoDiagnosticSampler(
            snapshotSupplier = { error("disabled supplier must not execute") },
            monotonicNanos = { error("disabled clock must not execute") },
        )

        LocalPhotoDiagnosticEvent.entries.forEach(sampler::record)
        assertNull(sampler.readWindow())
        assertNull(sampler.stopWindow())
    }

    @Test
    fun explicitWindowsKeepRelativeTimesAndIndependentImmutableReports() {
        var now = 10_000L
        var reads = 0
        var counts = LocalPhotoCacheSnapshot(1, 1, 1, 64L)
        val sampler = LocalPhotoDiagnosticSampler({ reads++; counts }, { now })
        assertTrue(sampler.startWindow())
        assertFalse(sampler.startWindow())
        assertEquals(1, reads)
        val before = requireNotNull(sampler.readWindow())
        now += 75L
        counts = LocalPhotoCacheSnapshot(2, 2, 2, 128L)
        sampler.record(LocalPhotoDiagnosticEvent.Ready)
        now += 25L
        val stopped = requireNotNull(sampler.stopWindow())
        assertFalse(stopped.active)
        assertEquals(listOf(0L, 75L, 100L), stopped.samples.map { it.elapsedNanos })
        assertEquals(LocalPhotoCacheSnapshot(1, 1, 1, 64L), stopped.baseline)
        assertEquals(1, before.samples.size)
        assertEquals(before, before.copy(samples = before.samples.toList()))
        assertEquals(3, reads)

        sampler.record(LocalPhotoDiagnosticEvent.Unpinned)
        assertEquals(stopped, sampler.stopWindow())
        assertEquals(3, reads)
        now += 100L
        assertTrue(sampler.startWindow())
        val restarted = requireNotNull(sampler.readWindow())
        assertEquals(1, restarted.samples.size)
        assertEquals(0L, restarted.samples.single().elapsedNanos)
        assertEquals(counts, restarted.baseline)
        assertEquals(0L, restarted.droppedSamples)
        assertEquals(0L, restarted.overwrittenSamples)
        try {
            (before.samples as MutableList<LocalPhotoDiagnosticSample>).clear()
            throw AssertionError("reports must not expose a mutable ring view")
        } catch (_: UnsupportedOperationException) {
            assertEquals(1, before.samples.size)
        }
    }

    @Test
    fun ringOverwritesOldestButKeepsBaselineAndExplicitOverflowCount() {
        var now = 100L
        val sampler = LocalPhotoDiagnosticSampler({ empty }, { now })
        assertTrue(sampler.startWindow())
        repeat(70) {
            now += 1L
            sampler.record(LocalPhotoDiagnosticEvent.Ready)
        }
        now += 1L
        val result = requireNotNull(sampler.stopWindow())
        assertEquals(64, result.samples.size)
        assertEquals(8L, result.overwrittenSamples)
        assertEquals(8L, result.samples.first().elapsedNanos)
        assertEquals(71L, result.samples.last().elapsedNanos)
        assertEquals(LocalPhotoDiagnosticEvent.WindowStopped, result.samples.last().event)
        assertEquals(empty, result.baseline)
        assertEquals(0L, result.droppedSamples)
    }

    @Test
    fun diagnosticFailuresNeverThrowOrPreventWindowShutdown() {
        var snapshotFailure: Throwable? = AssertionError("private diagnostic details")
        var clockFailure: Throwable? = null
        val sampler = LocalPhotoDiagnosticSampler(
            snapshotSupplier = { snapshotFailure?.let { throw it }; empty },
            monotonicNanos = { clockFailure?.let { throw it }; 100L },
        )
        assertFalse(sampler.startWindow())
        assertNull(sampler.readWindow())
        snapshotFailure = null
        clockFailure = OutOfMemoryError("clock diagnostics")
        assertFalse(sampler.startWindow())
        clockFailure = null
        assertTrue(sampler.startWindow())

        clockFailure = CancellationException("diagnostic clock only")
        sampler.record(LocalPhotoDiagnosticEvent.Ready)
        clockFailure = null
        snapshotFailure = AssertionError("snapshot diagnostics")
        sampler.record(LocalPhotoDiagnosticEvent.Unavailable)
        val result = requireNotNull(sampler.stopWindow())
        assertFalse(result.active)
        assertEquals(3L, result.droppedSamples)
        assertEquals(listOf(LocalPhotoDiagnosticEvent.WindowStarted), result.samples.map { it.event })
        assertFalse(result.toString().contains("private diagnostic details"))
        assertEquals(result, sampler.readWindow())
        sampler.record(LocalPhotoDiagnosticEvent.Unpinned)
        assertEquals(result, sampler.readWindow())
    }

    @Test
    fun regressingClockIsDroppedWithoutInventingTimeOrReadingSnapshot() {
        var now = 100L
        var reads = 0
        val sampler = LocalPhotoDiagnosticSampler({ reads++; empty }, { now })
        assertTrue(sampler.startWindow())
        now = 150L
        sampler.record(LocalPhotoDiagnosticEvent.Ready)
        now = 140L
        sampler.record(LocalPhotoDiagnosticEvent.Ready)
        now = 80L
        sampler.record(LocalPhotoDiagnosticEvent.Ready)
        now = 150L
        sampler.record(LocalPhotoDiagnosticEvent.Unavailable)
        val result = requireNotNull(sampler.readWindow())
        assertEquals(listOf(0L, 50L, 50L), result.samples.map { it.elapsedNanos })
        assertEquals(2L, result.droppedSamples)
        assertEquals(3, reads)
    }

    @Test(timeout = 5_000L)
    fun contendedCallbacksDropAndStopDisablesWithoutWaitingForObservation() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val block = AtomicBoolean(false)
        val cacheReads = AtomicInteger()
        val clockReads = AtomicInteger()
        val sampler = LocalPhotoDiagnosticSampler(
            snapshotSupplier = {
                cacheReads.incrementAndGet()
                if (block.get()) {
                    entered.countDown()
                    check(release.await(3L, TimeUnit.SECONDS))
                }
                empty
            },
            monotonicNanos = { clockReads.incrementAndGet().toLong() },
        )
        assertTrue(sampler.startWindow())
        block.set(true)
        val observer = Thread { sampler.record(LocalPhotoDiagnosticEvent.Ready) }
        observer.start()
        try {
            assertTrue(entered.await(2L, TimeUnit.SECONDS))
            sampler.record(LocalPhotoDiagnosticEvent.Unavailable)
            assertNull(sampler.readWindow())
            assertNull(sampler.stopWindow())
            sampler.record(LocalPhotoDiagnosticEvent.Unpinned)
            assertEquals(2, cacheReads.get())
            assertEquals(2, clockReads.get())
        } finally {
            release.countDown()
            observer.join(2_000L)
        }
        assertFalse(observer.isAlive)
        val stopped = requireNotNull(sampler.readWindow())
        assertFalse(stopped.active)
        assertEquals(3L, stopped.droppedSamples)
        assertEquals(1, stopped.samples.size)
    }

    @Test
    fun reentrantObservationIsDroppedInsteadOfRecursivelyReadingCache() {
        var reenter = false
        var reads = 0
        lateinit var sampler: LocalPhotoDiagnosticSampler
        sampler = LocalPhotoDiagnosticSampler(
            snapshotSupplier = {
                reads++
                if (reenter) {
                    sampler.record(LocalPhotoDiagnosticEvent.Unpinned)
                    assertNull(sampler.readWindow())
                }
                empty
            },
            monotonicNanos = { 100L },
        )
        assertTrue(sampler.startWindow())
        reenter = true
        sampler.record(LocalPhotoDiagnosticEvent.Ready)
        val result = requireNotNull(sampler.readWindow())
        assertEquals(2, reads)
        assertEquals(2, result.samples.size)
        assertEquals(1L, result.droppedSamples)
    }

    @Test
    fun samplesFollowTheRealCachePinsAndKeepThumbnailBaselineAfterPreviewUnpins() = runBlocking {
        val released = mutableListOf<String>()
        val cache = LocalPhotoMemoryCache<String>(maxFullscreenEntries = 2, maxDecodedBytes = 192L) {
            released += it
        }
        val source = object : LocalPhotoDecodeSource<String> {
            override suspend fun inspect(path: String): LocalPhotoSourceInfo? =
                if (path == "unavailable") null else LocalPhotoSourceInfo(4, 4, LocalPhotoOrientation.NORMAL)
            override suspend fun decode(path: String, sampleSize: Int) = LocalPhotoDecoded(path, 4, 4)
            override suspend fun applyOrientation(decoded: LocalPhotoDecoded<String>, orientation: LocalPhotoOrientation) = decoded
            override fun release(decoded: String) { released += decoded }
        }
        val loader = BoundedLocalPhotoLoader(source, cache)
        loader.load(LocalPhotoDecodeRequest("private-thumbnail", LocalPhotoTarget.THUMBNAIL))
        var now = 100L
        val sampler = LocalPhotoDiagnosticSampler(cache::snapshot) { now++ }
        assertTrue(sampler.startWindow())
        val previews = (0..2).map { index ->
            val loaded = loader.load(LocalPhotoDecodeRequest("private-full-$index", LocalPhotoTarget.FULLSCREEN))
                as LocalPhotoLoadResult.Ready<String>
            sampler.record(LocalPhotoDiagnosticEvent.Ready)
            loaded
        }
        assertTrue(released.isEmpty())
        assertEquals(LocalPhotoCacheSnapshot(4, 4, 4, 256L), requireNotNull(sampler.readWindow()).samples.last().cache)
        previews.forEach { loaded ->
            cache.unpin(loaded.plan.cacheKey)
            sampler.record(LocalPhotoDiagnosticEvent.Unpinned)
        }
        assertEquals(listOf("private-full-0"), released)
        val unavailable = loader.load(LocalPhotoDecodeRequest("unavailable", LocalPhotoTarget.FULLSCREEN))
        assertEquals(LocalPhotoLoadResult.Unavailable, unavailable)
        sampler.record(LocalPhotoDiagnosticEvent.Unavailable)
        val result = requireNotNull(sampler.stopWindow())
        assertEquals(LocalPhotoCacheSnapshot(1, 1, 1, 64L), result.baseline)
        assertEquals(LocalPhotoCacheSnapshot(3, 1, 1, 192L), result.samples.last().cache)
        assertEquals(result.baseline.totalPins, result.samples.last().cache.totalPins)
        assertEquals(cache.snapshot(), result.samples.last().cache)
        assertFalse(result.toString().contains("private-"))
        assertFalse(result.toString().contains("unavailable"))
        assertEquals(0L, result.droppedSamples)
        assertEquals(0L, result.overwrittenSamples)
    }
}
