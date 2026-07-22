package com.lezi.babylog.domain

import com.lezi.babylog.core.common.newClientUuid
import com.lezi.babylog.core.database.RecordDao
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.model.RecordType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class RecordInteractor @Inject constructor(
    private val recordDao: RecordDao,
) {
    fun observeDay(babyId: Long, startInclusive: Long, endExclusive: Long): Flow<List<RecordEntity>> =
        recordDao.observeDay(babyId, startInclusive, endExclusive)

    suspend fun quickAdd(
        babyId: Long,
        type: RecordType,
        userId: Long,
        timestamp: Long = System.currentTimeMillis(),
        payloadJson: String = "{}",
        note: String? = null,
        endTimestamp: Long? = null,
    ): Long {
        val now = System.currentTimeMillis()
        return recordDao.upsert(
            RecordEntity(
                clientUuid = newClientUuid(),
                babyId = babyId,
                type = type.key,
                timestamp = timestamp,
                endTimestamp = endTimestamp,
                note = note,
                createdByUserId = userId,
                payloadJson = payloadJson,
                updatedAt = now,
            ),
        )
    }

    suspend fun softDelete(id: Long) {
        recordDao.softDelete(id, System.currentTimeMillis())
    }
}
