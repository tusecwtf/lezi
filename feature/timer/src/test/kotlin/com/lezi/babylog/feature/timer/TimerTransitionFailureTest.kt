package com.lezi.babylog.feature.timer

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Regression for total transition failure cover: DataStore IOException must not leave
 * UI in STARTING/RUNNING while the foreground service keeps running.
 */
class TimerTransitionFailureTest {
    @Test
    fun startingPersistIoExceptionStopsServiceAndSettlesFailedWithoutStarting() = runBlocking {
        val published = mutableListOf<TimerState>()
        var memory: TimerState? = null
        var stops = 0
        var serviceStarts = 0

        val result = startTimerWithConfirmation(
            candidate = runningLeftCandidate(),
            publish = {
                published += it
                throw IOException("STARTING persist failed")
            },
            startService = {
                serviceStarts += 1
                TimerServiceStartResult.Started
            },
            stopService = { stops += 1 },
            publishMemoryOnly = { memory = it },
        )

        assertTrue(result is TimerServiceStartResult.Failed)
        assertEquals(TimerServiceFailure.RUNTIME, (result as TimerServiceStartResult.Failed).failure)
        assertEquals(0, serviceStarts)
        assertEquals(1, stops)
        val failed = requireNotNull(memory)
        assertEquals(TimerServiceState.FAILED, failed.serviceState)
        assertEquals(TimerServiceFailure.RUNTIME, failed.serviceFailure)
        assertEquals("L", failed.requestedSide)
        assertEquals("timer-session", failed.completionClientUuid)
        assertEquals(42L, failed.babyId)
        assertFalse(failed.leftRunning)
        assertFalse(failed.rightRunning)
        // Durable STARTING must not remain the last successful publish.
        assertTrue(published.none { it.serviceState == TimerServiceState.RUNNING })
    }

    @Test
    fun runningPersistIoExceptionAfterAckStopsServiceAndSettlesFailedInMemory() = runBlocking {
        val durable = mutableListOf<TimerState>()
        var memory: TimerState? = null
        var stops = 0

        val result = startTimerWithConfirmation(
            candidate = runningLeftCandidate(leftAccumMs = 5_000L),
            publish = { next ->
                durable += next
                if (next.serviceState == TimerServiceState.RUNNING) {
                    throw IOException("RUNNING persist failed")
                }
            },
            startService = { TimerServiceStartResult.Started },
            stopService = { stops += 1 },
            publishMemoryOnly = { memory = it },
        )

        assertTrue(result is TimerServiceStartResult.Failed)
        assertEquals(1, stops)
        assertEquals(TimerServiceState.STARTING, durable.first().serviceState)
        // RUNNING write was attempted then aborted — must not remain the settled UI state.
        assertTrue(durable.any { it.serviceState == TimerServiceState.RUNNING })
        val failed = requireNotNull(memory)
        assertEquals(TimerServiceState.FAILED, failed.serviceState)
        assertEquals(TimerServiceFailure.RUNTIME, failed.serviceFailure)
        assertEquals("L", failed.requestedSide)
        assertEquals(5_000L, failed.leftAccumMs)
        assertEquals("timer-session", failed.completionClientUuid)
        assertFalse(failed.leftRunning)
    }

    @Test
    fun failedPersistFailureStillUpdatesMemoryBeforeReturning() = runBlocking {
        val durable = mutableListOf<TimerState>()
        var memory: TimerState? = null
        var stops = 0

        val result = startTimerWithConfirmation(
            candidate = runningLeftCandidate(),
            publish = { next ->
                durable += next
                if (next.serviceState == TimerServiceState.FAILED) {
                    throw IOException("FAILED persist failed")
                }
            },
            startService = {
                TimerServiceStartResult.Failed(TimerServiceFailure.NOTIFICATION)
            },
            stopService = { stops += 1 },
            publishMemoryOnly = { memory = it },
        )

        assertEquals(
            TimerServiceStartResult.Failed(TimerServiceFailure.NOTIFICATION),
            result,
        )
        assertEquals(1, stops)
        assertEquals(TimerServiceState.STARTING, durable[0].serviceState)
        // Second durable attempt was FAILED and threw — memory must still settle.
        assertEquals(TimerServiceState.FAILED, durable.getOrNull(1)?.serviceState)
        val failed = requireNotNull(memory)
        assertEquals(TimerServiceState.FAILED, failed.serviceState)
        assertEquals(TimerServiceFailure.NOTIFICATION, failed.serviceFailure)
        assertEquals("L", failed.requestedSide)
    }

