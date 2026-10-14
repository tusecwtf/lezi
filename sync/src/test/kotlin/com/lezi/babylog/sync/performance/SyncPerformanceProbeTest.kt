package com.lezi.babylog.sync.performance

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

class SyncPerformanceProbeTest {
    @Before
    @After
    fun disableCapture() {
        SyncPerformanceProbe.stopCapture()
    }

    @Test
    fun defaultAndStoppedCaptureReturnValuesWithoutReadingClock() {
        assertThat(SyncPerformanceProbe.snapshot()).isNull()
        assertThat(SyncPerformanceProbe.measure(SyncPerformanceStage.ReplicaRound) { 42 })
            .isEqualTo(42)
        SyncPerformanceProbe.startCapture { error("disabled capture must not read the clock") }
        SyncPerformanceProbe.stopCapture()
        assertThat(SyncPerformanceProbe.measure(SyncPerformanceStage.Handshake) { 43 })
            .isEqualTo(43)
        assertThat(SyncPerformanceProbe.snapshot()).isNull()
    }

    @Test
    fun fixedRingRetainsLatestSamplesInCompletionOrderAndAllTimeTotals() {
        var now = 0L
        SyncPerformanceProbe.startCapture { now }
        repeat(300) { index ->
            SyncPerformanceProbe.measure(SyncPerformanceStage.PullRequest) { now += index + 1 }
        }
        val rows = requireNotNull(SyncPerformanceProbe.stopCapture())
        assertThat(rows.map { it.stageId }).containsExactlyElementsIn(0..8).inOrder()
        val pull = rows[SyncPerformanceStage.PullRequest.id]
        assertThat(pull.completedCount).isEqualTo(300)
        assertThat(pull.failedCount).isEqualTo(0)
        assertThat(pull.activeSpans).isEqualTo(0)
        assertThat(pull.totalNanos).isEqualTo(45_150)
        assertThat(pull.maxNanos).isEqualTo(300)
        assertThat(pull.samples.map { it.durationNanos })
            .containsExactlyElementsIn((45L..300L).toList()).inOrder()
        assertThat(pull.samples.map { it.succeeded }.toSet()).containsExactly(1)
        assertThat(rows.filter { it.stageId != pull.stageId }.sumOf { it.completedCount })
            .isEqualTo(0)
    }

    @Test
    fun exceptionsAndCancellationRemainIdenticalAndAreCompletedFailures() {
        val tick = AtomicLong()
        SyncPerformanceProbe.startCapture { tick.getAndAdd(7) }
        val failures = listOf(IOException("test"), CancellationException("test"))
        failures.forEach { expected ->
            val actual = runCatching {
                SyncPerformanceProbe.measure(SyncPerformanceStage.Handshake) { throw expected }
            }.exceptionOrNull()
            assertThat(actual).isSameInstanceAs(expected)
        }
        val row = requireNotNull(SyncPerformanceProbe.snapshot())[SyncPerformanceStage.Handshake.id]
        assertThat(row.completedCount).isEqualTo(2)
        assertThat(row.failedCount).isEqualTo(2)
        assertThat(row.activeSpans).isEqualTo(0)
        assertThat(row.samples).containsExactly(
            SyncPerformanceSample(7, 0),
            SyncPerformanceSample(7, 0),
        )
    }

