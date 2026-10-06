package com.lezi.babylog.feature.log.composer
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.CarePlan
import com.lezi.babylog.core.model.CarePlanStatus
import com.lezi.babylog.core.model.NursingPayload
import com.lezi.babylog.core.model.RecordTime
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.shouldOfferNextFeedPlanForFact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.timeline.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.layout.*
import com.lezi.babylog.feature.log.photo.*

class QuickRecordDraftSleepIntervalTest {
    @Test
    fun newSleepRequiresConfirmationButCreatesAnOpenInterval() {
        val draft = QuickRecordDraft.create(RecordType.SLEEP, tappedAt)

        assertEquals(SleepDraftAction.SleepDown, draft.sleepAction)
        assertEquals("确认睡下", draft.confirmLabel())
        assertNull(draft.validationError(nowMillis = tappedAt + 60_000L))
        assertNull(draft.toSaveCommand().endTimestamp)
    }

    @Test
    fun leftoverSleepDownEndIsIgnoredAsOpenSleepStart() {
        val draft = QuickRecordDraft.create(RecordType.SLEEP, 1_000L)
            .copy(endTimestamp = 2_000L)

        assertEquals(SleepDraftAction.SleepDown, draft.sleepAction)
        assertEquals("确认睡下", draft.confirmLabel())
        assertNull(draft.intervalDurationPreview(nowMillis = 3_000L))
        assertNull(draft.validationError(nowMillis = 3_000L))
        assertTrue(draft.canConfirm(nowMillis = 3_000L))
        assertNull(draft.toSaveCommand().endTimestamp)
    }

    @Test
    fun completedSleepPreviewsTheSameDurationCopyAsTheTimeline() {
        val draft = QuickRecordDraft.create(
            type = RecordType.SLEEP,
            timestamp = tappedAt,
            historical = true,
        ).copy(endTimestamp = tappedAt + 125 * 60_000L)

        assertEquals(
            IntervalDurationPreview.Duration("时长 2h5m"),
            draft.intervalDurationPreview(nowMillis = tappedAt + 180 * 60_000L),
        )
        assertTrue(draft.canConfirm(nowMillis = tappedAt + 180 * 60_000L))
        assertEquals(
            tappedAt + 125 * 60_000L,
            draft.toSaveCommand().endTimestamp,
        )
    }

    @Test
    fun sleepDownWithoutWakeHasNoDurationPreviewAndCanConfirm() {
        val draft = QuickRecordDraft.create(RecordType.SLEEP, tappedAt)

        assertNull(draft.intervalDurationPreview(nowMillis = tappedAt + 60_000L))
        assertTrue(draft.canConfirm(nowMillis = tappedAt + 60_000L))
    }

    @Test
    fun leftoverSleepDownIgnoresEndWhileManualAndWakeKeepTheirs() {
        val leftoverSleepDown = QuickRecordDraft.create(RecordType.SLEEP, tappedAt)
            .copy(endTimestamp = tappedAt + 30 * 60_000L)
        val manual = QuickRecordDraft.create(
            type = RecordType.SLEEP,
            timestamp = tappedAt,
            historical = true,
        ).copy(endTimestamp = tappedAt + 30 * 60_000L)
        val openSleep = Record(
            id = 42L,
            clientUuid = "sleep-42",
            babyId = 7L,
            type = RecordType.SLEEP,
            timestamp = tappedAt,
            endTimestamp = null,
            note = null,
            payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
            updatedAt = tappedAt,
        )
        val wake = QuickRecordDraft.wakeSleep(
            openSleep = openSleep,
            clickedAt = tappedAt + 45 * 60_000L,
        )

        assertNull(leftoverSleepDown.intervalDurationPreview(nowMillis = tappedAt + 60 * 60_000L))
        assertNull(leftoverSleepDown.toSaveCommand().endTimestamp)
        assertEquals(
            IntervalDurationPreview.Duration("时长 30m"),
            manual.intervalDurationPreview(nowMillis = tappedAt + 60 * 60_000L),
        )
        assertEquals(tappedAt + 30 * 60_000L, manual.toSaveCommand().endTimestamp)
        assertEquals(
            IntervalDurationPreview.Duration("时长 45m"),
            wake.intervalDurationPreview(nowMillis = tappedAt + 60 * 60_000L),
        )
        assertEquals(tappedAt + 45 * 60_000L, wake.toSaveCommand().endTimestamp)
    }