    @Test
    fun pausePersistIoExceptionStopsServiceAndSettlesFailedWithSession() = runBlocking {
        val durable = mutableListOf<TimerState>()
        var memory: TimerState? = null
        var stops = 0
        val paused = TimerState(
            babyId = 42L,
            completionClientUuid = "timer-session",
            leftAccumMs = 12_000L,
            lastSide = "L",
            order = "L",
            sessionStartedAt = 1_700_000_000_000L,
        )

        settleNonRunningTimerTransition(
            next = paused,
            publish = {
                durable += it
                throw IOException("pause persist failed")
            },
            stopService = { stops += 1 },
            publishMemoryOnly = { memory = it },
        )

        assertEquals(1, stops)
        // Durable write may have attempted PAUSED then failed — memory must not stay PAUSED.
        assertTrue(durable.all { it.serviceState == TimerServiceState.PAUSED })
        val failed = requireNotNull(memory)
        assertEquals(TimerServiceState.FAILED, failed.serviceState)
        assertEquals(TimerServiceFailure.RUNTIME, failed.serviceFailure)
        assertEquals("L", failed.requestedSide)
        assertEquals(12_000L, failed.leftAccumMs)
        assertEquals("timer-session", failed.completionClientUuid)
    }

    @Test
    fun clearPersistIoExceptionStillStopsAndAppliesEmptyMemory() = runBlocking {
        var memory: TimerState? = null
        var stops = 0

        settleNonRunningTimerTransition(
            next = TimerState(),
            publish = { throw IOException("clear persist failed") },
            stopService = { stops += 1 },
            publishMemoryOnly = { memory = it },
        )

        assertEquals(1, stops)
        assertEquals(TimerState(), memory)
    }

    @Test
    fun cancellationAfterServiceRiskStopsThenRethrowsUnchanged() = runBlocking {
        var stops = 0
        val cancelled = CancellationException("scope cancelled")

        try {
            startTimerWithConfirmation(
                candidate = runningLeftCandidate(),
                publish = { /* STARTING ok */ },
                startService = { throw cancelled },
                stopService = { stops += 1 },
                publishMemoryOnly = {},
            )
            fail("expected CancellationException")
        } catch (thrown: CancellationException) {
            assertSame(cancelled, thrown)
        }
        assertEquals(1, stops)
    }

    @Test
    fun cancellationOnStartingPublishStopsThenRethrows() = runBlocking {
        var stops = 0
        var serviceStarts = 0
        val cancelled = CancellationException("persist cancelled")

        try {
            startTimerWithConfirmation(
                candidate = runningLeftCandidate(),
                publish = { throw cancelled },
                startService = {
                    serviceStarts += 1
                    TimerServiceStartResult.Started
                },
                stopService = { stops += 1 },
                publishMemoryOnly = {},
            )
            fail("expected CancellationException")
        } catch (thrown: CancellationException) {
            assertSame(cancelled, thrown)
        }
        assertEquals(0, serviceStarts)
        assertEquals(1, stops)
    }

    @Test
    fun serviceRuntimeTimeoutAndNotificationShareSingleStopCover() = runBlocking {
        data class Case(
            val label: String,
            val start: suspend () -> TimerServiceStartResult,
            val expected: TimerServiceFailure,
        )
        val cases = listOf(
            Case("runtime", { throw IllegalArgumentException("boom") }, TimerServiceFailure.RUNTIME),
            Case(
                "timeout",
                { TimerServiceStartResult.Failed(TimerServiceFailure.TIMEOUT) },
                TimerServiceFailure.TIMEOUT,
            ),
            Case(
                "notification",
                {
                    confirmTimerServiceStartup {
                        throw TimerNotificationCreationException(IllegalStateException("channel"))
                    }
                },
                TimerServiceFailure.NOTIFICATION,
            ),
        )
        for (case in cases) {
            var stops = 0
            var memory: TimerState? = null
            val result = startTimerWithConfirmation(
                candidate = runningLeftCandidate(),
                publish = { next ->
                    if (next.serviceState == TimerServiceState.FAILED) {
                        throw IOException("failed write for ${case.label}")
                    }
                },
                startService = case.start,
                stopService = { stops += 1 },
                publishMemoryOnly = { memory = it },
            )
            assertEquals(case.label, TimerServiceStartResult.Failed(case.expected), result)
            assertEquals(case.label, 1, stops)
            assertEquals(case.label, TimerServiceState.FAILED, memory?.serviceState)
            assertEquals(case.label, case.expected, memory?.serviceFailure)
        }
    }

