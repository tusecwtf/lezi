package com.lezi.babylog.feature.timer

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerServiceStartRecoveryTest {
    @Test
    fun successPublishesPausedPendingBeforeConfirmedRunning() = runBlocking {
        val published = mutableListOf<TimerState>()
        val candidate = runningLeftCandidate()

        val result = startTimerWithConfirmation(
            candidate = candidate,
            publish = { published += it },
            startService = { TimerServiceStartResult.Started },
        )

        assertEquals(TimerServiceStartResult.Started, result)
        assertEquals(2, published.size)
        assertFalse(published[0].leftRunning)
        assertEquals(TimerServiceState.STARTING, published[0].serviceState)
        assertEquals("L", published[0].requestedSide)
        assertTrue(published[1].leftRunning)
        assertEquals(TimerServiceState.RUNNING, published[1].serviceState)
        assertNull(published[1].requestedSide)
    }

    @Test
    fun everyStartFailureReturnsToPausedRetryableStateWithoutLosingIntent() = runBlocking {
        TimerServiceFailure.entries.forEach { failure ->
            val published = mutableListOf<TimerState>()
            val candidate = runningLeftCandidate()

            val result = startTimerWithConfirmation(
                candidate = candidate,
                publish = { published += it },
                startService = { TimerServiceStartResult.Failed(failure) },
            )

            assertEquals(TimerServiceStartResult.Failed(failure), result)
            val failed = published.last()
            assertFalse(failed.leftRunning)
            assertFalse(failed.rightRunning)
            assertEquals(TimerServiceState.FAILED, failed.serviceState)
            assertEquals(failure, failed.serviceFailure)
            assertEquals("L", failed.requestedSide)
            assertEquals(42L, failed.babyId)
            assertEquals("timer-session", failed.completionClientUuid)
            assertEquals(0L, failed.leftAccumMs)
        }
    }

    @Test
    fun platformExceptionsAreClassifiedAndDoNotEscapeTheStartBoundary() = runBlocking {
        val permission = captureFailure(SecurityException("denied"))
        val restricted = captureFailure(ForegroundServiceStartNotAllowedForTest())
        val runtime = captureFailure(IllegalArgumentException("bad notification"))

        assertEquals(TimerServiceFailure.PERMISSION, permission)
        assertEquals(TimerServiceFailure.RESTRICTED, restricted)
        assertEquals(TimerServiceFailure.RUNTIME, runtime)
    }

    @Test
    fun notificationCreationFailureUsesTheSameRecoveryContract() = runBlocking {
        val published = mutableListOf<TimerState>()

        startTimerWithConfirmation(
            candidate = runningLeftCandidate(),
            publish = { published += it },
            startService = {
                confirmTimerServiceStartup {
                    throw TimerNotificationCreationException(IllegalStateException("channel"))
                }
            },
        )

        assertEquals(TimerServiceState.FAILED, published.last().serviceState)
        assertEquals(TimerServiceFailure.NOTIFICATION, published.last().serviceFailure)
    }

    @Test
    fun repeatedClickWhileStartIsPendingIsRejected() {
        val pending = runningLeftCandidate().pausedForServiceStart()

        assertFalse(pending.canRequestServiceStart())
        assertTrue(pending.copy(serviceState = TimerServiceState.FAILED).canRequestServiceStart())
    }

    @Test
    fun failedRequestCanRetryAndOnlyBecomesRunningAfterSuccess() = runBlocking {
        val failed = runningLeftCandidate()
            .pausedForServiceStart()
            .copy(
                serviceState = TimerServiceState.FAILED,
                serviceFailure = TimerServiceFailure.RESTRICTED,
            )
        val retry = failed.retryServiceStartCandidate(
            nowElapsed = 2_000L,
            nowWall = 1_700_000_001_000L,
        )
        requireNotNull(retry)
        val published = mutableListOf<TimerState>()

        startTimerWithConfirmation(
            candidate = retry,
            publish = { published += it },
            startService = { TimerServiceStartResult.Started },
        )

        assertEquals(TimerServiceState.STARTING, published.first().serviceState)
        assertEquals(TimerServiceState.RUNNING, published.last().serviceState)
        assertTrue(published.last().leftRunning)
        assertEquals(42L, published.last().babyId)
        assertEquals("timer-session", published.last().completionClientUuid)
    }

    @Test
    fun restartDistinguishesRecoverablePendingFailedAndOrdinaryPausedStates() {
        val pending = runningLeftCandidate().pausedForServiceStart()
        val failed = pending.copy(
            serviceState = TimerServiceState.FAILED,
            serviceFailure = TimerServiceFailure.PERMISSION,
        )
        val ordinaryPaused = pending.copy(
            serviceState = TimerServiceState.PAUSED,
            requestedSide = null,
        )

        val restoredPending = restore(pending)
        val restoredFailed = restore(failed)
        val restoredPaused = restore(ordinaryPaused)
        val restoredConfirmedRunning = restore(
            runningLeftCandidate().copy(serviceState = TimerServiceState.RUNNING),
        )

        assertEquals(TimerServiceState.RECOVERABLE, restoredPending.serviceState)
        assertEquals("L", restoredPending.requestedSide)
        assertEquals(TimerServiceState.FAILED, restoredFailed.serviceState)
        assertEquals(TimerServiceFailure.PERMISSION, restoredFailed.serviceFailure)
        assertEquals(TimerServiceState.PAUSED, restoredPaused.serviceState)
        assertEquals(TimerServiceState.RECOVERABLE, restoredConfirmedRunning.serviceState)
        assertFalse(restoredConfirmedRunning.leftRunning)
        assertEquals("L", restoredConfirmedRunning.requestedSide)
    }

    @Test
    fun recreationKeepsConfirmedRunningOnlyWhenTheSameServiceIsActuallyAlive() {
        val running = runningLeftCandidate().copy(serviceState = TimerServiceState.RUNNING)
        val raw = running.toJson(
            savedElapsed = 1_250L,
            savedWall = 1_700_000_000_250L,
            savedBootCount = 4L,
        )

        val live = TimerState.fromJson(
            raw = raw,
            nowElapsed = 1_500L,
            nowWall = 1_700_000_000_500L,
            nowBootCount = 4L,
            activeServiceSession = "timer-session",
        )
        val mismatched = TimerState.fromJson(
            raw = raw,
            nowElapsed = 1_500L,
            nowWall = 1_700_000_000_500L,
            nowBootCount = 4L,
            activeServiceSession = "some-other-session",
        )

        assertEquals(TimerServiceState.RUNNING, live.serviceState)
        assertTrue(live.leftRunning)
        assertEquals(1_000L, live.leftStartedElapsed)
        assertNull(live.requestedSide)
        assertEquals(TimerServiceState.RECOVERABLE, mismatched.serviceState)
        assertFalse(mismatched.leftRunning)
        assertEquals(250L, mismatched.leftAccumMs)
    }

    private suspend fun captureFailure(throwable: RuntimeException): TimerServiceFailure {
        val published = mutableListOf<TimerState>()
        startTimerWithConfirmation(
            candidate = runningLeftCandidate(),
            publish = { published += it },
            startService = { throw throwable },
        )
        return requireNotNull(published.last().serviceFailure)
    }

    private fun restore(state: TimerState): TimerState = TimerState.fromJson(
        raw = state.toJson(
            savedElapsed = 1_250L,
            savedWall = 1_700_000_000_250L,
            savedBootCount = 4L,
        ),
        nowElapsed = 1_500L,
        nowWall = 1_700_000_000_500L,
        nowBootCount = 4L,
    )

    private fun runningLeftCandidate() = TimerState(
        babyId = 42L,
        completionClientUuid = "timer-session",
        leftRunning = true,
        leftStartedElapsed = 1_000L,
        sessionStartedAt = 1_700_000_000_000L,
        lastSide = "L",
        order = "L",
    )
}

private class ForegroundServiceStartNotAllowedForTest : RuntimeException()
