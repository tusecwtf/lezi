package com.lezi.babylog.domain

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.Test

class GrowthMeasurementLifecycleTest {
    @Test
    fun createObserveUpdateDeleteUsesOneDisplayUnitLifecycle() = runTest {
        val store = FakeGrowthMeasurementRecordStore()
        val lifecycle = DefaultGrowthMeasurementLifecycle(store)
        val babyId = 7L
        val day = LocalDate.of(2026, 7, 23)
        val measuredAt = day.atTime(10, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val observe = ObserveGrowthMeasurements(
            babyId = babyId,
            type = RecordType.WEIGHT,
            birthday = LocalDate.of(2026, 1, 1),
            dueDate = null,
            correctedAge = false,
            zone = ZoneOffset.UTC,
        )

        val created = lifecycle.save(
            SaveGrowthMeasurement(
                babyId = babyId,
                type = RecordType.WEIGHT,
                displayValue = 6.35,
                measuredAt = measuredAt,
                note = "晨起",
                nowMillis = measuredAt + 1,
            ),
        ) as GrowthMeasurementSaveResult.Saved
        assertThat(lifecycle.observe(observe).first().single().displayValue)
            .isWithin(0.001).of(6.35)

        lifecycle.save(
            SaveGrowthMeasurement(
                babyId = babyId,
                type = RecordType.WEIGHT,
                displayValue = 6.5,
                measuredAt = measuredAt,
                note = "复测",
                existingRecordId = created.recordId,
                nowMillis = measuredAt + 1,
            ),
        )
        assertThat(lifecycle.observe(observe).first().single().note).isEqualTo("复测")

        lifecycle.delete(created.recordId)
        assertThat(lifecycle.observe(observe).first()).isEmpty()
    }

    @Test
    fun rejectsFutureNonPositiveAndCrossBabyEdit() = runTest {
        val store = FakeGrowthMeasurementRecordStore()
        val lifecycle = DefaultGrowthMeasurementLifecycle(store)
        val now = 2_000L
        val existing = lifecycle.save(
            SaveGrowthMeasurement(1, RecordType.HEIGHT, 66.0, 1_000L, null, nowMillis = now),
        ) as GrowthMeasurementSaveResult.Saved

        assertThat(
            lifecycle.save(
                SaveGrowthMeasurement(1, RecordType.HEIGHT, 0.0, 1_000L, null, nowMillis = now),
            ),
        ).isInstanceOf(GrowthMeasurementSaveResult.Rejected::class.java)
        assertThat(
            lifecycle.save(
                SaveGrowthMeasurement(1, RecordType.HEIGHT, 66.0, now + 1, null, nowMillis = now),
            ),
        ).isInstanceOf(GrowthMeasurementSaveResult.Rejected::class.java)
        assertThat(
            lifecycle.save(
                SaveGrowthMeasurement(
                    2,
                    RecordType.HEIGHT,
                    67.0,
                    1_000L,
                    null,
                    existing.recordId,
                    now,
                ),
            ),
        ).isInstanceOf(GrowthMeasurementSaveResult.Rejected::class.java)
    }
}

private class FakeGrowthMeasurementRecordStore : GrowthMeasurementRecordStore {
    private val records = MutableStateFlow<List<Record>>(emptyList())
    private var nextId = 1L

    override fun observe(babyId: Long, type: RecordType): Flow<List<Record>> =
        records.map { values ->
            values.filter { it.babyId == babyId && it.type == type && it.deletedAt == null }
        }

    override suspend fun get(recordId: Long): Record? =
        records.value.firstOrNull { it.id == recordId && it.deletedAt == null }

    override suspend fun add(
        babyId: Long,
        type: RecordType,
        timestamp: Long,
        note: String?,
        payloadJson: String,
    ): Long {
        val id = nextId++
        records.update {
            it + Record(
                id = id,
                clientUuid = "growth-$id",
                babyId = babyId,
                type = type,
                timestamp = timestamp,
                note = note,
                createdByUserId = 1,
                payloadJson = payloadJson,
                schemaVersion = 2,
                updatedAt = timestamp,
            )
        }
        return id
    }

    override suspend fun update(id: Long, timestamp: Long, note: String?, payloadJson: String) {
        records.update { values ->
            values.map { record ->
                if (record.id == id) {
                    record.copy(
                        timestamp = timestamp,
                        note = note,
                        payloadJson = payloadJson,
                        schemaVersion = 2,
                        updatedAt = timestamp,
                    )
                } else {
                    record
                }
            }
        }
    }

    override suspend fun delete(recordId: Long) {
        records.update { values -> values.filterNot { it.id == recordId } }
    }
}