    @Test
    fun retryFromFailedKeepsStableSessionAndShowsRunningOnlyAfterSuccess() = runBlocking {
        val failed = runningLeftCandidate(leftAccumMs = 3_000L)
            .pausedForServiceStart()
            .copy(
                serviceState = TimerServiceState.FAILED,
                serviceFailure = TimerServiceFailure.RUNTIME,
            )
        val retry = failed.retryServiceStartCandidate(
            nowElapsed = 9_000L,
            nowWall = 1_700_000_009_000L,
        )
        requireNotNull(retry)
        assertEquals("timer-session", retry.completionClientUuid)

        val published = mutableListOf<TimerState>()
        startTimerWithConfirmation(
            candidate = retry,
            publish = { published += it },
            startService = { TimerServiceStartResult.Started },
            stopService = {},
            publishMemoryOnly = {},
        )

        assertEquals(TimerServiceState.STARTING, published.first().serviceState)
        assertEquals(TimerServiceState.RUNNING, published.last().serviceState)
        assertEquals("timer-session", published.last().completionClientUuid)
        assertTrue(published.last().leftRunning)
        assertNull(published.last().serviceFailure)
    }

    @Test
    fun storageFaultRestoreMirrorsTransitionFailedStopPolicy() {
        val corruptedRunning = runningLeftCandidate(leftAccumMs = 8_000L)
            .copy(serviceState = TimerServiceState.RUNNING)
        val settled = restoreTimerAfterStorageFault(
            raw = corruptedRunning.toJson(
                savedElapsed = 1_250L,
                savedWall = 1_700_000_000_250L,
                savedBootCount = 4L,
            ),
            nowElapsed = 1_500L,
            nowWall = 1_700_000_000_500L,
            nowBootCount = 4L,
            activeServiceSession = null,
            readFailed = true,
        )
        // Read failed: cannot trust disk — empty session + must stop (caller).
        assertEquals(TimerState(), settled.state)
        assertTrue(settled.shouldStopService)

        val badJson = restoreTimerAfterStorageFault(
            raw = "{not-json",
            nowElapsed = 1_500L,
            nowWall = 1_700_000_000_500L,
            nowBootCount = 4L,
            activeServiceSession = null,
            readFailed = false,
        )
        assertEquals(TimerState(), badJson.state)
        assertTrue(badJson.shouldStopService)

        val recoverable = restoreTimerAfterStorageFault(
            raw = corruptedRunning.toJson(
                savedElapsed = 1_250L,
                savedWall = 1_700_000_000_250L,
                savedBootCount = 4L,
            ),
            nowElapsed = 1_500L,
            nowWall = 1_700_000_000_500L,
            nowBootCount = 4L,
            activeServiceSession = null,
            readFailed = false,
        )
        assertEquals(TimerServiceState.RECOVERABLE, recoverable.state.serviceState)
        assertEquals("L", recoverable.state.requestedSide)
        assertTrue(recoverable.shouldStopService)

        val live = restoreTimerAfterStorageFault(
            raw = corruptedRunning.toJson(
                savedElapsed = 1_250L,
                savedWall = 1_700_000_000_250L,
                savedBootCount = 4L,
            ),
            nowElapsed = 1_500L,
            nowWall = 1_700_000_000_500L,
            nowBootCount = 4L,
            activeServiceSession = "timer-session",
            readFailed = false,
        )
        assertEquals(TimerServiceState.RUNNING, live.state.serviceState)
        assertFalse(live.shouldStopService)
    }

    @Test
    fun toFailedRetryableKeepsAccumSideAndSession() {
        val pending = runningLeftCandidate(leftAccumMs = 7_000L).pausedForServiceStart()
        val failed = pending.toFailedRetryable(TimerServiceFailure.TIMEOUT)
        assertEquals(TimerServiceState.FAILED, failed.serviceState)
        assertEquals(TimerServiceFailure.TIMEOUT, failed.serviceFailure)
        assertEquals("L", failed.requestedSide)
        assertEquals(7_000L, failed.leftAccumMs)
        assertEquals("timer-session", failed.completionClientUuid)
        assertFalse(failed.leftRunning)
        assertNull(failed.leftStartedElapsed)
    }

    private fun runningLeftCandidate(leftAccumMs: Long = 0L) = TimerState(
        babyId = 42L,
        completionClientUuid = "timer-session",
        leftRunning = true,
        leftAccumMs = leftAccumMs,
        leftStartedElapsed = 1_000L,
        sessionStartedAt = 1_700_000_000_000L,
        lastSide = "L",
        order = "L",
    )
}
