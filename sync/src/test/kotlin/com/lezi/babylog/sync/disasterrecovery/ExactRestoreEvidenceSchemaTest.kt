package com.lezi.babylog.sync.disasterrecovery

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.CustomItemEntity
import com.lezi.babylog.core.database.FulfillmentCandidateEntity
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.causal.WakeObservationEntity
import com.lezi.babylog.sync.localBaby
import com.lezi.babylog.sync.localCarePlan
import com.lezi.babylog.sync.localRecord
import java.lang.reflect.Modifier
import org.junit.Test

/** Test-only reflection guards the explicitly maintained local evidence schema as entities evolve. */
class ExactRestoreEvidenceSchemaTest {
    @Test
    fun everyLocalFieldHasExactlyOneEntryWithItsOwnValueAndScalarType() {
        val rows = listOf(
            localBaby(),
            localRecord(1),
            localCarePlan(1),
            CustomItemEntity(clientUuid = "custom", familyId = 1, name = "custom", iconSlot = 2, updatedAt = 120),
            FulfillmentCandidateEntity(clientUuid = "candidate", carePlanClientUuid = "plan",
                recordClientUuid = "record", confirmedAt = 120, updatedAt = 120),
            WakeObservationEntity(clientUuid = "wake", sleepRecordClientUuid = "record", wakeTimestamp = 150,
                updatedAt = 150),
            MediaAssetEntity(clientUuid = "photo", recordId = 1, localUri = "photo.jpg", createdAt = 120),
        )
        for (row in rows) {
            val fields = row.javaClass.declaredFields.filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
            // Distinct synthetic values catch accidental wiring to a neighboring nullable field too.
            // These objects are used only by the encoder, never persisted or passed to product code.
            fields.forEachIndexed { index, field ->
                field.isAccessible = true
                field.set(row, when (field.type) {
                    String::class.java -> "${row.javaClass.simpleName}.${field.name}:\u0000🍒"
                    Int::class.javaPrimitiveType, Int::class.javaObjectType -> Int.MIN_VALUE + index
                    Long::class.javaPrimitiveType, Long::class.javaObjectType -> Long.MIN_VALUE + index
                    Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType -> index % 2 == 0
                    else -> error("new local evidence type needs an explicit codec: ${field.type}")
                })
            }
            val sink = RecordingFields()
            writeExactRestoreEvidenceFields(row, sink)

            assertThat(sink.entries.map { it.name }).containsExactlyElementsIn(fields.map { it.name })
            for (field in fields) {
                val entry = sink.entries.single { it.name == field.name }
                assertThat(entry.value).isEqualTo(field.get(row))
                val expectedType = when (field.type) {
                    String::class.java -> "string"
                    Int::class.javaPrimitiveType, Int::class.javaObjectType -> "int"
                    Long::class.javaPrimitiveType, Long::class.javaObjectType -> "long"
                    Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType -> "boolean"
                    else -> error("unrecognized evidence type")
                }
                assertThat(entry.type).isEqualTo(expectedType)
            }
        }
    }

    private data class Entry(val name: String, val type: String, val value: Any?)

    private class RecordingFields : ExactRestoreEvidenceFields {
        val entries = mutableListOf<Entry>()
        override fun text(name: String, value: String?) { entries += Entry(name, "string", value) }
        override fun int(name: String, value: Int?) { entries += Entry(name, "int", value) }
        override fun long(name: String, value: Long?) { entries += Entry(name, "long", value) }
        override fun boolean(name: String, value: Boolean) { entries += Entry(name, "boolean", value) }
    }
}
