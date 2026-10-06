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

class QuickRecordDraftSerializationTest {
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
        assertTrue(shouldOfferNextFeedPlanForFact(RecordType.NURSING, true, null))
        assertTrue(shouldOfferNextFeedPlanForFact(RecordType.FORMULA, true, null))
        assertTrue(shouldOfferNextFeedPlanForFact(RecordType.PUMPED_FEED, true, null))
        assertFalse(shouldOfferNextFeedPlanForFact(RecordType.NURSING, false, null))
        assertFalse(shouldOfferNextFeedPlanForFact(RecordType.FORMULA, true, 8L))
        assertFalse(shouldOfferNextFeedPlanForFact(RecordType.SLEEP, true, null))
    }

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
    fun nursingEditRoundTripsAllOrdersIncludingSingleSideRecords() {
        val examples = listOf(
            NursingPayload(leftMinutes = 7, rightMinutes = 0, order = "L"),
            NursingPayload(leftMinutes = 0, rightMinutes = 9, order = "R"),
            NursingPayload(leftMinutes = 7, rightMinutes = 9, order = "LR"),
            NursingPayload(leftMinutes = 7, rightMinutes = 9, order = "RL"),
        )

        examples.forEach { expected ->
            val source = record(
                type = RecordType.NURSING,
                payload = com.lezi.babylog.core.model.RecordPayloadCodec.encode(
                    com.lezi.babylog.core.model.RecordPayloadDocument(
                        type = RecordType.NURSING,
                        payload = expected,
                        schemaVersion =
                            com.lezi.babylog.core.model.CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
                    ),
                ),
            )

            val draft = QuickRecordDraft.fromRecord(source)
            val saved = com.lezi.babylog.core.model.RecordPayloadCodec.decode(
                type = RecordType.NURSING,
                payloadJson = draft.toSaveCommand().payloadJson,
                schemaVersion = com.lezi.babylog.core.model.CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
            ).payload

            assertEquals(expected.leftMinutes.toString(), draft.leftMin)
            assertEquals(expected.rightMinutes.toString(), draft.rightMin)
            assertEquals(expected.order, draft.order)
            assertEquals(expected, saved)
            assertTrue(draft.canConfirm(nowMillis = tappedAt + 1L))
        }
    }

    @Test
    fun nursingComposerUsesSharedDurationAndAmountLimits() {
        val valid = QuickRecordDraft.create(RecordType.NURSING, tappedAt)
            .copy(leftMin = "1", rightMin = "0", order = "L")

        assertEquals(
            "左右时长需为 0–1440 分钟的整数",
            valid.copy(leftMin = "1441").validationError(tappedAt + 1L),
        )
        assertEquals(
            "奶量需在 1–999 ml 之间",
            valid.copy(nursingAmountMl = "1000").validationError(tappedAt + 1L),
        )
    }

    @Test
    fun nursingSharedIssuesTargetTheMatchingComposerControls() {
        val valid = QuickRecordDraft.create(RecordType.NURSING, tappedAt)
            .copy(leftMin = "1", rightMin = "0", order = "L")

        assertEquals(
            ComposerInvalidField.NursingDuration,
            valid.copy(leftMin = "1441").validationResult(tappedAt + 1L)?.field,
        )
        assertEquals(
            ComposerInvalidField.NursingOrder,
            valid.copy(order = "UNKNOWN").validationResult(tappedAt + 1L)?.field,
        )
        assertEquals(
            ComposerInvalidField.NursingAmount,
            valid.copy(nursingAmountMl = "1000").validationResult(tappedAt + 1L)?.field,
        )
    }

    @Test
    fun foodEditPreservesUntouchedLegacyAmountBytes() {
        listOf<String?>("几口", "半碗", " 半碗 ", "", null).forEach { amount ->
            val payload = if (amount == null) {
                """{"content":"南瓜泥"}"""
            } else {
                """{"content":"南瓜泥","amount":"$amount"}"""
            }
            val draft = QuickRecordDraft.fromRecord(
                record(type = RecordType.BABY_FOOD, payload = payload),
            ).copy(
                timestamp = tappedAt + 1L,
                note = "只改时间与备注",
            )

            assertTrue(draft.canConfirm(nowMillis = tappedAt + 2L))
            assertEquals(payload, draft.toSaveCommand().payloadJson)
        }
    }

    @Test
    fun touchingLegacyFoodAmountAppliesCurrentNormalization() {
        val source = record(
            type = RecordType.BABY_FOOD,
            payload = """{"content":"南瓜泥","amount":"几口"}""",
        )
        val draft = QuickRecordDraft.fromRecord(source).copy(foodAmount = "半碗")

        assertTrue(draft.canConfirm(nowMillis = tappedAt + 1L))
        assertEquals(
            """{"content":"南瓜泥","amount":"0.5碗"}""",
            draft.toSaveCommand().payloadJson,
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
            listOf("\"content\":\"南瓜米糊\"", "\"amount\":\"0.5碗\""),
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
    fun walkIsAPointRecordWithOptionalNote() {
        val walk = QuickRecordDraft.create(RecordType.WALK, tappedAt)
        val now = tappedAt + 90 * 60_000L

        assertNull(walk.intervalDurationPreview(nowMillis = now))
        assertNull(walk.validationError(nowMillis = now))
        assertTrue(walk.canConfirm(nowMillis = now))
        assertNull(walk.toSaveCommand().endTimestamp)
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
}
