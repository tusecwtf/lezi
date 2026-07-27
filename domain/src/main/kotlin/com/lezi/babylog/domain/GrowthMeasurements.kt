package com.lezi.babylog.domain

import com.lezi.babylog.core.model.CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION
import com.lezi.babylog.core.model.GrowthMeasurementFacts
import com.lezi.babylog.core.model.GrowthReferenceBand
import com.lezi.babylog.core.model.MeasurementPayload
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordPayloadCodec
import com.lezi.babylog.core.model.RecordPayloadDocument
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.RecordTime
import com.lezi.babylog.core.model.Sex
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Observable lifecycle interface for growth measurements.
 *
 * Callers submit display-unit values and receive storage-independent facts.
 * DAO records, JSON, unit conversion, validation and refresh orchestration are
 * hidden by the implementation.
 */
interface GrowthMeasurementLifecycle {
    fun observe(request: ObserveGrowthMeasurements): Flow<GrowthMeasurementSnapshot>

    fun validationError(type: RecordType, displayValue: Double): String?

    suspend fun save(request: SaveGrowthMeasurement): GrowthMeasurementSaveResult

    suspend fun delete(babyId: Long, recordId: Long): Boolean
}

data class ObserveGrowthMeasurements(
    val babyId: Long,
    val type: RecordType,
    val birthday: LocalDate,
    val zone: ZoneId,
    val sex: Sex?,
)

data class GrowthMeasurementSnapshot(
    val measurements: List<GrowthMeasurementFact>,
    val referenceBands: List<GrowthReferenceBand>,
)

data class GrowthMeasurementFact(
    val recordId: Long,
    val type: RecordType,
    val displayValue: Double,
    val measuredAt: Long,
    val note: String?,
    val monthAge: Float,
    val referenceWarning: String?,
)

interface GrowthReferenceSource {
    fun bands(type: RecordType, sex: Sex?): List<GrowthReferenceBand>
}

data class SaveGrowthMeasurement(
    val babyId: Long,
    val type: RecordType,
    val displayValue: Double,
    val measuredAt: Long,
    val note: String?,
    val existingRecordId: Long? = null,
    val nowMillis: Long = RecordTime.currentTimeMillis(),
)

sealed interface GrowthMeasurementSaveResult {
    data class Saved(val recordId: Long) : GrowthMeasurementSaveResult
    data class Rejected(val message: String) : GrowthMeasurementSaveResult
}

