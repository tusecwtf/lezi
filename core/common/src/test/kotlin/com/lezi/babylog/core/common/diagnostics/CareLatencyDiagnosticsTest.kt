package com.lezi.babylog.core.common.diagnostics

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import org.junit.Test

class CareLatencyDiagnosticsTest {
    @Test
    fun disabledPathDoesNotReadClockOrMakeSamples() {
        val calls = AtomicLong()
        val window = CareLatencyDiagnostics.openDebugWindow { calls.incrementAndGet() }
        window.close()
        assertThat(CareLatencyDiagnostics.begin(CareLatencyStage.LOCAL_TRANSACTION, CareLatencyOperation.CREATE_RECORD))
            .isNull()
        assertThat(CareLatencyDiagnostics.measure(CareLatencyStage.LOCAL_TRANSACTION, CareLatencyOperation.CREATE_RECORD) { 7 })
            .isEqualTo(7)
        assertThat(calls.get()).isEqualTo(0)
        assertThat(window.snapshot().samples).isEmpty()
    }

    @Test
    fun oneClockExactDurationAndRepeatedFinishCannotChangeSuccess() {
        var nanos = 10L
        CareLatencyDiagnostics.openDebugWindow { nanos }.use { window ->
            val span = requireNotNull(CareLatencyDiagnostics.begin(
                CareLatencyStage.LOCAL_TRANSACTION, CareLatencyOperation.EDIT_RECORD,
                mediaCount = 2, rootCount = -1,
            ))
            nanos = 47
            span.finish()
            span.finish(CareLatencyOutcome.FAILED)
            assertThat(window.snapshot().samples.single()).isEqualTo(
                CareLatencySample(1, CareLatencyStage.LOCAL_TRANSACTION, CareLatencyOperation.EDIT_RECORD,
                    CareLatencyOutcome.SUCCESS, 37, 2, -1, CareLatencyBytes.UNKNOWN),
            )
        }
    }

    @Test
    fun actualOneTwoThreeAndUnknownCountsAreNotRewritten() {
        CareLatencyDiagnostics.openDebugWindow { 1L }.use { window ->
            for (count in listOf(-1, 0, 1, 2, 3, 192)) {
                CareLatencyDiagnostics.begin(CareLatencyStage.MEDIA_RECEIPTS, CareLatencyOperation.COMMIT_BATCH,
                    mediaCount = count, rootCount = 64)?.finish()
            }
            assertThat(window.snapshot().samples.map { it.mediaCount }).containsExactly(-1, 0, 1, 2, 3, 192).inOrder()
        }
    }

    @Test
    fun invalidCountsAndClockRegressionAreRejectedWithoutFakeZeroDuration() {
        var nanos = 100L
        CareLatencyDiagnostics.openDebugWindow { nanos }.use { window ->
            assertThat(CareLatencyDiagnostics.begin(CareLatencyStage.LOCAL_TRANSACTION,
                CareLatencyOperation.CREATE_RECORD, mediaCount = 4097)).isNull()
            assertThat(CareLatencyDiagnostics.begin(CareLatencyStage.LOCAL_TRANSACTION,
                CareLatencyOperation.CREATE_RECORD, rootCount = -2)).isNull()
            val span = requireNotNull(CareLatencyDiagnostics.begin(CareLatencyStage.LOCAL_TRANSACTION,
                CareLatencyOperation.CREATE_RECORD))
            nanos = 99
            span.finish()
            assertThat(window.snapshot().samples).isEmpty()
            assertThat(window.snapshot().rejected).isEqualTo(3)
        }
    }

    @Test
    fun completedRingEvictsOldestAndOpenLimitReportsMissingEndpoints() {
        val nanos = AtomicLong()
        CareLatencyDiagnostics.openDebugWindow { nanos.incrementAndGet() }.use { window ->
            repeat(300) {
                CareLatencyDiagnostics.begin(CareLatencyStage.LOCAL_TRANSACTION, CareLatencyOperation.CREATE_RECORD)?.finish()
            }
            assertThat(window.snapshot().samples).hasSize(256)
            assertThat(window.snapshot().samples.first().spanSequence).isEqualTo(45)
            assertThat(window.snapshot().overwritten).isEqualTo(44)
            repeat(20) { CareLatencyDiagnostics.begin(CareLatencyStage.PAGE_APPLY, CareLatencyOperation.PULL_PAGE) }
            assertThat(window.snapshot().capacityDropped).isEqualTo(4)
            window.close()
            assertThat(window.snapshot().incomplete).isEqualTo(16)
        }
    }

