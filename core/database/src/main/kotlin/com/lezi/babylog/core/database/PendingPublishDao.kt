package com.lezi.babylog.core.database

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Projects publication work directly from the authoritative Room replicas. */
@Dao
interface PendingPublishDao {
    @Query(
        """
        SELECT
            (SELECT COUNT(*) FROM babies WHERE syncDirty = 1) +
            (SELECT COUNT(*) FROM records WHERE syncDirty = 1) +
            (SELECT COUNT(*) FROM care_plans WHERE syncDirty = 1) +
            (SELECT COUNT(*) FROM fulfillment_candidates WHERE syncDirty = 1) +
            (SELECT COUNT(*) FROM media_assets WHERE syncDirty = 1) +
            (SELECT COUNT(*) FROM custom_items WHERE syncDirty = 1)
        """,
    )
    fun observeCount(): Flow<Int>
}
