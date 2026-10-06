package com.lezi.babylog.domain.carelog

import com.lezi.babylog.core.database.FulfillmentCandidateDao
import com.lezi.babylog.core.model.FulfillmentAdoptionStatus
import com.lezi.babylog.core.model.Record

/**
 * Timeline/export visibility for fulfillment winners vs conflict-not-adopted losers.
 *
 * Not related to ordinary `/v1/push` transport — "surface" means product UI/export.
 * Owned by carelog so read/query seams do not import careplan (one-way careplan → carelog).
 */
internal class FulfillmentSurface(
    private val fulfillmentCandidateDao: FulfillmentCandidateDao,
) {
    suspend fun filterSurfaceRecords(records: List<Record>): List<Record> {
        if (records.isEmpty()) return records
        val blocked = fulfillmentCandidateDao.listConflictNotAdoptedRecordUuids().toSet()
        if (blocked.isEmpty()) return records
        return records.filter { it.clientUuid !in blocked }
    }

    suspend fun isSurfaceRecord(clientUuid: String): Boolean {
        val linked = fulfillmentCandidateDao.listForRecord(clientUuid)
            .filter { it.deletedAt == null }
        if (linked.isEmpty()) return true
        return linked.none { it.adoptionStatus == FulfillmentAdoptionStatus.CONFLICT_NOT_ADOPTED }
    }
}
