package com.lezi.babylog.domain

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.model.GrowthReferenceBand
import com.lezi.babylog.core.model.GrowthReferenceSeries
import com.lezi.babylog.core.model.GrowthMeasurementFacts
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordPayloadCodec
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.Sex
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
        val lifecycle = DefaultGrowthMeasurementLifecycle(store, FakeGrowthReferenceSource())
        val babyId = 7L
        val day = LocalDate.of(2026, 7, 23)
        val measuredAt = day.atTime(10, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val observe = ObserveGrowthMeasurements(
            babyId = babyId,
            type = RecordType.WEIGHT,
            birthday = LocalDate.of(2026, 1, 1),
            zone = ZoneOffset.UTC,
            sex = Sex.MALE,
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
        val createdSnapshot = lifecycle.observe(observe).first()
        assertThat(createdSnapshot.measurements.single().displayValue)
            .isWithin(0.001).of(6.35)
        assertThat(createdSnapshot.referenceBands).hasSize(2)

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
        assertThat(lifecycle.observe(observe).first().measurements.single().note)
            .isEqualTo("复测")

        assertThat(lifecycle.delete(babyId, created.recordId)).isTrue()
        assertThat(lifecycle.observe(observe).first().measurements).isEmpty()
    }

    @Test
    fun rejectsFutureNonPositiveCrossBabyAndCrossMetricMutations() = runTest {
        val store = FakeGrowthMeasurementRecordStore()
        val lifecycle = DefaultGrowthMeasurementLifecycle(store, FakeGrowthReferenceSource())
        val now = 2_000L
        val existing = lifecycle.save(
            SaveGrowthMeasurement(1, RecordType.HEIGHT, 66.0, 1_000L, null, nowMillis = now),
        ) as GrowthMeasurementSaveResult.Saved

        assertThat(
            lifecycle.save(
                SaveGrowthMeasurement(1, RecordType.HEIGHT, 0.0, 1_000L, null, nowMillis = now),
            ),
        ).isInstanceOf(GrowthMeasurementSaveResult.Rejected::class.java)

        assertThat(lifecycle.delete(2, existing.recordId)).isFalse()
        assertThat(store.get(existing.recordId)).isNotNull()
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
        assertThat(
            lifecycle.save(
                SaveGrowthMeasurement(
                    1,
                    RecordType.WEIGHT,
                    6.5,
                    1_000L,
                    null,
                    existing.recordId,
                    now,
                ),
            ),
        ).isInstanceOf(GrowthMeasurementSaveResult.Rejected::class.java)
    }

    @Test
    fun lifecycleOwnsReferenceWarningAndDisplayValueValidation() = runTest {
        val store = FakeGrowthMeasurementRecordStore()
        val lifecycle = DefaultGrowthMeasurementLifecycle(store, FakeGrowthReferenceSource())
        val day = LocalDate.of(2026, 7, 23)
        val measuredAt = day.atTime(10, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

        assertThat(lifecycle.validationError(RecordType.WEIGHT, 101.0))
            .isEqualTo("体重需在 0–100 kg 之间")
        lifecycle.save(
            SaveGrowthMeasurement(
                babyId = 7,
                type = RecordType.WEIGHT,
                displayValue = 12.0,
                measuredAt = measuredAt,
                note = null,
                nowMillis = measuredAt + 1,
            ),
        )

        val snapshot = lifecycle.observe(
            ObserveGrowthMeasurements(
                babyId = 7,
                type = RecordType.WEIGHT,
                birthday = LocalDate.of(2026, 1, 23),
                zone = ZoneOffset.UTC,
                sex = Sex.FEMALE,
            ),
        ).first()

        assertThat(snapshot.referenceBands).hasSize(2)
        assertThat(snapshot.referenceValidUntilMonthExclusive).isEqualTo(7f)
        assertThat(snapshot.measurements.single().referenceWarning)
            .isEqualTo("该数值达到或高于同年龄同性别参考带，请先复测；如持续偏离请咨询儿保或儿科。")
    }

    @Test
    fun growthAndComposerPayloadPathsShareMeasurementValidation() {
        val lifecycle = DefaultGrowthMeasurementLifecycle(
            FakeGrowthMeasurementRecordStore(),
            FakeGrowthReferenceSource(),
        )
        val cases = listOf(
            RecordType.WEIGHT to 6.35,
            RecordType.WEIGHT to 0.0,
            RecordType.WEIGHT to 101.0,
            RecordType.HEIGHT to 66.0,
            RecordType.HEIGHT to 251.0,
            RecordType.HEAD to 42.0,
        )

        cases.forEach { (type, value) ->
            val payload = GrowthMeasurementFacts.payload(type, value)
            val composerError = payload?.let {
                RecordPayloadCodec.validate(it).singleOrNull()
            } ?: GrowthMeasurementFacts.validationError(type, value)

            assertThat(lifecycle.validationError(type, value)).isEqualTo(composerError)
        }
    }
}

private class FakeGrowthReferenceSource : GrowthReferenceSource {
    override fun reference(type: RecordType, sex: Sex?): GrowthReferenceSeries? =
        if (type == RecordType.WEIGHT) {
            GrowthReferenceSeries(
                bands = listOf(
                    GrowthReferenceBand(month = 0f, p3 = 2.5f, p50 = 3.3f, p97 = 4.5f),
                    GrowthReferenceBand(month = 6f, p3 = 6.4f, p50 = 7.9f, p97 = 9.8f),
                ),
                validUntilMonthExclusive = 7f,
            )
        } else {
            null
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