    @Test
    fun incompleteAndInvalidSleepUseShortWarningsAndCannotConfirm() {
        val manual = QuickRecordDraft.create(
            type = RecordType.SLEEP,
            timestamp = tappedAt,
            historical = true,
        )
        val now = tappedAt + 60_000L

        assertEquals(
            IntervalDurationPreview.Warning("请选择醒来时刻"),
            manual.intervalDurationPreview(nowMillis = now),
        )
        assertFalse(manual.canConfirm(nowMillis = now))

        val nonPositive = manual.copy(endTimestamp = tappedAt)
        assertEquals(
            IntervalDurationPreview.Warning("醒来须晚于睡下"),
            nonPositive.intervalDurationPreview(nowMillis = now),
        )
        assertFalse(nonPositive.canConfirm(nowMillis = now))

        val future = manual.copy(endTimestamp = now + 1L)
        assertEquals(
            IntervalDurationPreview.Warning("不能选未来时刻"),
            future.intervalDurationPreview(nowMillis = now),
        )
        assertFalse(future.canConfirm(nowMillis = now))

        val futureSleepDown = QuickRecordDraft.create(
            type = RecordType.SLEEP,
            timestamp = now + 1L,
        )
        // Future sleep is schedule-care intent (ticket 17), not a fact open interval.
        assertEquals(ComposerWorkMode.ScheduleCare, futureSleepDown.workMode(nowMillis = now))
        assertNull(futureSleepDown.intervalDurationPreview(nowMillis = now))
        assertTrue(futureSleepDown.canConfirm(nowMillis = now))
    }

    @Test
    fun clockEndRejectionAddsCrossDayGuidanceOnlyForSleepOrdering() {
        val now = tappedAt + 90 * 60_000L
        val sleep = QuickRecordDraft.create(
            type = RecordType.SLEEP,
            timestamp = tappedAt,
            historical = true,
        )

        assertEquals(
            "醒来须晚于睡下。跨天请先把日期改为次日",
            sleep.endTimeRejectionMessage(tappedAt, nowMillis = now),
        )
        assertEquals(
            "不能选未来时刻",
            sleep.endTimeRejectionMessage(now + 1L, nowMillis = now),
        )
        assertNull(
            sleep.endTimeRejectionMessage(
                candidateEndTimestamp = tappedAt + 30 * 60_000L,
                nowMillis = now,
            ),
        )

        // Future start is schedule-care intent — interval end rejection does not apply.
        val futureStart = sleep.copy(timestamp = now + 2L)
        assertEquals(ComposerWorkMode.ScheduleCare, futureStart.workMode(nowMillis = now))
        assertNull(
            futureStart.endTimeRejectionMessage(
                candidateEndTimestamp = now + 1L,
                nowMillis = now,
            ),
        )
    }

    @Test
    fun intervalPreviewExcludesHandEnteredDurationFields() {
        assertNull(
            QuickRecordDraft.create(RecordType.NURSING, tappedAt)
                .copy(leftMin = "10")
                .intervalDurationPreview(nowMillis = tappedAt + 60_000L),
        )
        assertNull(
            QuickRecordDraft.create(RecordType.FORMULA, tappedAt)
                .copy(durationMin = "10")
                .intervalDurationPreview(nowMillis = tappedAt + 60_000L),
        )
    }

    @Test
    fun wakeConfirmationUpdatesTheExistingOpenSleepAndNormalizesVersionTwoPayload() {
        val open = Record(
            id = 42L,
            clientUuid = "sleep-42",
            babyId = 7L,
            type = RecordType.SLEEP,
            timestamp = tappedAt - 3_600_000L,
            endTimestamp = null,
            note = "午睡",
            payloadJson = """{"is_nap":true,"anomaly_flag":false}""",
            updatedAt = tappedAt,
        )

        val draft = QuickRecordDraft.wakeSleep(open, tappedAt)
        val command = draft.toSaveCommand()

        assertEquals(SleepDraftAction.WakeUp, draft.sleepAction)
        assertEquals("确认醒来", draft.confirmLabel())
        assertEquals(42L, command.existingRecordId)
        assertEquals(open.timestamp, command.timestamp)
        assertEquals(tappedAt, command.endTimestamp)
        assertEquals("""{"is_nap":true,"anomaly_flag":false}""", command.payloadJson)
        assertEquals(2, command.schemaVersion)
        assertEquals("午睡", command.note)
    }

    @Test
    fun wakeConfirmationPersistsAnEditedNapFlagWithoutDroppingExistingPayload() {
        val open = Record(
            id = 42L,
            clientUuid = "sleep-42",
            babyId = 7L,
            type = RecordType.SLEEP,
            timestamp = tappedAt - 3_600_000L,
            endTimestamp = null,
            note = null,
            payloadJson = """{"is_nap":true,"anomaly_flag":true}""",
            updatedAt = tappedAt,
        )

        val command = QuickRecordDraft.wakeSleep(open, tappedAt)
            .copy(isNap = false)
            .toSaveCommand()

        assertEquals(
            """{"is_nap":false,"anomaly_flag":true}""",
            command.payloadJson,
        )
    }

