package com.lezi.babylog.core.database

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Projects unresolved family work as atomic units, never as raw dirty rows. */
@Dao
interface PendingPublishDao {
    @Query(
        """
        SELECT COUNT(*) FROM (
            SELECT 'baby:' || b.clientUuid AS unitKey
            FROM babies b
            WHERE (b.syncDirty = 1 OR EXISTS (
                SELECT 1 FROM media_assets m
                WHERE m.babyId = b.id AND m.kind = 'avatar' AND m.syncDirty = 1
            )) AND NOT EXISTS (
                SELECT 1 FROM causal_transport_journal j
                WHERE j.journalKey = 'terminal-receipt:baby:' || b.clientUuid
                  AND j.contentEpoch = b.updatedAt
            )
            UNION ALL
            SELECT 'record:' || r.clientUuid
            FROM records r
            WHERE (r.syncDirty = 1 OR EXISTS (
                SELECT 1 FROM media_assets m
                WHERE m.recordId = r.id AND m.kind = 'log' AND m.syncDirty = 1
            )) AND NOT EXISTS (
                SELECT 1 FROM causal_transport_journal j
                WHERE j.journalKey = 'terminal-receipt:record:' || r.clientUuid
                  AND j.contentEpoch = r.updatedAt
            )
            UNION ALL
            SELECT 'care_plan:' || p.clientUuid
            FROM care_plans p
            WHERE (p.syncDirty = 1 OR EXISTS (
                SELECT 1 FROM media_assets m
                WHERE m.carePlanId = p.id AND m.kind = 'log' AND m.syncDirty = 1
            )) AND NOT EXISTS (
                SELECT 1 FROM causal_transport_journal j
                WHERE j.journalKey = 'terminal-receipt:care_plan:' || p.clientUuid
                  AND j.contentEpoch = p.updatedAt
            )
            UNION ALL
            SELECT 'custom_item:' || c.clientUuid
            FROM custom_items c
            WHERE c.syncDirty = 1 AND NOT EXISTS (
                SELECT 1 FROM causal_transport_journal j
                WHERE j.journalKey = 'terminal-receipt:custom_item:' || c.clientUuid
                  AND j.contentEpoch = c.updatedAt
            )
            UNION ALL
            SELECT 'wake_observation:' || w.clientUuid
            FROM wake_observations w
            WHERE (w.syncDirty = 1 OR EXISTS (
                SELECT 1 FROM media_assets m
                WHERE m.wakeObservationId = w.id AND m.kind = 'wake' AND m.syncDirty = 1
            )) AND NOT EXISTS (
                SELECT 1 FROM causal_transport_journal j
                WHERE j.journalKey = 'terminal-receipt:wake_observation:' || w.clientUuid
                  AND j.contentEpoch = w.updatedAt
            )
            UNION ALL
            SELECT 'fulfillment_candidate:' || f.clientUuid
            FROM fulfillment_candidates f
            WHERE f.syncDirty = 1 AND NOT EXISTS (
                SELECT 1 FROM causal_transport_journal j
                WHERE j.journalKey = 'terminal-receipt:fulfillment_candidate:' || f.clientUuid
                  AND j.contentEpoch = f.updatedAt
            )
        )
        """,
    )
    fun observeCount(): Flow<Int>
}
