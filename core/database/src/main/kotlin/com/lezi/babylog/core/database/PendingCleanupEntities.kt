package com.lezi.babylog.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction

/**
 * Durable hand-off from the Room clear transaction to Android side-effect cleanup.
 *
 * One row per local-clear scope survives process death until settings (including
 * nursing timer epoch), app care-plan alarms, nursing timer FGS, and captured
 * Android system-calendar events have all been cleaned.
 * This storage entity is private to [RoomPendingReminderCleanupStore]; callers
 * use typed snapshots.
 */
@Entity(tableName = "pending_reminder_cleanup")
data class PendingReminderCleanupEntity(
    @PrimaryKey val operation: String,
    @androidx.room.ColumnInfo(defaultValue = "''")
    val carePlanIds: String = "",
    @androidx.room.ColumnInfo(defaultValue = "'{}'")
    val systemCalendarProjectionsJson: String = "{}",
    @androidx.room.ColumnInfo(defaultValue = "NULL")
    val currentBabyId: Long? = null,
    /**
     * Unused residual column from the retired independent next-feed alarm.
     * Always written null; kept only to preserve Room schema v24.
     */
    @androidx.room.ColumnInfo(defaultValue = "NULL")
    val nextFeedAt: Long? = null,
    /**
     * Nursing timer clear epoch (JSON). Empty string means no timer was captured.
     * Reuses the retired next-feed epoch column so Room schema v24 stays fixed.
     */
    @androidx.room.ColumnInfo(defaultValue = "''")
    val nextFeedEpoch: String = "",
    val familyServerRetained: Boolean,
)

@Dao
interface PendingReminderCleanupDao {
    @Query("SELECT * FROM pending_reminder_cleanup WHERE operation = :operation LIMIT 1")
    suspend fun get(operation: String): PendingReminderCleanupEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(pending: PendingReminderCleanupEntity)

    @Query("DELETE FROM pending_reminder_cleanup WHERE operation = :operation")
    suspend fun delete(operation: String)

    /** Read-merge-write of one scope. Room runs the body in a single transaction. */
    @Transaction
    suspend fun mergeUpsert(
        operation: String,
        merged: (PendingReminderCleanupEntity?) -> PendingReminderCleanupEntity,
    ) {
        upsert(merged(get(operation)))
    }
}

/**
 * Durable write-ahead marker for post-commit local replica cleanup.
 *
 * The marker is inserted in the same Room transaction as the domain clear and
 * deleted only after file, DataStore, and media cleanup succeeds.
 */
@Entity(tableName = "pending_replica_cleanup")
data class PendingReplicaCleanupEntity(
    @PrimaryKey val operation: String,
    val scope: String,
    val familyId: String,
    val pullGeneration: String,
    val mediaClientUuidsJson: String,
    val localMediaPathsJson: String,
)

@Dao
interface PendingReplicaCleanupDao {
    @Query("SELECT * FROM pending_replica_cleanup WHERE operation = :operation LIMIT 1")
    suspend fun get(operation: String): PendingReplicaCleanupEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(pending: PendingReplicaCleanupEntity)

    @Query("DELETE FROM pending_replica_cleanup WHERE operation = :operation")
    suspend fun delete(operation: String)
}