    @Test
    fun stopReportsActiveSpanAndItsCompletionCannotEnterRestartedCapture() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var now = 10L
        SyncPerformanceProbe.startCapture { now }
        val old = async {
            SyncPerformanceProbe.measure(SyncPerformanceStage.StageMediaDownload) {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        val inFlight = requireNotNull(SyncPerformanceProbe.snapshot())
            .get(SyncPerformanceStage.StageMediaDownload.id)
        assertThat(inFlight.activeSpans).isEqualTo(1)
        assertThat(inFlight.completedCount).isEqualTo(0)
        val stopped = requireNotNull(SyncPerformanceProbe.stopCapture())
        val active = stopped[SyncPerformanceStage.StageMediaDownload.id]
        assertThat(active.completedCount).isEqualTo(0)
        assertThat(active.activeSpans).isEqualTo(1)
        assertThat(active.samples).isEmpty()
        SyncPerformanceProbe.startCapture { now }
        now = 100L
        release.complete(Unit)
        old.await()
        SyncPerformanceProbe.measure(SyncPerformanceStage.StageMediaDownload) { now += 5 }
        val fresh = requireNotNull(SyncPerformanceProbe.stopCapture())
        assertThat(fresh[SyncPerformanceStage.StageMediaDownload.id].samples)
            .containsExactly(SyncPerformanceSample(5, 1))
        assertThat(stopped[SyncPerformanceStage.StageMediaDownload.id]).isEqualTo(active)
    }

    @Test
    fun startingAnotherCaptureAlsoDiscardsTheOldInFlightCompletion() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        SyncPerformanceProbe.startCapture()
        val old = async {
            SyncPerformanceProbe.measure(SyncPerformanceStage.MissingMediaDownload) {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        SyncPerformanceProbe.startCapture()
        release.complete(Unit)
        old.await()
        val fresh = requireNotNull(SyncPerformanceProbe.stopCapture())
        assertThat(fresh.sumOf { it.completedCount }).isEqualTo(0)
        assertThat(fresh.sumOf { it.activeSpans }).isEqualTo(0)
    }

    @Test
    fun suspendedCancellationClosesTheActiveSpan() = runTest {
        val tick = AtomicLong()
        SyncPerformanceProbe.startCapture { tick.getAndIncrement() }
        val entered = CompletableDeferred<Unit>()
        val work = async {
            SyncPerformanceProbe.measure(SyncPerformanceStage.ApplyPage) {
                entered.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
        }
        entered.await()
        work.cancel()
        work.join()
        val row = requireNotNull(SyncPerformanceProbe.snapshot())[SyncPerformanceStage.ApplyPage.id]
        assertThat(row.completedCount).isEqualTo(1)
        assertThat(row.failedCount).isEqualTo(1)
        assertThat(row.activeSpans).isEqualTo(0)
    }

    @Test
    fun concurrentCompletionsStayBoundedAndSnapshotsDoNotChange() {
        SyncPerformanceProbe.startCapture()
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val jobs = List(4) {
                pool.submit {
                    check(start.await(5, TimeUnit.SECONDS))
                    repeat(300) {
                        SyncPerformanceProbe.measure(SyncPerformanceStage.CaptureLocalChanges) { Unit }
                    }
                }
            }
            start.countDown()
            jobs.forEach { it.get(10, TimeUnit.SECONDS) }
            val before = requireNotNull(SyncPerformanceProbe.snapshot())
            SyncPerformanceProbe.measure(SyncPerformanceStage.CaptureLocalChanges) { Unit }
            val row = before[SyncPerformanceStage.CaptureLocalChanges.id]
            assertThat(row.completedCount).isEqualTo(1_200)
            assertThat(row.activeSpans).isEqualTo(0)
            assertThat(row.samples).hasSize(SyncPerformanceProbe.SAMPLES_PER_STAGE)
            assertThat(row.samples.all { it.durationNanos >= 0 }).isTrue()
            assertThat(requireNotNull(SyncPerformanceProbe.snapshot())[row.stageId].completedCount)
                .isEqualTo(1_201)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun nonLocalReturnIsASuccessAndNestedStagesRemainSeparate() {
        val tick = AtomicLong()
        SyncPerformanceProbe.startCapture { tick.getAndIncrement() }
        fun outer(): Int = SyncPerformanceProbe.measure(SyncPerformanceStage.ReplicaRound) {
            SyncPerformanceProbe.measure(SyncPerformanceStage.Handshake) { return 9 }
        }
        assertThat(outer()).isEqualTo(9)
        val rows = requireNotNull(SyncPerformanceProbe.stopCapture())
        assertThat(rows[SyncPerformanceStage.ReplicaRound.id].samples)
            .containsExactly(SyncPerformanceSample(3, 1))
        assertThat(rows[SyncPerformanceStage.Handshake.id].samples)
            .containsExactly(SyncPerformanceSample(1, 1))
    }
}