    @Test
    fun historicalSleepUsesCompletedIntervalValidation() {
        val draft = QuickRecordDraft.create(
            type = RecordType.SLEEP,
            timestamp = tappedAt,
            historical = true,
        )

        assertEquals(SleepDraftAction.Manual, draft.sleepAction)
        assertEquals("请选择醒来时刻", draft.validationError(nowMillis = tappedAt + 60_000L))
        assertNull(
            draft.copy(endTimestamp = tappedAt + 30 * 60_000L)
                .validationError(nowMillis = tappedAt + 60 * 60_000L),
        )
    }

    @Test
    fun editingUnknownCurrentPayloadIsFailClosed() {
        val source = Record(
            id = 88L,
            clientUuid = "formula-88",
            babyId = 7L,
            type = RecordType.FORMULA,
            timestamp = tappedAt,
            endTimestamp = null,
            note = "原备注",
            payloadJson =
                """{"amount_ml":120,"photos":["a.jpg"],"anomaly_flag":true,"future":{"v":2}}""",
            updatedAt = tappedAt,
        )

        val draft = QuickRecordDraft.fromRecord(source).copy(amountMl = 135)

        assertTrue(draft.isEditing)
        assertEquals("保存修改", draft.confirmLabel())
        assertEquals(
            "此记录格式暂不支持安全编辑，原始数据已保留",
            draft.validationError(nowMillis = tappedAt + 1L),
        )
        assertFalse(draft.canConfirm(nowMillis = tappedAt + 1L))
    }

    @Test
    fun editingCompletedAndOpenSleepKeepsTheirState() {
        fun sleep(end: Long?) = Record(
            id = if (end == null) 91L else 92L,
            clientUuid = "sleep-${end ?: "open"}",
            babyId = 7L,
            type = RecordType.SLEEP,
            timestamp = tappedAt,
            endTimestamp = end,
            note = null,
            payloadJson = """{"anomaly_flag":false,"is_nap":false}""",
            updatedAt = tappedAt,
        )

        val openDraft = QuickRecordDraft.fromRecord(sleep(end = null))
        val completeDraft = QuickRecordDraft.fromRecord(sleep(end = tappedAt + 20 * 60_000L))

        assertEquals(SleepDraftAction.SleepDown, openDraft.sleepAction)
        assertNull(openDraft.endTimestamp)
        assertTrue(openDraft.isEditing)
        assertEquals(SleepDraftAction.Manual, completeDraft.sleepAction)
        assertEquals(tappedAt + 20 * 60_000L, completeDraft.endTimestamp)
    }

    @Test
    fun wakeCardSleepStartIsEditableOnlyWhenActorCanManage() {
        assertTrue(wakeCardSleepStartEditable(canManageSleepStart = true))
        assertFalse(wakeCardSleepStartEditable(canManageSleepStart = false))
        assertFalse(
            wakeCardSleepStartEditable(canManageSleepStart = true, sheetReadOnly = true),
        )
        val open = Record(
            id = 42L,
            clientUuid = "sleep-42",
            babyId = 7L,
            type = RecordType.SLEEP,
            timestamp = tappedAt - 3_600_000L,
            endTimestamp = null,
            note = null,
            payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
            updatedAt = tappedAt,
        )
        val draft = QuickRecordDraft.wakeSleep(open, tappedAt)
        assertEquals("选择睡下时刻", clockDialogTitle(draft, selectingEnd = false))
        assertEquals("选择醒来时刻", clockDialogTitle(draft, selectingEnd = true))
        val movedPastWake = draft.copy(timestamp = tappedAt + 1L)
        assertEquals(
            "醒来须晚于睡下",
            movedPastWake.validationError(nowMillis = tappedAt + 60_000L),
        )
        assertFalse(movedPastWake.canConfirm(nowMillis = tappedAt + 60_000L))
        val movedEarlier = draft.copy(timestamp = tappedAt - 4_200_000L)
        assertNull(movedEarlier.validationError(nowMillis = tappedAt + 60_000L))
        assertEquals(tappedAt - 4_200_000L, movedEarlier.toSaveCommand().timestamp)
        assertEquals(tappedAt, movedEarlier.toSaveCommand().endTimestamp)
    }

    @Test
    fun wakeRejectsZeroLengthInterval() {
        val open = Record(
            id = 42L,
            clientUuid = "sleep-42",
            babyId = 7L,
            type = RecordType.SLEEP,
            timestamp = tappedAt,
            endTimestamp = null,
            note = null,
            payloadJson = """{"anomaly_flag":false}""",
            updatedAt = tappedAt,
        )

        assertEquals(
            "醒来须晚于睡下",
            QuickRecordDraft.wakeSleep(open, clickedAt = tappedAt)
                .validationError(nowMillis = tappedAt + 60_000L),
        )
    }
}
