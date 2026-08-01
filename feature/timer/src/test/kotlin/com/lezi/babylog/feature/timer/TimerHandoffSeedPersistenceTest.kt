package com.lezi.babylog.feature.timer

import com.lezi.babylog.core.model.TimerHandoffAcceptResult
import com.lezi.babylog.core.model.TimerHandoffPhoto
import com.lezi.babylog.core.model.TimerHandoffPhotoOwnership
import com.lezi.babylog.core.model.TimerHandoffSeed
import com.lezi.babylog.core.model.decideTimerHandoffAccept
import com.lezi.babylog.core.model.mergeTimerCompletionPhotos
import com.lezi.babylog.core.model.timerDiscardReclaimPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerHandoffSeedPersistenceTest {
    @Test
    fun timerStateRoundTripRestoresSameHandoffSeed() {
        val seed = TimerHandoffSeed(
            handoffId = "handoff-persist",
            babyId = 7L,
            carePlanId = 42L,
            note = "备注",
            amountMl = "60",
            photos = listOf(
                TimerHandoffPhoto("plan.jpg", TimerHandoffPhotoOwnership.Borrowed),
                TimerHandoffPhoto("owned.jpg", TimerHandoffPhotoOwnership.ComposerOwned),
            ),
        )
        val original = TimerState(
            babyId = 7L,
            completionClientUuid = "session-1",
            carePlanId = 42L,
            handoffSeed = seed,
            leftAccumMs = 1_000L,
        )

        val restored = TimerState.fromJson(
            raw = original.toJson(savedElapsed = 10_000L, savedWall = 1_700_000_000_000L),
            nowElapsed = 10_000L,
            nowWall = 1_700_000_000_000L,
            nowBootCount = 1L,
        )

        assertEquals(seed, restored.handoffSeed)
        assertEquals(7L, restored.babyId)
        assertEquals(42L, restored.carePlanId)
    }

    @Test
    fun seedOnlyAcceptSessionPersistsWithoutLeftAccumMs() {
        // Mirrors acceptHandoffSeed → persistLocal: handoff/plan bind without L/R start.
        val seed = TimerHandoffSeed(
            handoffId = "handoff-seed-only",
            babyId = 7L,
            carePlanId = 42L,
            note = "仅交接",
            amountMl = "45",
            photos = listOf(
                TimerHandoffPhoto("owned.jpg", TimerHandoffPhotoOwnership.ComposerOwned),
            ),
        )
        val accepted = TimerState(
            babyId = 7L,
            completionClientUuid = "session-seed-only",
            carePlanId = 42L,
            handoffSeed = seed,
        )
        assertTrue(accepted.shouldPersistTimerSession())
        assertTrue(!accepted.hasTimerData())
        assertTrue(accepted.leftAccumMs == 0L)

        val nursingTimerJson = accepted.toJson(
            savedElapsed = 5_000L,
            savedWall = 1_700_000_000_000L,
            savedBootCount = 2L,
        )
        val restored = TimerState.fromJson(
            raw = nursingTimerJson,
            nowElapsed = 5_000L,
            nowWall = 1_700_000_000_000L,
            nowBootCount = 2L,
        )
        assertEquals(seed, restored.handoffSeed)
        assertEquals("仅交接", restored.handoffSeed?.note)
        assertEquals("45", restored.handoffSeed?.amountMl)
        assertEquals(listOf("owned.jpg"), restored.handoffSeed?.composerOwnedPaths)
        assertEquals(0L, restored.leftAccumMs)
        assertNull(restored.sessionStartedAt)
    }

    @Test
    fun corruptNestedHandoffSeedDoesNotWipeValidTimerSession() {
        val durable = TimerState(
            babyId = 3L,
            completionClientUuid = "keep-me",
            leftAccumMs = 2_500L,
            sessionStartedAt = 1_700_000_000_000L,
            order = "L",
        )
        val raw = durable.toJson(
            savedElapsed = 9_000L,
            savedWall = 1_700_000_000_100L,
            savedBootCount = 1L,
        ).replace(
            "\"handoffSeed\":null",
            "\"handoffSeed\":{\"handoffId\":\"\",\"babyId\":0}",
        )
        val restored = TimerState.fromJson(
            raw = raw,
            nowElapsed = 9_000L,
            nowWall = 1_700_000_000_100L,
            nowBootCount = 1L,
        )
        assertNull(restored.handoffSeed)
        assertEquals(3L, restored.babyId)
        assertEquals("keep-me", restored.completionClientUuid)
        assertEquals(2_500L, restored.leftAccumMs)
        assertEquals(1_700_000_000_000L, restored.sessionStartedAt)
    }

    @Test
    fun emptyTimerStateDoesNotPersist() {
        assertTrue(!TimerState().shouldPersistTimerSession())
        assertTrue(TimerState(carePlanId = 9L).shouldPersistTimerSession())
    }

    @Test
    fun olderSnapshotsWithoutHandoffKeyStillRestore() {
        val legacy = TimerState(
            babyId = 1L,
            completionClientUuid = "legacy",
            leftAccumMs = 500L,
        )
        // Strip handoffSeed field to emulate pre-Ticket-09 durable JSON.
        val raw = legacy.toJson(savedElapsed = 1_000L, savedWall = 2_000L)
            .replace("\"handoffSeed\":null,", "")
            .replace(",\"handoffSeed\":null", "")

        val restored = TimerState.fromJson(
            raw = raw,
            nowElapsed = 1_000L,
            nowWall = 2_000L,
            nowBootCount = 1L,
        )
        assertNull(restored.handoffSeed)
        assertEquals(1L, restored.babyId)
    }

    @Test
    fun acceptIsIdempotentAndConflictRejectsWithoutSecondTakeover() {
        val seed = TimerHandoffSeed(handoffId = "h1", babyId = 1L, carePlanId = 2L)
        assertEquals(
            TimerHandoffAcceptResult.Accepted(1L, 2L),
            decideTimerHandoffAccept(
                seed = seed,
                existingHandoffId = null,
                boundCarePlanId = null,
                boundBabyId = null,
                hasSessionData = false,
            ),
        )
        assertEquals(
            TimerHandoffAcceptResult.AlreadyAccepted,
            decideTimerHandoffAccept(
                seed = seed,
                existingHandoffId = "h1",
                boundCarePlanId = 2L,
                boundBabyId = 1L,
                hasSessionData = true,
            ),
        )
        assertEquals(
            TimerHandoffAcceptResult.RejectedConflict,
            decideTimerHandoffAccept(
                seed = seed,
                existingHandoffId = null,
                boundCarePlanId = null,
                boundBabyId = null,
                hasSessionData = true,
            ),
        )
    }

    @Test
    fun completeMergeAndDiscardReclaimCoverSeedAndPlanPhotos() {
        val seed = TimerHandoffSeed(
            handoffId = "h",
            babyId = 1L,
            carePlanId = 9L,
            photos = listOf(
                TimerHandoffPhoto("owned.jpg", TimerHandoffPhotoOwnership.ComposerOwned),
                TimerHandoffPhoto("plan-a.jpg", TimerHandoffPhotoOwnership.Borrowed),
            ),
        )
        assertEquals(
            listOf("owned.jpg", "plan-a.jpg", "plan-b.jpg"),
            mergeTimerCompletionPhotos(
                seedPhotoPaths = seed.orderedPaths,
                livePlanPhotoPaths = listOf("plan-a.jpg", "plan-b.jpg"),
            ),
        )
        assertEquals(listOf("owned.jpg"), timerDiscardReclaimPaths(seed))
        assertTrue(timerDiscardReclaimPaths(null).isEmpty())
    }
}