@Singleton
internal class DefaultGrowthMeasurementLifecycle @Inject constructor(
    private val store: GrowthMeasurementRecordStore,
    private val references: GrowthReferenceSource,
) : GrowthMeasurementLifecycle {
    override fun observe(
        request: ObserveGrowthMeasurements,
    ): Flow<GrowthMeasurementSnapshot> {
        val referenceBands = references.bands(request.type, request.sex)
        return store.observe(request.babyId, request.type).map { records ->
            val measurements = records.mapNotNull { record ->
                val payload = record.payload.payload as? MeasurementPayload
                    ?: return@mapNotNull null
                val measuredDate = Instant.ofEpochMilli(record.timestamp)
                    .atZone(request.zone)
                    .toLocalDate()
                val displayValue = GrowthMeasurementFacts.displayValue(payload)
                val monthAge = GrowthMeasurementFacts.monthAge(
                    birthday = request.birthday,
                    measuredDate = measuredDate,
                )
                GrowthMeasurementFact(
                    recordId = record.id,
                    type = record.type,
                    displayValue = displayValue,
                    measuredAt = record.timestamp,
                    note = record.note,
                    monthAge = monthAge,
                    referenceWarning = GrowthMeasurementFacts.referenceAt(
                        monthAge,
                        referenceBands,
                    )?.warningFor(displayValue.toFloat()),
                )
            }.sortedBy(GrowthMeasurementFact::measuredAt)
            GrowthMeasurementSnapshot(
                measurements = measurements,
                referenceBands = referenceBands,
            )
        }
    }

    override fun validationError(type: RecordType, displayValue: Double): String? =
        GrowthMeasurementFacts.validationError(type, displayValue)

    override suspend fun save(
        request: SaveGrowthMeasurement,
    ): GrowthMeasurementSaveResult {
        if (request.measuredAt > request.nowMillis) {
            return GrowthMeasurementSaveResult.Rejected("不能选未来时刻")
        }
        validationError(request.type, request.displayValue)?.let {
            return GrowthMeasurementSaveResult.Rejected(it)
        }
        val payload = GrowthMeasurementFacts.payload(request.type, request.displayValue)
            ?: return GrowthMeasurementSaveResult.Rejected("请填写有效数值")
        val existing = if (request.existingRecordId != null) {
            store.get(request.existingRecordId)
        } else {
            null
        }
        if (request.existingRecordId != null && existing == null) {
            return GrowthMeasurementSaveResult.Rejected("测量记录已不存在")
        }
        if (existing != null && existing.babyId != request.babyId) {
            return GrowthMeasurementSaveResult.Rejected("记录不属于当前宝宝")
        }
        if (existing != null && existing.type != request.type) {
            return GrowthMeasurementSaveResult.Rejected("测量类型不匹配")
        }
        val document = RecordPayloadDocument(
            type = request.type,
            payload = payload,
            schemaVersion = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
        )
        val payloadJson = RecordPayloadCodec.encode(document)
        val id = if (existing == null) {
            store.add(
                babyId = request.babyId,
                type = request.type,
                timestamp = request.measuredAt,
                note = request.note?.trim()?.ifBlank { null },
                payloadJson = payloadJson,
            )
        } else {
            store.update(
                id = existing.id,
                timestamp = request.measuredAt,
                note = request.note?.trim()?.ifBlank { null },
                payloadJson = payloadJson,
            )
            existing.id
        }
        return GrowthMeasurementSaveResult.Saved(id)
    }

    override suspend fun delete(babyId: Long, recordId: Long): Boolean {
        val record = store.get(recordId)
        if (record?.babyId != babyId) return false
        store.delete(recordId)
        return true
    }
}

internal interface GrowthMeasurementRecordStore {
    fun observe(babyId: Long, type: RecordType): Flow<List<Record>>
    suspend fun get(recordId: Long): Record?
    suspend fun add(
        babyId: Long,
        type: RecordType,
        timestamp: Long,
        note: String?,
        payloadJson: String,
    ): Long
    suspend fun update(id: Long, timestamp: Long, note: String?, payloadJson: String)
    suspend fun delete(recordId: Long)
}

@Singleton
internal class CareLogGrowthMeasurementRecordStore @Inject constructor(
    private val careLog: CareLog,
) : GrowthMeasurementRecordStore {
    override fun observe(babyId: Long, type: RecordType): Flow<List<Record>> =
        careLog.observeMeasurements(babyId, type)

    override suspend fun get(recordId: Long): Record? = careLog.getRecord(recordId)

    override suspend fun add(
        babyId: Long,
        type: RecordType,
        timestamp: Long,
        note: String?,
        payloadJson: String,
    ): Long = careLog.addRecord(
        babyId = babyId,
        type = type,
        timestamp = timestamp,
        note = note,
        payloadJson = payloadJson,
    )

    override suspend fun update(id: Long, timestamp: Long, note: String?, payloadJson: String) {
        careLog.updateRecord(
            id = id,
            timestamp = timestamp,
            endTimestamp = null,
            note = note,
            payloadJson = payloadJson,
            schemaVersion = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
        )
    }

    override suspend fun delete(recordId: Long) {
        careLog.deleteRecord(recordId)
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class GrowthMeasurementModule {
    @Binds
    abstract fun bindGrowthMeasurementLifecycle(
        implementation: DefaultGrowthMeasurementLifecycle,
    ): GrowthMeasurementLifecycle

    @Binds
    abstract fun bindGrowthMeasurementRecordStore(
        implementation: CareLogGrowthMeasurementRecordStore,
    ): GrowthMeasurementRecordStore
}
