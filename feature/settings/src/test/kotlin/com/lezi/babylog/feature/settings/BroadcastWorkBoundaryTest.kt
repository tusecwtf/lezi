package com.lezi.babylog.feature.settings

import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class BroadcastWorkBoundaryTest {
    @Test
    fun operationalFailureIsReportedWithoutEscapingAndAlwaysFinishes() = runBlocking {
        val failure = IllegalStateException("settings unavailable")
        val reported = mutableListOf<Exception>()
        var finishCount = 0

        runBroadcastWork(
            finish = { finishCount += 1 },
            reportFailure = reported::add,
        ) {
            throw failure
        }

        assertEquals(1, finishCount)
        assertEquals(1, reported.size)
        assertSame(failure, reported.single())
    }

    @Test
    fun successfulWorkFinishesWithoutReportingFailure() = runBlocking {
        val reported = mutableListOf<Exception>()
        var finishCount = 0
        var workCount = 0

        runBroadcastWork(
            finish = { finishCount += 1 },
            reportFailure = reported::add,
        ) {
            workCount += 1
        }

        assertEquals(1, workCount)
        assertEquals(1, finishCount)
        assertEquals(emptyList<Exception>(), reported)
    }

    @Test
    fun cancellationPropagatesUnchangedAfterFinishing() = runBlocking {
        val cancellation = CancellationException("receiver stopped")
        val reported = mutableListOf<Exception>()
        var finishCount = 0

        try {
            runBroadcastWork(
                finish = { finishCount += 1 },
                reportFailure = reported::add,
            ) {
                throw cancellation
            }
            fail("Expected cancellation to propagate")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }

        assertEquals(1, finishCount)
        assertEquals(emptyList<Exception>(), reported)
    }

    @Test
    fun wrappedCancellationStillPropagatesAsCancellationAfterFinishing() = runBlocking {
        val cancellation = CancellationException("storage read cancelled")
        val wrapped = IllegalStateException("storage wrapper", cancellation)
        val reported = mutableListOf<Exception>()
        var finishCount = 0

        try {
            runBroadcastWork(
                finish = { finishCount += 1 },
                reportFailure = reported::add,
            ) {
                throw wrapped
            }
            fail("Expected nested cancellation to propagate")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }

        assertEquals(1, finishCount)
        assertEquals(emptyList<Exception>(), reported)
    }
}