    @Test
    fun clockFailuresNeverReplaceTheBusinessReturnOrException() {
        CareLatencyDiagnostics.openDebugWindow { error("secret clock detail") }.use { window ->
            val value = CareLatencyDiagnostics.measure(CareLatencyStage.LOCAL_TRANSACTION,
                CareLatencyOperation.CREATE_RECORD) { 19 }
            val business = IllegalStateException("private record content")
            val caught = runCatching {
                CareLatencyDiagnostics.measure(CareLatencyStage.LOCAL_TRANSACTION,
                    CareLatencyOperation.EDIT_RECORD) { throw business }
            }.exceptionOrNull()
            assertThat(value).isEqualTo(19)
            assertThat(caught).isSameInstanceAs(business)
            assertThat(window.snapshot().samples).isEmpty()
            assertThat(window.snapshot().toString()).doesNotContain("secret")
            assertThat(window.snapshot().toString()).doesNotContain("private")
        }
    }

    @Test
    fun endClockFailureRetiresTheTokenAndPreservesTheBusinessValue() {
        var calls = 0
        CareLatencyDiagnostics.openDebugWindow {
            if (++calls % 2 == 0) error("clock end failed") else calls.toLong()
        }.use { window ->
            repeat(20) {
                assertThat(CareLatencyDiagnostics.measure(CareLatencyStage.LOCAL_TRANSACTION,
                    CareLatencyOperation.CREATE_RECORD) { 23 }).isEqualTo(23)
            }
            assertThat(window.snapshot().samples).isEmpty()
            assertThat(window.snapshot().capacityDropped).isEqualTo(0)
            assertThat(window.snapshot().rejected).isEqualTo(20)
        }
    }

    @Test
    fun closingOldWindowCannotPublishItsTokenIntoAnotherClockDomain() {
        val old = CareLatencyDiagnostics.openDebugWindow { 100L }
        val span = requireNotNull(CareLatencyDiagnostics.begin(CareLatencyStage.LOCAL_TRANSACTION,
            CareLatencyOperation.CREATE_RECORD))
        old.close()
        CareLatencyDiagnostics.openDebugWindow { 999999L }.use { current ->
            span.finish()
            CareLatencyDiagnostics.begin(CareLatencyStage.PAGE_APPLY, CareLatencyOperation.PULL_PAGE)?.finish()
            assertThat(current.snapshot().samples.single().stage).isEqualTo(CareLatencyStage.PAGE_APPLY)
            assertThat(old.snapshot().samples).isEmpty()
            assertThat(old.snapshot().incomplete).isEqualTo(1)
        }
    }

    @Test
    fun concurrentOrdinarySpansStayBoundedAndUseUniqueProcessCounters() {
        val nanos = AtomicLong()
        CareLatencyDiagnostics.openDebugWindow { nanos.incrementAndGet() }.use { window ->
            val start = CountDownLatch(1)
            val threads = List(4) {
                Thread {
                    start.await()
                    repeat(30) {
                        CareLatencyDiagnostics.begin(CareLatencyStage.PAGE_APPLY, CareLatencyOperation.PULL_PAGE)?.finish()
                    }
                }.apply { start() }
            }
            start.countDown()
            threads.forEach { it.join() }
            val snapshot = window.snapshot()
            assertThat(snapshot.samples).isNotEmpty()
            assertThat(snapshot.samples.size).isAtMost(120)
            assertThat(snapshot.samples.map { it.spanSequence }.distinct()).hasSize(snapshot.samples.size)
            assertThat(snapshot.samples.all { it.durationNanos >= 0 }).isTrue()
            assertThat(snapshot.capacityDropped).isEqualTo(0)
        }
    }
}
