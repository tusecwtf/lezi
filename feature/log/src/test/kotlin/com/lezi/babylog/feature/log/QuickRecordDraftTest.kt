package com.lezi.babylog.feature.log

import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.CarePlan
import com.lezi.babylog.core.model.CarePlanStatus
import com.lezi.babylog.core.model.RecordType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickRecordDraftTest {
    @Test
    fun editingIntentOnlyMilkPlanDoesNotInventAnAmount() {
        val plan = CarePlan(
            id = 9,
            clientUuid = "next-feed",
            babyId = 3,
            type = RecordType.FORMULA,
            scheduledAt = tappedAt + 60_000,
            scheduledZoneId = "Asia/Shanghai",
            payloadJson = """{"amount_ml":0}""",
            status = CarePlanStatus.PENDING,
            updatedAt = tappedAt,
        )

        val draft = QuickRecordDraft.fromCarePlanForEdit(plan)

        assertEquals(0, draft.amountMl)
        assertTrue(draft.canConfirm(nowMillis = tappedAt))
        assertTrue(draft.toSaveCommand().payloadJson.contains("\"amount_ml\":0"))
    }

    @Test
    fun nextFeedPrompt_onlyFollowsNewFeedFact() {
        assertTrue(shouldOfferNextFeedPlan(ComposerWriteDecision.AddRecord, RecordType.NURSING))
        assertTrue(shouldOfferNextFeedPlan(ComposerWriteDecision.AddRecord, RecordType.FORMULA))
        assertTrue(shouldOfferNextFeedPlan(ComposerWriteDecision.AddRecord, RecordType.PUMPED_FEED))
        assertFalse(shouldOfferNextFeedPlan(ComposerWriteDecision.CreateCarePlan, RecordType.NURSING))
        assertFalse(shouldOfferNextFeedPlan(ComposerWriteDecision.FulfillCarePlan, RecordType.FORMULA))
        assertFalse(shouldOfferNextFeedPlan(ComposerWriteDecision.AddRecord, RecordType.SLEEP))
    }

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
    fun composerEditReencodesCurrentPayloadFields() {
        val source = record(
            type = RecordType.FORMULA,
            payload = """{"amount_ml":120,"prepared_ml":135,"duration_min":8}""",
        )

        val command = QuickRecordDraft.fromRecord(source)
            .copy(amountMl = 135)
            .toSaveCommand()

        assertEquals(
            """{"amount_ml":135,"prepared_ml":135,"duration_min":8}""",
            command.payloadJson,
        )
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
            payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
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
        // Future sleep is schedule-care intent (ticket 17), not a fact open interval.
        assertEquals(ComposerWorkMode.ScheduleCare, futureSleepDown.workMode(nowMillis = now))
        assertNull(futureSleepDown.intervalDurationPreview(nowMillis = now))
        assertTrue(futureSleepDown.canConfirm(nowMillis = now))
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
    fun futurePointInTimeCreateBecomesScheduleCareWhileFulfillRejectsFuture() {
        val now = tappedAt
        val draft = QuickRecordDraft.create(
            type = RecordType.BATH,
            timestamp = now + 1L,
        )

        assertEquals(ComposerWorkMode.ScheduleCare, draft.workMode(nowMillis = now))
        assertEquals("安排护理", draft.workModeTitle(nowMillis = now))
        assertEquals("安排护理", sheetKicker(draft, nowMillis = now))
        assertEquals("确认安排", draft.confirmLabel(nowMillis = now))
        assertNull(draft.validationError(nowMillis = now))
        assertTrue(draft.canConfirm(nowMillis = now))

        val fact = QuickRecordDraft.create(type = RecordType.BATH, timestamp = now - 1L)
        assertEquals(ComposerWorkMode.RecordFact, fact.workMode(nowMillis = now))
        assertEquals("记录事实", sheetKicker(fact, nowMillis = now))

        val fulfill = draft.copy(carePlanId = 42L)
        assertEquals(ComposerWorkMode.FulfillPlan, fulfill.workMode(nowMillis = now))
        assertEquals("完成护理计划", sheetKicker(fulfill, nowMillis = now))
        assertEquals("确认完成", fulfill.confirmLabel(nowMillis = now))
        assertEquals("不能选未来时刻", fulfill.validationError(nowMillis = now))
        assertFalse(fulfill.canConfirm(nowMillis = now))
    }

    @Test
    fun explicitScheduleIntentNeverDegradesToAFactAfterItsTimePasses() {
        val scheduledAt = tappedAt + 60_000L
        val afterScheduledAt = scheduledAt + 1L
        val draft = QuickRecordDraft.create(
            type = RecordType.BATH,
            timestamp = scheduledAt,
            createIntent = ComposerCreateIntent.ScheduleCare,
        )

        assertEquals(
            ComposerWorkMode.ScheduleCare,
            draft.workMode(nowMillis = afterScheduledAt),
        )
        assertEquals(
            ComposerWriteDecision.CreateCarePlan,
            draft.writeDecision(nowMillis = afterScheduledAt),
        )
        assertEquals(
            CARE_PLAN_TIME_NOT_FUTURE_WARNING,
            draft.validationError(nowMillis = afterScheduledAt),
        )
        assertFalse(draft.canConfirm(nowMillis = afterScheduledAt))
        assertEquals("确认安排", draft.confirmLabel(nowMillis = afterScheduledAt))

        val adjusted = draft.copy(timestamp = afterScheduledAt + 60_000L)
        assertNull(adjusted.validationError(nowMillis = afterScheduledAt))
        assertTrue(adjusted.canConfirm(nowMillis = afterScheduledAt))

        val ordinaryFact = QuickRecordDraft.create(
            type = RecordType.BATH,
            timestamp = scheduledAt,
        )
        assertEquals(
            ComposerWriteDecision.AddRecord,
            ordinaryFact.writeDecision(nowMillis = afterScheduledAt),
        )
    }

    @Test
    fun scheduledSleepCreatesAPlanInsteadOfStartingAnOpenSleepFact() {
        val scheduledAt = tappedAt + 60_000L
        val explicit = QuickRecordDraft.create(
            type = RecordType.SLEEP,
            timestamp = scheduledAt,
            createIntent = ComposerCreateIntent.ScheduleCare,
        )
        val timestampDerived = QuickRecordDraft.create(
            type = RecordType.SLEEP,
            timestamp = scheduledAt,
        )

        assertEquals(SleepDraftAction.SleepDown, explicit.sleepAction)
        assertEquals(ComposerWorkMode.ScheduleCare, explicit.workMode(nowMillis = tappedAt))
        assertEquals(
            ComposerWriteDecision.CreateCarePlan,
            explicit.writeDecision(nowMillis = tappedAt),
        )
        assertEquals(
            ComposerWriteDecision.CreateCarePlan,
            timestampDerived.writeDecision(nowMillis = tappedAt),
        )
    }

    @Test
    fun sleepPlanChromeIsNeutralWhileFactAndFulfillKeepStateActions() {
        val schedule = QuickRecordDraft.create(
            type = RecordType.SLEEP,
            timestamp = tappedAt + 60_000L,
            createIntent = ComposerCreateIntent.ScheduleCare,
        )
        val schedulePolicy = sleepComposerPolicy(schedule, nowMillis = tappedAt)

        assertTrue(schedulePolicy.isPlanIntent)
        assertEquals("睡眠", schedulePolicy.sheetTitle)
        assertEquals("计划时间", schedulePolicy.timeSectionLabel)
        assertEquals("睡眠", schedulePolicy.primaryTimeLabel)
        assertFalse(schedulePolicy.showWakeToggle)
        assertEquals("月亮图标，安排睡眠", schedulePolicy.animationDescription)
        assertEquals(
            "选择睡眠时刻",
            clockDialogTitle(schedule, selectingEnd = false, nowMillis = tappedAt),
        )

        val derivedSchedule = schedule.copy(
            createIntent = ComposerCreateIntent.DeriveFromTimestamp,
        )
        assertTrue(sleepComposerPolicy(derivedSchedule, nowMillis = tappedAt).isPlanIntent)

        val editPlan = schedule.copy(
            carePlanId = 9L,
            editCarePlan = true,
            timestamp = tappedAt - 1L,
        )
        assertTrue(sleepComposerPolicy(editPlan, nowMillis = tappedAt).isPlanIntent)

        val convert = QuickRecordDraft.create(
            type = RecordType.SLEEP,
            timestamp = tappedAt - 60_000L,
        ).copy(
            existingRecordId = 10L,
            timestamp = tappedAt + 60_000L,
        )
        val convertPolicy = sleepComposerPolicy(convert, nowMillis = tappedAt)
        assertTrue(convertPolicy.isPlanIntent)
        assertEquals("睡眠", convertPolicy.sheetTitle)
        assertFalse(convertPolicy.showWakeToggle)
        assertEquals(
            "选择睡眠时刻",
            clockDialogTitle(convert, selectingEnd = false, nowMillis = tappedAt),
        )

        val fulfill = schedule.copy(
            carePlanId = 9L,
            editCarePlan = false,
            timestamp = tappedAt,
        )
        val fulfillPolicy = sleepComposerPolicy(fulfill, nowMillis = tappedAt + 1L)
        assertFalse(fulfillPolicy.isPlanIntent)
        assertEquals("睡下", fulfillPolicy.sheetTitle)
        assertEquals("睡下时间", fulfillPolicy.timeSectionLabel)
        assertTrue(fulfillPolicy.showWakeToggle)
        assertEquals("月亮轻轻摇动，准备睡下", fulfillPolicy.animationDescription)
        assertEquals(
            "选择睡下时刻",
            clockDialogTitle(fulfill, selectingEnd = false, nowMillis = tappedAt + 1L),
        )
    }

    @Test
    fun startTimePickerUsesDraftAwarePlanAndFactRules() {
        val schedule = QuickRecordDraft.create(
            type = RecordType.SLEEP,
            timestamp = tappedAt + 60_000L,
            createIntent = ComposerCreateIntent.ScheduleCare,
        )
        assertNull(
            schedule.startTimeRejectionMessage(
                candidateStartTimestamp = tappedAt + 120_000L,
                candidateEndTimestamp = null,
                nowMillis = tappedAt,
            ),
        )
        assertEquals(
            CARE_PLAN_TIME_NOT_FUTURE_WARNING,
            schedule.startTimeRejectionMessage(
                candidateStartTimestamp = tappedAt - 1L,
                candidateEndTimestamp = null,
                nowMillis = tappedAt,
            ),
        )

        val editPlan = schedule.copy(carePlanId = 11L, editCarePlan = true)
        assertNull(
            editPlan.startTimeRejectionMessage(
                candidateStartTimestamp = tappedAt - 1L,
                candidateEndTimestamp = null,
                nowMillis = tappedAt,
            ),
        )
        assertNull(
            editPlan.startTimeRejectionMessage(
                candidateStartTimestamp = tappedAt + 180_000L,
                candidateEndTimestamp = null,
                nowMillis = tappedAt,
            ),
        )

        val convert = QuickRecordDraft.create(
            type = RecordType.SLEEP,
            timestamp = tappedAt - 60_000L,
        ).copy(existingRecordId = 12L)
        assertNull(
            convert.startTimeRejectionMessage(
                candidateStartTimestamp = tappedAt + 240_000L,
                candidateEndTimestamp = null,
                nowMillis = tappedAt,
            ),
        )

        val fulfill = schedule.copy(carePlanId = 13L, editCarePlan = false)
        assertEquals(
            FUTURE_TIME_WARNING,
            fulfill.startTimeRejectionMessage(
                candidateStartTimestamp = tappedAt + 300_000L,
                candidateEndTimestamp = null,
                nowMillis = tappedAt,
            ),
        )
    }

    @Test
    fun fromCarePlanHydratesPlanFieldSnapshotIntoFulfillDraft() {
        val plan = com.lezi.babylog.core.model.CarePlan(
            id = 9L,
            clientUuid = "plan-uuid",
            babyId = 1L,
            type = RecordType.PEE,
            scheduledAt = tappedAt + 60_000L,
            scheduledZoneId = "Asia/Shanghai",
            note = "换尿布",
            payloadJson = """{"pee_amount":1}""",
            schemaVersion = 2,
            updatedAt = tappedAt,
        )
        val draft = QuickRecordDraft.fromCarePlan(plan, actualTimestamp = tappedAt)
        assertEquals(9L, draft.carePlanId)
        assertNull(draft.existingRecordId)
        assertEquals(tappedAt, draft.timestamp)
        assertEquals(1, draft.peeAmount)
        assertEquals("换尿布", draft.note)
        assertEquals(ComposerWorkMode.FulfillPlan, draft.workMode(nowMillis = tappedAt))
        assertEquals("完成护理计划", sheetKicker(draft))
    }

    @Test
    fun fulfillDraftCarriesPlanPhotosWithoutOwningPlanSourcePaths() {
        val plan = com.lezi.babylog.core.model.CarePlan(
            id = 15L,
            clientUuid = "plan-photos",
            babyId = 1L,
            type = RecordType.DIARY,
            scheduledAt = tappedAt + 60_000L,
            scheduledZoneId = "UTC",
            note = "记",
            payloadJson = """{"body":"x"}""",
            schemaVersion = 2,
            updatedAt = tappedAt,
        )
        // Composer fulfill path sets photos from listCarePlanPhotoPaths and sourcePhotos empty.
        val draft = QuickRecordDraft.fromCarePlan(plan, actualTimestamp = tappedAt).copy(
            photos = listOf("p1.jpg", "p2.jpg"),
            sourcePhotos = emptyList(),
        )
        assertEquals(listOf("p1.jpg", "p2.jpg"), draft.photos)
        assertTrue(draft.sourcePhotos.isEmpty())
        assertEquals(ComposerWorkMode.FulfillPlan, draft.workMode(nowMillis = tappedAt))
    }

    @Test
    fun editPlanModeDoesNotFulfillAndAllowsPastScheduledTime() {
        val plan = com.lezi.babylog.core.model.CarePlan(
            id = 11L,
            clientUuid = "plan-edit",
            babyId = 1L,
            type = RecordType.BATH,
            scheduledAt = tappedAt + 60_000L,
            scheduledZoneId = "UTC",
            note = "洗澡",
            payloadJson = "{}",
            schemaVersion = 2,
            updatedAt = tappedAt,
        )
        val draft = QuickRecordDraft.fromCarePlanForEdit(plan)
            .copy(timestamp = tappedAt - 5_000L)
        assertTrue(draft.editCarePlan)
        assertEquals(ComposerWorkMode.EditPlan, draft.workMode(nowMillis = tappedAt))
        assertEquals("编辑护理计划", draft.workModeTitle(nowMillis = tappedAt))
        assertEquals("编辑护理计划", sheetKicker(draft, nowMillis = tappedAt))
        assertEquals("保存计划", draft.confirmLabel(nowMillis = tappedAt))
        assertNull(draft.validationError(nowMillis = tappedAt))
        assertTrue(draft.canConfirm(nowMillis = tappedAt))
        // Fulfill path still blocks future actual times.
        val fulfill = draft.copy(editCarePlan = false, timestamp = tappedAt + 1L)
        assertEquals(ComposerWorkMode.FulfillPlan, fulfill.workMode(nowMillis = tappedAt))
        assertEquals("不能选未来时刻", fulfill.validationError(nowMillis = tappedAt))
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

    @Test
    fun nursingAndSleepScheduleAllowEmptyIntentPayload() {
        val now = tappedAt
        val nursingSchedule = QuickRecordDraft.create(
            type = RecordType.NURSING,
            timestamp = now + 60_000L,
        )
        assertEquals(ComposerWorkMode.ScheduleCare, nursingSchedule.workMode(nowMillis = now))
        assertNull(nursingSchedule.validationError(nowMillis = now))
        assertTrue(nursingSchedule.canConfirm(nowMillis = now))

        val sleepSchedule = QuickRecordDraft.create(
            type = RecordType.SLEEP,
            timestamp = now + 60_000L,
        )
        assertEquals(ComposerWorkMode.ScheduleCare, sleepSchedule.workMode(nowMillis = now))
        assertNull(sleepSchedule.validationError(nowMillis = now))
        assertTrue(sleepSchedule.canConfirm(nowMillis = now))
    }

    @Test
    fun sleepFulfillDefaultsToOpenSleepDownAction() {
        val plan = com.lezi.babylog.core.model.CarePlan(
            id = 21L,
            clientUuid = "sleep-plan",
            babyId = 1L,
            type = RecordType.SLEEP,
            scheduledAt = tappedAt + 60_000L,
            scheduledZoneId = "UTC",
            payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
            schemaVersion = 2,
            updatedAt = tappedAt,
        )
        val draft = QuickRecordDraft.fromCarePlan(plan, actualTimestamp = tappedAt)
        assertEquals(SleepDraftAction.SleepDown, draft.sleepAction)
        assertNull(draft.endTimestamp)
        assertNull(draft.validationError(nowMillis = tappedAt))
        assertTrue(draft.canConfirm(nowMillis = tappedAt))
    }

    @Test
    fun concreteCustomItemRoundTripsIdentityAndSnapshot() {
        val draft = QuickRecordDraft.create(
            type = RecordType.CUSTOM,
            timestamp = tappedAt,
            customItemId = 42L,
            customTitle = "抚触",
            customIconSlot = 3,
        ).copy(customDetail = "睡前")

        val command = draft.toSaveCommand()

        assertEquals(RecordType.CUSTOM, command.type)
        assertTrue(command.payloadJson.contains("\"title\":\"抚触\""))
        assertTrue(command.payloadJson.contains("\"detail\":\"睡前\""))
        assertTrue(command.payloadJson.contains("\"custom_item_id\":42"))
        assertTrue(command.payloadJson.contains("\"icon_slot\":3"))
        assertEquals("抚触", sheetTitle(draft))
    }

    @Test
    fun scheduleCareDefaultsProjectToSystemCalendarOn() {
        val now = 1_700_000_000_000L
        val draft = QuickRecordDraft.create(RecordType.FORMULA, now + 60_000L)
        assertEquals(ComposerWorkMode.ScheduleCare, draft.workMode(nowMillis = now))
        assertTrue(draft.projectToSystemCalendar)
        val off = draft.copy(projectToSystemCalendar = false)
        assertFalse(off.projectToSystemCalendar)
    }

    @Test
    fun editingRecordToFutureRequiresConvertNotOrdinarySave() {
        val now = tappedAt
        val source = Record(
            id = 55L,
            clientUuid = "formula-55",
            babyId = 1L,
            type = RecordType.FORMULA,
            timestamp = now - 60_000L,
            note = "原备注",
            payloadJson = """{"amount_ml":120}""",
            updatedAt = now - 60_000L,
        )
        val draft = QuickRecordDraft.fromRecord(source).copy(timestamp = now + 90_000L)

        assertTrue(draft.needsConvertToCarePlan(nowMillis = now))
        // Still RecordFact work mode — convert is an explicit action, not silent schedule.
        assertEquals(ComposerWorkMode.RecordFact, draft.workMode(nowMillis = now))
        assertEquals("转为护理计划", draft.workModeTitle(nowMillis = now))
        assertEquals("转为护理计划", sheetKicker(draft, nowMillis = now))
        assertEquals("转为护理计划", draft.confirmLabel(nowMillis = now))
        assertNull(draft.validationError(nowMillis = now))
        assertTrue(draft.canConfirm(nowMillis = now))

        // Cancel path: putting time back to past removes convert need and restores save.
        val cancelled = draft.copy(timestamp = now - 1_000L)
        assertFalse(cancelled.needsConvertToCarePlan(nowMillis = now))
        assertEquals("保存修改", cancelled.confirmLabel(nowMillis = now))
        assertTrue(cancelled.canConfirm(nowMillis = now))

    }

    @Test
    fun convertSleepAndNursingUseIntentOnlyValidation() {
        val now = tappedAt
        val openSleep = Record(
            id = 70L,
            clientUuid = "sleep-70",
            babyId = 1L,
            type = RecordType.SLEEP,
            timestamp = now - 30_000L,
            endTimestamp = null,
            note = null,
            payloadJson = """{"is_nap":true,"anomaly_flag":false}""",
            updatedAt = now - 30_000L,
        )
        val sleepDraft = QuickRecordDraft.fromRecord(openSleep).copy(timestamp = now + 60_000L)
        assertTrue(sleepDraft.needsConvertToCarePlan(nowMillis = now))
        assertNull(sleepDraft.intervalDurationPreview(nowMillis = now))
        assertNull(sleepDraft.validationError(nowMillis = now))
        assertTrue(sleepDraft.canConfirm(nowMillis = now))

        val nursing = Record(
            id = 71L,
            clientUuid = "nursing-71",
            babyId = 1L,
            type = RecordType.NURSING,
            timestamp = now - 10_000L,
            payloadJson =
                """{"left_min":0,"right_min":0,"order":"LR","record_mode":"end"}""",
            updatedAt = now - 10_000L,
        )
        val nursingDraft = QuickRecordDraft.fromRecord(nursing).copy(
            timestamp = now + 120_000L,
            leftMin = "0",
            rightMin = "0",
        )
        assertTrue(nursingDraft.needsConvertToCarePlan(nowMillis = now))
        assertNull(nursingDraft.validationError(nowMillis = now))
        assertTrue(nursingDraft.canConfirm(nowMillis = now))
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
        payloadJson = payload,
        schemaVersion = 2,
        updatedAt = tappedAt,
    )
}
