package com.lezi.babylog.feature.settings.calendar

import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BroadcastWorkBoundaryTest {
    @Test
    fun nestedCancellationEscapesAsTheOriginalInstanceAndStillFinishes() = runTest {
        val cancellation = CancellationException("receiver stopped")
        var finishCalls = 0
        val reported = mutableListOf<Exception>()

        val actual = runCatching {
            runBroadcastWork(
                finish = { finishCalls += 1 },
                reportFailure = reported::add,
                work = { throw IllegalStateException("calendar failed", cancellation) },
            )
        }.exceptionOrNull()

        assertSame(cancellation, actual)
        assertTrue(reported.isEmpty())
        assertEquals(1, finishCalls)
    }

    @Test
    fun businessTimeoutUsesTheFailureFeedbackPathAndStillFinishes() = runTest {
        val timeout = TimeoutException("provider timed out")
        var finishCalls = 0
        val reported = mutableListOf<Exception>()

        runBroadcastWork(
            finish = { finishCalls += 1 },
            reportFailure = reported::add,
            work = { throw timeout },
        )

        assertEquals(listOf(timeout), reported)
        assertEquals(1, finishCalls)
    }
}
