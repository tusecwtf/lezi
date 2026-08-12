package com.lezi.babylog.core.database.causal

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * First-class WakeObservation atomic root (wire §4.5).
 *
 * Stable UUID, Sleep Record reference, actual wake time, observer membership,
 * note, withdrawn, revision/pending/base, and 0–3 wake photo relations via
 * [com.lezi.babylog.core.database.MediaAssetEntity] kind=`wake`.
 */
@Entity(
    tableName = "wake_observations",
    indices = [
        Index(value = ["clientUuid"], unique = true),
        Index("sleepRecordClientUuid"),
        Index("updatedAt"),
        Index("syncDirty"),
        Index("openConflictId"),
    ],
)
data class WakeObservationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clientUuid: String,
    val sleepRecordClientUuid: String,
    val wakeTimestamp: Long,
    @ColumnInfo(defaultValue = "''")
    val observerMembershipId: String = "",
    val note: String? = null,
    @ColumnInfo(defaultValue = "0")
    val withdrawn: Boolean = false,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    @ColumnInfo(defaultValue = "1")
    val syncDirty: Boolean = true,
    @ColumnInfo(defaultValue = "NULL")
    val familyPublishedUpdatedAt: Long? = null,
    @ColumnInfo(defaultValue = "NULL")
    val baseVersion: String? = null,
    @ColumnInfo(defaultValue = "NULL")
    val mutationId: String? = null,
    @ColumnInfo(defaultValue = "NULL")
    val openConflictId: String? = null,
    @ColumnInfo(defaultValue = "NULL")
    val localBranchVersionId: String? = null,
)

/**
 * Offline-capable conflict summary for badge/list (pull-bounded; not a second stable fact).
 */
@Entity(
    tableName = "conflict_summaries",
    indices = [
        Index("entityType", "clientUuid"),
        Index("status"),
    ],
)
data class ConflictSummaryEntity(
    @PrimaryKey val conflictId: String,
    val entityType: String,
    val clientUuid: String,
    val baseVersionId: String? = null,
    val stableVersionId: String,
    /** open | resolved */
    val status: String,
    /** concurrent | tombstone_restore */
    val kind: String,
    /** JSON array of branch_version_id strings for list affordances. */
    @ColumnInfo(defaultValue = "'[]'")
    val branchVersionIdsJson: String = "[]",
    val updatedAt: Long,
)

/** One bounded Room query owns the observable cross-root conflict inbox projection. */
data class ConflictInboxProjectionRow(
    val conflictId: String,
    val entityType: String,
    val clientUuid: String,
    val updatedAt: Long,
    val localTitle: String?,
    val babyLabel: String?,
    val localActorId: String?,
    val localTombstone: Boolean?,
    val localMediaCount: Int,
    val snapshotJson: String?,
)

/**
 * Conflict snapshot cache stored in the existing Room 27 table.
 *
 * A canonical complete snapshot uses its wire conflict UUID as [conflictId].
 * H08 resumable page evidence, pre-H27 frozen mutation/media envelopes, and the
 * single in-flight replica-reset receipt use closed private non-UUID keys and
 * are never joined into the inbox projection. The reset receipt is replaced in
 * the same Room transaction that requeues roots and is deleted after recovery;
 * H27 owns moving these transport journals to dedicated Room 28 storage.
 */
@Entity(tableName = "conflict_detail_cache")
data class ConflictSnapshotCacheEntity(
    @PrimaryKey val conflictId: String,
    /** Fixed sentinel: the canonical snapshot lives only in [snapshotJson]. */
    @ColumnInfo(name = "stableRootJson")
    val legacyStableRootSentinel: String = "{}",
    @ColumnInfo(name = "baseRootJson")
    val legacyBaseRootSentinel: String? = null,
    /** Canonical snapshot or one of the closed private transport journals documented above. */
    @ColumnInfo(name = "branchesJson")
    val snapshotJson: String,
    /** Fixed sentinel; paging state never revives this lossy legacy column. */
    @ColumnInfo(name = "conflictPathsJson")
    val legacyConflictPathsSentinel: String = "[]",
    val cachedAt: Long,
)

