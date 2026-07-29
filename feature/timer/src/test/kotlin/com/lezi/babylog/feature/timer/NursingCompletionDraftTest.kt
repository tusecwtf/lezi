package com.lezi.babylog.feature.timer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NursingCompletionDraftTest {
    @Test
    fun freeze_capturesRunningDurationsAndTapTime() {
        val state = TimerState(
            leftRunning = true,
            leftAccumMs = 30_000L,
            leftStartedElapsed = 1_000L,
            rightAccumMs = 89_000L,
            sessionStartedAt = 1_700_000_000_000L,
            lastSide = "R",
            order = "LR",
        )

        val draft = freezeNursingCompletion(
            state = state,
            nowElapsed = 91_000L,
            clickedAt = 1_700_001_200_000L,
        )

        assertEquals("2", draft.leftMinutes)
        assertEquals("1", draft.rightMinutes)
        assertEquals("LR", draft.order)
        assertEquals(1_700_000_000_000L, draft.startedAt)
        assertEquals(1_700_001_200_000L, draft.endedAt)
        assertEquals(1_700_001_200_000L, draft.capturedAt)
    }

    @Test
    fun frozenDraft_doesNotRecalculateWhileTimerKeepsRunning() {
        val state = TimerState(
            leftRunning = true,
            leftStartedElapsed = 0L,
            sessionStartedAt = 1_700_000_000_000L,
            order = "L",
        )
        val draft = freezeNursingCompletion(
            state = state,
            nowElapsed = 120_000L,
            clickedAt = 1_700_000_120_000L,
        )

        // The live timer has reached five minutes, but confirmation still uses
        // the values reviewed at the two-minute tap.
        assertEquals(300_000L, state.leftMs(300_000L))
        assertEquals(2, draft.toCommand().leftMin)
        assertEquals(1_700_000_120_000L, draft.toCommand().endedAt)
    }

    @Test
    fun freeze_carriesTheQuickSheetNoteAndAmountIntoFinalConfirmation() {
        val draft = freezeNursingCompletion(
            state = TimerState(
                leftAccumMs = 60_000L,
                sessionStartedAt = 1_700_000_000_000L,
                order = "L",
            ),
            nowElapsed = 120_000L,
            clickedAt = 1_700_000_120_000L,
            initialNote = "含接顺利",
            initialAmountMl = "45",
        )

        assertEquals("含接顺利", draft.note)
        assertEquals("45", draft.amountMl)
    }

    @Test
    fun command_keepsReviewedFieldsAndNormalizesOptionalNote() {
        val draft = validDraft().copy(
            leftMinutes = "12",
            rightMinutes = "8",
            order = "RL",
            amountMl = "45",
            note = "  含接顺利  ",
        )

        val command = draft.toCommand()

        assertEquals(12, command.leftMin)
        assertEquals(8, command.rightMin)
        assertEquals("RL", command.order)
        assertEquals(45, command.amountMl)
        assertEquals("含接顺利", command.note)
    }

    @Test
    fun commandPreservesAllFourOrdersIncludingSingleSideDurations() {
        val examples = listOf(
            Triple("L", "7", "0"),
            Triple("R", "0", "9"),
            Triple("LR", "7", "9"),
            Triple("RL", "7", "9"),
        )

        examples.forEach { (order, left, right) ->
            val command = validDraft().copy(
                leftMinutes = left,
                rightMinutes = right,
                order = order,
            ).toCommand()

            assertEquals(order, command.order)
            assertEquals(left.toInt(), command.leftMin)
            assertEquals(right.toInt(), command.rightMin)
        }
    }

    @Test
    fun validation_rejectsEndBeforeStartAndAfterCurrentTime() {
        val start = 1_700_000_000_000L
        val now = start + 60_000L

        assertEquals(
            "结束时刻不能早于开始时刻",
            validDraft().copy(startedAt = start, endedAt = start - 1L)
                .validationError(now),
        )
        assertEquals(
            "结束时刻不能晚于现在",
            validDraft().copy(startedAt = start, endedAt = now + 1L)
                .validationError(now),
        )
        assertNull(
            validDraft().copy(startedAt = start, endedAt = start)
                .validationError(now),
        )
    }

    @Test
    fun validation_requiresDurationAndBoundsOptionalFields() {
        assertEquals(
            "请填写左侧或右侧喂养时长",
            validDraft().copy(leftMinutes = "0", rightMinutes = "0")
                .validationError(1_700_000_060_000L),
        )
        assertEquals(
            "奶量需在 1–999 ml 之间",
            validDraft().copy(amountMl = "1000")
                .validationError(1_700_000_060_000L),
        )
        assertEquals(
            "备注最多 200 字",
            validDraft().copy(note = "记".repeat(201))
                .validationError(1_700_000_060_000L),
        )
    }

    @Test
    fun minuteRounding_matchesCompletionSnapshotRule() {
        assertEquals(0, roundedNursingMinutes(29_999L))
        assertEquals(1, roundedNursingMinutes(30_000L))
        assertEquals(2, roundedNursingMinutes(90_000L))
        assertEquals(1_440, roundedNursingMinutes(Long.MAX_VALUE))
    }

    private fun validDraft() = NursingCompletionDraft(
        leftMinutes = "1",
        rightMinutes = "0",
        order = "L",
        startedAt = 1_700_000_000_000L,
        endedAt = 1_700_000_060_000L,
        capturedAt = 1_700_000_060_000L,
    )
}
