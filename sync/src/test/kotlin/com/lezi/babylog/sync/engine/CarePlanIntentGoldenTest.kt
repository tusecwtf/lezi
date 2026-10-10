package com.lezi.babylog.sync.engine

import com.google.common.truth.Truth.assertWithMessage
import com.lezi.babylog.core.database.CarePlanEntity
import com.lezi.babylog.core.database.RecordEntity
import java.io.File
import kotlinx.serialization.json.*
import org.junit.Test

class CarePlanIntentGoldenTest {
    @Test
    fun planAndFactAcceptanceMatchesSharedCrossLanguageCorpus() {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .map { File(it, "config/care-plan-intent-v1-golden.json") }.first { it.isFile }
        val cases = Json.parseToJsonElement(root.readText()).jsonObject.getValue("cases").jsonArray
        for (entry in cases) {
            val row = entry.jsonObject
            val id = row.getValue("id").jsonPrimitive.content
            val type = row.getValue("type").jsonPrimitive.content
            val note = row["note"]?.jsonPrimitive?.contentOrNull
            val payload = row.getValue("payload").toString()
            val planAccepted = runCatching {
                SyncWireMapper.carePlan(CarePlanEntity(
                    clientUuid = "plan-$id", babyId = 1, type = type,
                    scheduledAt = 1000, scheduledZoneId = "UTC", note = note,
                    payloadJson = payload, updatedAt = 1,
                ), "synthetic-baby", null)
            }.isSuccess
            val recordAccepted = runCatching {
                SyncWireMapper.record(RecordEntity(
                    clientUuid = "record-$id", babyId = 1, type = type,
                    timestamp = 1000, note = note, payloadJson = payload, updatedAt = 1,
                ), "synthetic-baby")
            }.isSuccess
            assertWithMessage("$id plan").that(planAccepted).isEqualTo(row.getValue("plan_valid").jsonPrimitive.boolean)
            assertWithMessage("$id record").that(recordAccepted).isEqualTo(row.getValue("record_valid").jsonPrimitive.boolean)
        }
    }
}
