package com.lezi.babylog.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class TimerHandoffSeedTest {
    @Test
    fun buildSeedCarriesBabyCarePlanNoteAmountAndPerPhotoOwnershipInOrder() {
        val result = buildTimerHandoffSeed(
            handoffId = "handoff-1",
            babyId = 7L,
            carePlanId = 42L,
            note = "含接顺利",
            amountMl = "120",
            orderedPhotoPaths = listOf("a.jpg", "b.jpg", "c.jpg"),
            borrowedPaths = listOf("a.jpg", "b.jpg"),
            ownedPaths = listOf("c.jpg"),
        )

        val seed = (result as TimerHandoffBuildResult.Ready).seed
        assertThat(seed.handoffId).isEqualTo("handoff-1")
        assertThat(seed.babyId).isEqualTo(7L)
        assertThat(seed.carePlanId).isEqualTo(42L)
        assertThat(seed.note).isEqualTo("含接顺利")
        assertThat(seed.amountMl).isEqualTo("120")
        assertThat(seed.orderedPaths).containsExactly("a.jpg", "b.jpg", "c.jpg").inOrder()
        assertThat(seed.photos.map { it.ownership }).containsExactly(
            TimerHandoffPhotoOwnership.Borrowed,
            TimerHandoffPhotoOwnership.Borrowed,
            TimerHandoffPhotoOwnership.ComposerOwned,
        ).inOrder()
    }

    @Test
    fun borrowedWinsOverOwnedWhenPathAppearsInBoth() {
        val result = buildTimerHandoffSeed(
            handoffId = "h",
            babyId = 1L,
            carePlanId = 2L,
            note = "",
            amountMl = "",
            orderedPhotoPaths = listOf("shared.jpg"),
            borrowedPaths = listOf("shared.jpg"),
            ownedPaths = listOf("shared.jpg"),
        )
        val seed = (result as TimerHandoffBuildResult.Ready).seed
        assertThat(seed.photos.single().ownership)
            .isEqualTo(TimerHandoffPhotoOwnership.Borrowed)
        assertThat(seed.composerOwnedPaths).isEmpty()
    }

    @Test
    fun photoOverflowWhenSeedPlusLivePlanExceedsMaxBeforeLeave() {
        val result = buildTimerHandoffSeed(
            handoffId = "h",
            babyId = 1L,
            carePlanId = 9L,
            note = "",
            amountMl = "",
            orderedPhotoPaths = listOf("s1.jpg", "s2.jpg"),
            borrowedPaths = listOf("s1.jpg"),
            ownedPaths = listOf("s2.jpg"),
            livePlanPhotoPaths = listOf("p1.jpg", "p2.jpg", "p3.jpg"),
        )
        val overflow = result as TimerHandoffBuildResult.PhotoOverflow
        assertThat(overflow.distinctCount).isEqualTo(5)
        assertThat(overflow.maxAllowed).isEqualTo(MAX_RECORD_PHOTOS)
    }

    @Test
    fun mergeSeedAndPlanPhotosPrefersSeedOrderThenPlanOnlyAndCapsAtThree() {
        assertThat(
            mergeTimerCompletionPhotos(
                seedPhotoPaths = listOf("s1.jpg", "s2.jpg", "plan-shared.jpg"),
                livePlanPhotoPaths = listOf("plan-shared.jpg", "p-new.jpg", "p-extra.jpg"),
            ),
        ).containsExactly("s1.jpg", "s2.jpg", "plan-shared.jpg").inOrder()

        assertThat(
            mergeTimerCompletionPhotos(
                seedPhotoPaths = listOf("owned.jpg"),
                livePlanPhotoPaths = listOf("p1.jpg", "p2.jpg"),
            ),
        ).containsExactly("owned.jpg", "p1.jpg", "p2.jpg").inOrder()
    }

    @Test
    fun untransferableFieldsRequireConfirmForDurationOrderAndTime() {
        assertThat(
            untransferableTimerFields(
                leftMinutes = "5",
                rightMinutes = "0",
                order = "LR",
                timestamp = 1_000L,
                baselineOrder = "LR",
                baselineTimestamp = 1_000L,
            ),
        ).containsExactly(UntransferableTimerField.ManualDuration)

        assertThat(
            untransferableTimerFields(
                leftMinutes = "0",
                rightMinutes = "0",
                order = "RL",
                timestamp = 2_000L,
                baselineOrder = "LR",
                baselineTimestamp = 1_000L,
            ),
        ).containsExactly(
            UntransferableTimerField.ManualOrder,
            UntransferableTimerField.ManualTime,
        )

        assertThat(
            untransferableTimerFields(
                leftMinutes = "0",
                rightMinutes = "0",
                order = "LR",
                timestamp = 1_000L,
                baselineOrder = "LR",
                baselineTimestamp = 1_000L,
            ),
        ).isEmpty()
    }

    @Test
    fun jsonRoundTripPreservesSeedAndCorruptJsonReturnsNull() {
        val seed = TimerHandoffSeed(
            handoffId = "handoff-json",
            babyId = 3L,
            carePlanId = 8L,
            note = "备注",
            amountMl = "90",
            photos = listOf(
                TimerHandoffPhoto("a.jpg", TimerHandoffPhotoOwnership.Borrowed),
                TimerHandoffPhoto("b.jpg", TimerHandoffPhotoOwnership.ComposerOwned),
            ),
        )
        val restored = TimerHandoffSeed.fromJson(seed.toJson())
        assertThat(restored).isEqualTo(seed)
        assertThat(TimerHandoffSeed.fromJson(null)).isNull()
        assertThat(TimerHandoffSeed.fromJson("{not-json")).isNull()
        assertThat(TimerHandoffSeed.fromJson("""{"handoffId":""}""")).isNull()
    }

    @Test
    fun acceptIsIdempotentByHandoffIdAndRejectsConflictingSession() {
        val seed = TimerHandoffSeed(handoffId = "h1", babyId = 1L, carePlanId = 2L)
        assertThat(
            decideTimerHandoffAccept(
                seed = seed,
                existingHandoffId = null,
                boundCarePlanId = null,
                boundBabyId = null,
                hasSessionData = false,
            ),
        ).isEqualTo(TimerHandoffAcceptResult.Accepted(1L, 2L))

        assertThat(
            decideTimerHandoffAccept(
                seed = seed,
                existingHandoffId = "h1",
                boundCarePlanId = 2L,
                boundBabyId = 1L,
                hasSessionData = true,
            ),
        ).isEqualTo(TimerHandoffAcceptResult.AlreadyAccepted)

        assertThat(
            decideTimerHandoffAccept(
                seed = seed,
                existingHandoffId = "other",
                boundCarePlanId = null,
                boundBabyId = null,
                hasSessionData = false,
            ),
        ).isEqualTo(TimerHandoffAcceptResult.RejectedConflict)

        assertThat(
            decideTimerHandoffAccept(
                seed = seed,
                existingHandoffId = null,
                boundCarePlanId = null,
                boundBabyId = null,
                hasSessionData = true,
            ),
        ).isEqualTo(TimerHandoffAcceptResult.RejectedConflict)
    }

    @Test
    fun discardReclaimPathsAreComposerOwnedOnly() {
        val seed = TimerHandoffSeed(
            handoffId = "h",
            babyId = 1L,
            photos = listOf(
                TimerHandoffPhoto("plan.jpg", TimerHandoffPhotoOwnership.Borrowed),
                TimerHandoffPhoto("owned.jpg", TimerHandoffPhotoOwnership.ComposerOwned),
            ),
        )
        assertThat(timerDiscardReclaimPaths(seed)).containsExactly("owned.jpg")
        assertThat(timerDiscardReclaimPaths(null)).isEmpty()
    }

    @Test
    fun seedRejectsBlankHandoffOrTooManyPhotos() {
        assertThrows(IllegalArgumentException::class.java) {
            TimerHandoffSeed(handoffId = " ", babyId = 1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TimerHandoffSeed(
                handoffId = "h",
                babyId = 1L,
                photos = listOf(
                    TimerHandoffPhoto("a.jpg", TimerHandoffPhotoOwnership.ComposerOwned),
                    TimerHandoffPhoto("b.jpg", TimerHandoffPhotoOwnership.ComposerOwned),
                    TimerHandoffPhoto("c.jpg", TimerHandoffPhotoOwnership.ComposerOwned),
                    TimerHandoffPhoto("d.jpg", TimerHandoffPhotoOwnership.ComposerOwned),
                ),
            )
        }
    }
}
