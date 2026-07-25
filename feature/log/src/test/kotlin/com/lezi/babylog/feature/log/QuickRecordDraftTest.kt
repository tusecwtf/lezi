package com.lezi.babylog.feature.log

import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickRecordDraftTest {
    private val tappedAt = 1_721_722_800_000L

    @Test
    fun everyRecordTypeHasAnExplicitSecondaryFormMode() {
        val routed = RecordType.entries.associateWith { it.quickRecordMode }

        assertEquals(RecordType.entries.size, routed.size)
        assertFalse(routed.values.any { it.name.isBlank() })
    }

    @Test
    fun confirmationKeepsTheOriginalTapTimeAndIncludesNote() {
        val draft = QuickRecordDraft.create(
            type = RecordType.FORMULA,
            timestamp = tappedAt,
            lastAmountMl = 135,
        ).copy(note = "拍嗝顺利")

        val command = draft.toSaveCommand()

        assertEquals(tappedAt, command.timestamp)
        assertEquals("拍嗝顺利", command.note)
        assertTrue(command.payloadJson.contains("\"amount_ml\":135"))
    }

    @Test
    fun formulaSerializesPurposeSpecificOptionalFields() {
        val command = QuickRecordDraft.create(RecordType.FORMULA, tappedAt)
            .copy(
                amountMl = 120,
                preparedMl = "135",
                durationMin = "12",
            )
            .toSaveCommand()

        assertEquals(
            """{"amount_ml":120,"prepared_ml":135,"duration_min":12}""",
            command.payloadJson,
        )
    }

    @Test
    fun composerEditPreservesUnknownPayloadFields() {
        val source = record(
            type = RecordType.FORMULA,
            payload = """{"amount_ml":120,"scalar":"keep","object":{"v":2},"array":[1,2]}""",
        )

        val command = QuickRecordDraft.fromRecord(source)
            .copy(amountMl = 135)
            .toSaveCommand()

        assertTrue(command.payloadJson.contains("\"amount_ml\":135"))
        assertTrue(command.payloadJson.contains("\"scalar\":\"keep\""))
        assertTrue(command.payloadJson.contains("\"object\":{\"v\":2}"))
        assertTrue(command.payloadJson.contains("\"array\":[1,2]"))
    }

    @Test
    fun allPurposeFamiliesSerializeTheirBasicInformation() {
        val cases = listOf(
            QuickRecordDraft.create(RecordType.NURSING, tappedAt)
                .copy(leftMin = "8", rightMin = "6", order = "RL", nursingAmountMl = "70") to
                listOf("\"left_min\":8", "\"right_min\":6", "\"order\":\"RL\"", "\"amount_ml\":70"),
            QuickRecordDraft.create(RecordType.BOTH_DIAPER, tappedAt)
                .copy(peeAmount = 3, stoolAmount = 4, stoolConsistency = 2, stoolColor = 5) to
                listOf("\"pee_amount\":3", "\"stool_amount\":4", "\"stool_consistency\":2", "\"stool_color\":5"),
            QuickRecordDraft.create(RecordType.TEMPERATURE, tappedAt)
                .copy(temperature = "100.4", temperatureUnit = TemperatureUnit.Fahrenheit) to
                listOf("\"celsius\":38"),
            QuickRecordDraft.create(RecordType.COUGH, tappedAt)
                .copy(severity = 3, description = "夜间连续") to
                listOf("\"severity\":3", "\"description\":\"夜间连续\""),
            QuickRecordDraft.create(RecordType.HOSPITAL, tappedAt)
                .copy(hospitalReason = "复诊", hospitalAdvice = "一周后复查") to
                listOf("\"reason\":\"复诊\"", "\"advice\":\"一周后复查\""),
            QuickRecordDraft.create(RecordType.BABY_FOOD, tappedAt)
                .copy(foodContent = "南瓜米糊", foodAmount = "半碗") to
                listOf("\"content\":\"南瓜米糊\"", "\"amount\":\"半碗\""),
            QuickRecordDraft.create(RecordType.VACCINE, tappedAt)
                .copy(vaccineName = "乙肝", vaccineBatch = "第2针") to
                listOf("\"name\":\"乙肝\"", "\"batch\":\"第2针\""),
        )

        cases.forEach { (draft, expectedParts) ->
            val payload = draft.toSaveCommand().payloadJson
            expectedParts.forEach { expected ->
                assertTrue("$payload should contain $expected", payload.contains(expected))
            }
        }
    }

    @Test
    fun weightInputUsesTheExistingAndroidGramPayloadContract() {
        val command = QuickRecordDraft.create(RecordType.WEIGHT, tappedAt)
            .copy(measurementValue = "6.35")
            .toSaveCommand()

        assertEquals("""{"value":6350,"unit":"g"}""", command.payloadJson)
    }

    @Test
    fun newSleepRequiresConfirmationButCreatesAnOpenInterval() {
        val draft = QuickRecordDraft.create(RecordType.SLEEP, tappedAt)

        assertEquals(SleepDraftAction.SleepDown, draft.sleepAction)
        assertEquals("确认睡下", draft.confirmLabel())
        assertNull(draft.validationError(nowMillis = tappedAt + 60_000L))
        assertNull(draft.toSaveCommand().endTimestamp)
    }

    @Test
    fun sleepDownWithEndRecordsCompletedIntervalInOneStep() {
        // Mirrors switch「同时记醒来」ON (end defaults to a valid moment).
        val end = tappedAt + 30 * 60_000L
        val draft = QuickRecordDraft.create(RecordType.SLEEP, tappedAt)
            .copy(endTimestamp = end)

        assertEquals(SleepDraftAction.SleepDown, draft.sleepAction)
        assertEquals("确认记录", draft.confirmLabel())
        assertNull(draft.validationError(nowMillis = tappedAt + 60 * 60_000L))
        assertEquals(end, draft.toSaveCommand().endTimestamp)
    }

    @Test
    fun completedSleepPreviewsTheSameDurationCopyAsTheTimeline() {
        val draft = QuickRecordDraft.create(
            type = RecordType.SLEEP,
            timestamp = tappedAt,
            historical = true,
        ).copy(endTimestamp = tappedAt + 125 * 60_000L)

        assertEquals(
            IntervalDurationPreview.Duration("时长 2小时5分"),
            draft.intervalDurationPreview(nowMillis = tappedAt + 180 * 60_000L),
        )
        assertTrue(draft.canConfirm(nowMillis = tappedAt + 180 * 60_000L))
    }

    @Test
    fun sleepDownWithoutWakeHasNoDurationPreviewAndCanConfirm() {
        val draft = QuickRecordDraft.create(RecordType.SLEEP, tappedAt)

        assertNull(draft.intervalDurationPreview(nowMillis = tappedAt + 60_000L))
        assertTrue(draft.canConfirm(nowMillis = tappedAt + 60_000L))
    }

    @Test
    fun sleepDownWithWakeAndWakeConfirmationBothPreviewDuration() {
        val sleepDown = QuickRecordDraft.create(RecordType.SLEEP, tappedAt)
            .copy(endTimestamp = tappedAt + 30 * 60_000L)
        val openSleep = Record(
            id = 42L,
            clientUuid = "sleep-42",
            babyId = 7L,
            type = RecordType.SLEEP,
            timestamp = tappedAt,
            endTimestamp = null,
            note = null,
            createdByUserId = 1L,
            payloadJson = "{}",
            updatedAt = tappedAt,
        )
        val wake = QuickRecordDraft.wakeSleep(
            openSleep = openSleep,
            clickedAt = tappedAt + 45 * 60_000L,
        )

        assertEquals(
            IntervalDurationPreview.Duration("时长 30分"),
            sleepDown.intervalDurationPreview(nowMillis = tappedAt + 60 * 60_000L),
        )
        assertEquals(
            IntervalDurationPreview.Duration("时长 45分"),
            wake.intervalDurationPreview(nowMillis = tappedAt + 60 * 60_000L),
        )
    }

    @Test
    fun sleepDownTurningOffRecordWakeClearsEnd() {
        val withEnd = QuickRecordDraft.create(RecordType.SLEEP, tappedAt)
            .copy(endTimestamp = tappedAt + 10_000L)
        val cleared = withEnd.copy(endTimestamp = null)

        assertEquals("确认睡下", cleared.confirmLabel())
        assertNull(cleared.toSaveCommand().endTimestamp)
        assertNull(cleared.validationError(nowMillis = tappedAt + 60_000L))
    }

    @Test
    fun sleepDownWithInvalidEndIsRejected() {
        val draft = QuickRecordDraft.create(RecordType.SLEEP, tappedAt)

        assertEquals(
            "醒来须晚于睡下",
            draft.copy(endTimestamp = tappedAt)
                .validationError(nowMillis = tappedAt + 60_000L),
        )
        assertEquals(
            "不能选未来时刻",
            draft.copy(endTimestamp = tappedAt + 2 * 60_000L)
                .validationError(nowMillis = tappedAt + 60_000L),
        )
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
        assertEquals(
            IntervalDurationPreview.Warning("不能选未来时刻"),
            futureSleepDown.intervalDurationPreview(nowMillis = now),
        )
        assertFalse(futureSleepDown.canConfirm(nowMillis = now))
    }

    @Test
    fun walkIsAPointRecordWithOptionalNote() {
        val walk = QuickRecordDraft.create(RecordType.WALK, tappedAt)
        val now = tappedAt + 90 * 60_000L

        assertNull(walk.intervalDurationPreview(nowMillis = now))
        assertNull(walk.validationError(nowMillis = now))
        assertTrue(walk.canConfirm(nowMillis = now))
        assertNull(walk.toSaveCommand().endTimestamp)
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

        val futureStart = sleep.copy(timestamp = now + 2L)
        assertEquals(
            "不能选未来时刻",
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
            createdByUserId = 1L,
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
        assertEquals("""{"is_nap":true}""", command.payloadJson)
        assertEquals(2, command.schemaVersion)
        assertEquals("午睡", command.note)
    }

    @Test
    fun wakeConfirmationPersistsAnEditedNapFlagWithoutDroppingOtherPayload() {
        val open = Record(
            id = 42L,
            clientUuid = "sleep-42",
            babyId = 7L,
            type = RecordType.SLEEP,
            timestamp = tappedAt - 3_600_000L,
            endTimestamp = null,
            note = null,
            createdByUserId = 1L,
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
    fun editingRecordRoundTripsUnknownPayloadAndOriginalFields() {
        val source = Record(
            id = 88L,
            clientUuid = "formula-88",
            babyId = 7L,
            type = RecordType.FORMULA,
            timestamp = tappedAt,
            endTimestamp = null,
            note = "原备注",
            createdByUserId = 1L,
            payloadJson =
                """{"amount_ml":120,"photos":["a.jpg"],"anomaly_flag":true,"future":{"v":2}}""",
            updatedAt = tappedAt,
        )

        val draft = QuickRecordDraft.fromRecord(source).copy(amountMl = 135)
        val command = draft.toSaveCommand()

        assertTrue(draft.isEditing)
        assertEquals("保存修改", draft.confirmLabel())
        assertEquals(88L, command.existingRecordId)
        assertTrue(command.payloadJson.contains("\"amount_ml\":135"))
        assertTrue(command.payloadJson.contains("\"photos\":[\"a.jpg\"]"))
        assertTrue(command.payloadJson.contains("\"anomaly_flag\":true"))
        assertTrue(command.payloadJson.contains("\"future\":{\"v\":2}"))
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
            createdByUserId = 1L,
            payloadJson = """{"is_nap":false,"photos":["sleep.jpg"]}""",
            updatedAt = tappedAt,
        )

        val openDraft = QuickRecordDraft.fromRecord(sleep(end = null))
        val completeDraft = QuickRecordDraft.fromRecord(sleep(end = tappedAt + 20 * 60_000L))

        assertEquals(SleepDraftAction.SleepDown, openDraft.sleepAction)
        assertNull(openDraft.endTimestamp)
        assertTrue(openDraft.isEditing)
        assertEquals(SleepDraftAction.Manual, completeDraft.sleepAction)
        assertEquals(tappedAt + 20 * 60_000L, completeDraft.endTimestamp)
        assertTrue(completeDraft.toSaveCommand().payloadJson.contains("\"photos\":[\"sleep.jpg\"]"))
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
            createdByUserId = 1L,
            payloadJson = "{}",
            updatedAt = tappedAt,
        )

        assertEquals(
            "醒来须晚于睡下",
            QuickRecordDraft.wakeSleep(open, clickedAt = tappedAt)
                .validationError(nowMillis = tappedAt + 60_000L),
        )
    }

    @Test
    fun allInvalidDraftsLockConfirmButFooterWaitsForInteraction() {
        val now = tappedAt + 60_000L
        val cases = listOf(
            QuickRecordDraft.create(RecordType.NURSING, tappedAt) to
                "请填写左侧或右侧喂养时长",
            QuickRecordDraft.create(RecordType.DIARY, tappedAt) to
                "请填写日记正文",
        )

        cases.forEach { (draft, expected) ->
            assertFalse(draft.canConfirm(nowMillis = now))
            assertNull(
                draft.footerValidationError(
                    nowMillis = now,
                    isDirty = false,
                    attemptedConfirm = false,
                ),
            )
            assertEquals(
                expected,
                draft.footerValidationError(
                    nowMillis = now,
                    isDirty = true,
                    attemptedConfirm = false,
                ),
            )
            assertEquals(
                expected,
                draft.footerValidationError(
                    nowMillis = now,
                    isDirty = false,
                    attemptedConfirm = true,
                ),
            )
        }
    }

    @Test
    fun intervalWarningsStayAtTheTimeFieldsInsteadOfRepeatingInTheFooter() {
        val draft = QuickRecordDraft.create(
            type = RecordType.SLEEP,
            timestamp = tappedAt,
            historical = true,
        )

        assertEquals(
            IntervalDurationPreview.Warning("请选择醒来时刻"),
            draft.intervalDurationPreview(nowMillis = tappedAt + 60_000L),
        )
        assertNull(
            draft.footerValidationError(
                nowMillis = tappedAt + 60_000L,
                isDirty = true,
                attemptedConfirm = false,
            ),
        )
    }

    @Test
    fun inlineValidationDefersWarningsExceptForAMissingRequiredEnd() {
        val now = tappedAt + 60_000L
        val manual = QuickRecordDraft.create(
            type = RecordType.SLEEP,
            timestamp = tappedAt,
            historical = true,
        )

        assertEquals(
            IntervalDurationPreview.Warning("请选择醒来时刻"),
            manual.visibleIntervalDurationPreview(
                nowMillis = now,
                isDirty = false,
                attemptedConfirm = false,
            ),
        )

        val nonPositive = manual.copy(endTimestamp = tappedAt)
        assertNull(
            nonPositive.visibleIntervalDurationPreview(
                nowMillis = now,
                isDirty = false,
                attemptedConfirm = false,
            ),
        )
        assertEquals(
            IntervalDurationPreview.Warning("醒来须晚于睡下"),
            nonPositive.visibleIntervalDurationPreview(
                nowMillis = now,
                isDirty = true,
                attemptedConfirm = false,
            ),
        )

        val future = manual.copy(endTimestamp = now + 1L)
        assertNull(
            future.visibleIntervalDurationPreview(
                nowMillis = now,
                isDirty = false,
                attemptedConfirm = false,
            ),
        )
        assertEquals(
            IntervalDurationPreview.Warning("不能选未来时刻"),
            future.visibleIntervalDurationPreview(
                nowMillis = now,
                isDirty = false,
                attemptedConfirm = true,
            ),
        )

        val valid = manual.copy(endTimestamp = tappedAt + 30_000L)
        assertEquals(
            IntervalDurationPreview.Duration("时长 不足1分"),
            valid.visibleIntervalDurationPreview(
                nowMillis = now,
                isDirty = false,
                attemptedConfirm = false,
            ),
        )
    }

    @Test
    fun pointRecordErrorAppearsWithoutInventingDuration() {
        val now = tappedAt + 60 * 60_000L
        val draft = QuickRecordDraft.create(RecordType.WALK, tappedAt)
            .copy(
                endTimestamp = tappedAt + 30 * 60_000L,
                note = "长".repeat(201),
            )

        assertNull(draft.intervalDurationPreview(nowMillis = now))
        assertEquals(
            "备注最多 200 字",
            draft.footerValidationError(
                nowMillis = now,
                isDirty = true,
                attemptedConfirm = false,
            ),
        )
        assertFalse(draft.canConfirm(nowMillis = now))
    }

    @Test
    fun futurePointInTimeRecordUsesTheSharedShortWarning() {
        val now = tappedAt
        val draft = QuickRecordDraft.create(
            type = RecordType.BATH,
            timestamp = now + 1L,
        )

        assertEquals("不能选未来时刻", draft.validationError(nowMillis = now))
        assertFalse(draft.canConfirm(nowMillis = now))
    }

    @Test
    fun requiredPurposeInformationBlocksEmptyConfirmation() {
        assertEquals(
            "请填写药品名称",
            QuickRecordDraft.create(RecordType.MEDICINE, tappedAt)
                .validationError(nowMillis = tappedAt + 1L),
        )
        assertEquals(
            "请填写内容",
            QuickRecordDraft.create(RecordType.BABY_FOOD, tappedAt)
                .validationError(nowMillis = tappedAt + 1L),
        )
        assertEquals(
            "请填写左侧或右侧喂养时长",
            QuickRecordDraft.create(RecordType.NURSING, tappedAt)
                .validationError(nowMillis = tappedAt + 1L),
        )
    }

    private fun record(
        type: RecordType,
        payload: String,
    ) = Record(
        id = 7,
        clientUuid = "record-7",
        babyId = 1,
        type = type,
        timestamp = tappedAt,
        createdByUserId = 1,
        payloadJson = payload,
        schemaVersion = 1,
        updatedAt = tappedAt,
    )
}
