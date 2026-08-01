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