/**
 * Soft client-side suspected-duplicate group (heuristic). Independent of syncDirty /
 * Record deletedAt / ordinary media tombstones.
 */
@Entity(
    tableName = "suspected_duplicate_groups",
    indices = [
        Index("status"),
        Index("babyClientUuid", "recordType"),
    ],
)
data class SuspectedDuplicateGroupEntity(
    @PrimaryKey val groupId: String,
    val babyClientUuid: String,
    val recordType: String,
    /** Sorted JSON array of member record client UUIDs. */
    val memberClientUuidsJson: String,
    /** open | resolved | dismissed */
    val status: String,
    val updatedAt: Long,
)

/**
 * Source relation after explicit author declare or Owner group resolve.
 * Sources stay live entities; this is not a record tombstone.
 */
@Entity(
    tableName = "source_relations",
    indices = [
        Index("displayClientUuid"),
    ],
)
data class SourceRelationEntity(
    @PrimaryKey val relationId: String,
    val displayClientUuid: String,
    /** Permanent media retention invariant; always true for new rows. */
    @ColumnInfo(defaultValue = "1")
    val mediaRetained: Boolean = true,
    /** See [SourceRelationReason]. */
    val reason: String,
    val mutationId: String,
    val createdByMembershipId: String,
    val createdAt: Long,
)

@Entity(
    tableName = "source_relation_members",
    primaryKeys = ["relationId", "recordClientUuid"],
    indices = [Index("recordClientUuid")],
)
data class SourceRelationMemberEntity(
    val relationId: String,
    val recordClientUuid: String,
    /** See [SourceRelationRole]. */
    val role: String,
)

@Entity(
    tableName = "source_relation_declarations",
    indices = [
        Index("recordClientUuid"),
        Index("status"),
    ],
)
data class SourceRelationDeclarationEntity(
    @PrimaryKey val mutationId: String,
    val recordClientUuid: String,
    val equivalentToClientUuid: String,
    val expectedRecordVersion: String,
    val expectedOtherVersion: String,
    val authorMembershipId: String,
    /** See [SourceRelationDeclarationStatus]. */
    val status: String,
    val createdAt: Long,
)

/** Wire/Room vocabulary for source-relation reason (client + server author_declare/owner_group_resolve). */
object SourceRelationReason {
    const val AUTHOR_DECLARE = "author_declare"
    const val OWNER_GROUP_RESOLVE = "owner_group_resolve"
    /** Local pull attach only — never invents Owner resolution provenance. */
    const val PULL_SUMMARY = "pull_summary"
}

object SourceRelationRole {
    const val DISPLAY = "display"
    const val SOURCE = "source"
}

object SourceRelationDeclarationStatus {
    const val PENDING = "pending"
    const val CONSUMED = "consumed"
    const val SUPERSEDED = "superseded"
    const val FAILED = "failed"
}

/**
 * Media reference holders that may keep bytes alive independently of a single
 * live MediaAsset ownership pointer.
 *
 * holderKind: stable_root | local_mutation | conflict_branch | duplicate_source
 */
@Entity(
    tableName = "media_references",
    primaryKeys = ["mediaUuid", "holderKind", "holderId"],
    indices = [
        Index("localUri"),
        Index("holderKind", "holderId"),
    ],
)
data class MediaReferenceEntity(
    val mediaUuid: String,
    val holderKind: String,
    val holderId: String,
    val localUri: String? = null,
    val remoteUri: String? = null,
    val createdAt: Long,
)

/** Well-known media reference holder kinds (ticket 04). */
object MediaReferenceHolderKind {
    const val STABLE_ROOT = "stable_root"
    const val LOCAL_MUTATION = "local_mutation"
    const val CONFLICT_BRANCH = "conflict_branch"
    const val DUPLICATE_SOURCE = "duplicate_source"
}
