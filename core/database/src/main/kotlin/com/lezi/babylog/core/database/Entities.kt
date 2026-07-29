package com.lezi.babylog.core.database

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.Index
import androidx.room.PrimaryKey
import com.lezi.babylog.core.model.CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION

@Entity(tableName = "local_users")
data class LocalUserEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String? = null,
    val deviceId: String,
    val createdAt: Long,
)

@Entity(tableName = "families")
data class FamilyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ownerUserId: Long,
    val createdAt: Long,
)

@Entity(
    tableName = "memberships",
    primaryKeys = ["familyId", "userId"],
)
data class MembershipEntity(
    val familyId: Long,
    val userId: Long,
    val role: String,
    val status: String,
    val joinedAt: Long,
)

@Entity(
    tableName = "babies",
    indices = [
        Index("familyId"),
        Index(value = ["clientUuid"], unique = true),
        Index("updatedAt"),
        Index("syncDirty"),
    ],
)
data class BabyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val familyId: Long,
    val nickname: String,
    val sex: String? = null,
    val birthdayEpochDay: Long,
    /** Birth weight in grams; null when not set. */
    val birthWeightGrams: Int? = null,
    val themeColorArgb: Int,
    val sortOrder: Int = 0,
    val clientUuid: String,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    @ColumnInfo(defaultValue = "1")
    val syncDirty: Boolean = true,
    /** Portable canonical avatar pointer; [avatarPath] remains device-local. */
    val avatarMediaUuid: String? = null,
    /** App-private relative path. Never sync this device-local value. */
    val avatarPath: String? = null,
    /** Local-only: this Baby identity was applied from the joined family authority. */
    @ColumnInfo(defaultValue = "0")
    val familyAuthority: Boolean = false,
)

@Entity(
    tableName = "records",
    indices = [
        Index("babyId", "timestamp"),
        Index(value = ["clientUuid"], unique = true),
        Index("babyId", "type", "timestamp"),
        Index("updatedAt"),
        Index("syncDirty"),
    ],
)
data class RecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clientUuid: String,
    val babyId: Long,
    val type: String,
    val timestamp: Long,
    val endTimestamp: Long? = null,
    val note: String? = null,
    val payloadJson: String = "{}",
    val schemaVersion: Int = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    @ColumnInfo(defaultValue = "1")
    val syncDirty: Boolean = true,
    @ColumnInfo(defaultValue = "''")
    val createdByMembershipId: String = "",
    /** Last root revision atomically committed by the family server; never a media receipt. */
    @ColumnInfo(defaultValue = "NULL")
    val familyPublishedUpdatedAt: Long? = null,
)

/**
 * Local care plan entity. Family packages publish via atomic care_plan bundles
 * (plan metadata + 0–3 plan photos), same contract as record packages.
 */
@Entity(
    tableName = "care_plans",
    indices = [
        Index(value = ["clientUuid"], unique = true),
        Index("babyId", "scheduledAt"),
        Index("status"),
        Index("updatedAt"),
        Index("syncDirty"),
    ],
)
data class CarePlanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clientUuid: String,
    val babyId: Long,
    val type: String,
    val customItemId: Long? = null,
    val scheduledAt: Long,
    val scheduledZoneId: String,
    val note: String? = null,
    val payloadJson: String = "{}",
    val schemaVersion: Int = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
    /** Storage: pending | missed | completed | skipped */
    val status: String = "pending",
    val createdByMembershipId: String = "",
    val fulfilledRecordClientUuid: String? = null,
    val fulfilledAt: Long? = null,
    /**
     * When this plan was created by converting a fact Record, the soft-deleted
     * record's [RecordEntity.clientUuid] for later family-sync provenance/idempotency.
     */
    val sourceRecordClientUuid: String? = null,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    /**
     * True while this device still needs to publish the atomic plan package.
     * Creator keeps full local plan + projection; receivers stay invisible until commit.
     */
    @ColumnInfo(defaultValue = "1")
    val syncDirty: Boolean = true,
    /** Device-local desired reminder route; deliberately excluded from family wire payloads. */
    @ColumnInfo(defaultValue = "1")
    val systemCalendarProjectionEnabled: Boolean = true,
    /** Device-local provider identity; stable UID lookup repairs a missing value after crashes. */
    @ColumnInfo(defaultValue = "NULL")
    val systemCalendarEventId: String? = null,
    /** Last confirmed ownership state for the provider begin-time reminder. */
    @ColumnInfo(defaultValue = "0")
    val systemCalendarReminderReady: Boolean = false,
    /** Durable hand-off set before external provider I/O; closes insert-before-id crashes. */
    @ColumnInfo(defaultValue = "0")
    val systemCalendarProjectionPending: Boolean = false,
    /** Last root revision atomically committed by the family server; never a media receipt. */
    @ColumnInfo(defaultValue = "NULL")
    val familyPublishedUpdatedAt: Long? = null,
)

