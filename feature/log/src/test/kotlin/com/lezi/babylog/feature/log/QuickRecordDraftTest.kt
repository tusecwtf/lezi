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
    fun wakeConfirmationUpdatesTheExistingOpenSleepAndPreservesPayload() {
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
        assertEquals(open.payloadJson, command.payloadJson)
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
}