/**
 * Local durable fulfillment attempt for family sync.
 *
 * One fulfill creates a stable [clientUuid] linked to the completed care plan and
 * the fact [recordClientUuid]. Multiple candidates per plan are allowed;
 * [FulfillmentAuthority] picks one winner and marks losers via [adoptionStatus]
 * without deleting Record/photos.
 */
@Entity(
    tableName = "fulfillment_candidates",
    indices = [
        Index(value = ["clientUuid"], unique = true),
        Index("carePlanClientUuid"),
        Index("recordClientUuid"),
        Index("adoptionStatus"),
        Index("syncDirty"),
        Index("updatedAt"),
    ],
)
data class FulfillmentCandidateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clientUuid: String,
    val carePlanClientUuid: String,
    val recordClientUuid: String,
    /** Actual care time from the linked Record (not used for conflict adjudication). */
    val actualTimestamp: Long? = null,
    /**
     * Immutable confirm instant for this local attempt. Server re-stamps
     * authoritative [confirmedAt] / submitter evidence on first push; after pull
     * we store the server values for winner selection.
     */
    val confirmedAt: Long,
    /**
     * Submitter membership for multi-candidate authority. Local offline trail is
     * filled from the joined session on fulfill; server freeze overwrites on first
     * accept and subsequent pulls must merge those stamps.
     */
    @ColumnInfo(defaultValue = "''")
    val submitterMembershipId: String = "",
    /**
     * Submitter role evidence (`owner` / `member`). Local offline trail from the
     * joined session; server freeze is authoritative after pull.
     */
    @ColumnInfo(defaultValue = "''")
    val submitterRole: String = "",
    /**
     * Device-local adoption mark after [FulfillmentAuthority] resolution.
     * Empty until resolved; then `adopted` or `conflict_not_adopted`. Not synced.
     */
    @ColumnInfo(defaultValue = "''")
    val adoptionStatus: String = "",
    /**
     * Device-local pointer to the independent Record created by admin
     * “转为独立记录”. Empty until convert succeeds. Not a family wire field —
     * keeps convert idempotent without flipping [adoptionStatus] or plan authority.
     */
    @ColumnInfo(defaultValue = "''")
    val convertedRecordClientUuid: String = "",
    val updatedAt: Long,
    val deletedAt: Long? = null,
    @ColumnInfo(defaultValue = "1")
    val syncDirty: Boolean = true,
)

@Entity(
    tableName = "media_assets",
    indices = [
        Index(value = ["clientUuid"], unique = true),
        Index("recordId"),
        Index("carePlanId"),
        Index("babyId", "kind", "deletedAt"),
        Index("updatedAt"),
        Index("syncDirty"),
    ],
)
data class MediaAssetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordId: Long? = null,
    /** Owner care plan when this is a plan photo (mutually exclusive with log [recordId]). */
    val carePlanId: Long? = null,
    @ColumnInfo(defaultValue = "''")
    val clientUuid: String = "",
    @ColumnInfo(defaultValue = "'log'")
    val kind: String = "log",
    val babyId: Long? = null,
    val localUri: String,
    val remoteUri: String? = null,
    val mime: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    @ColumnInfo(defaultValue = "0")
    val byteSize: Long = 0,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "0")
    val updatedAt: Long = createdAt,
    val deletedAt: Long? = null,
    @ColumnInfo(defaultValue = "1")
    val syncDirty: Boolean = true,
) {
    init {
        when (kind) {
            "log" -> {
                require((recordId != null) xor (carePlanId != null)) {
                    "log media must belong to exactly one record or care plan"
                }
                require(babyId == null) { "log media must not belong directly to a baby" }
            }
            "avatar" -> {
                require(babyId != null) { "avatar media must belong to a baby" }
                require(recordId == null && carePlanId == null) {
                    "avatar media must not belong to a record or care plan"
                }
            }
            else -> throw IllegalArgumentException("unsupported media kind: $kind")
        }
    }
}
