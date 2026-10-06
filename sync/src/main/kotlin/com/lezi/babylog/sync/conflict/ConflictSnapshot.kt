package com.lezi.babylog.sync.conflict

import com.lezi.babylog.sync.backend.CausalMediaItem
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

enum class ConflictRootType(val wireName: String, val mediaRole: String?, val mediaLimit: Int) {
    Baby("baby", "avatar", 1),
    Record("record", "log", 3),
    CarePlan("care_plan", "plan", 3),
    CustomItem("custom_item", null, 0),
    WakeObservation("wake_observation", "wake", 3),
    ;

    companion object {
        fun fromWire(value: String): ConflictRootType =
            entries.singleOrNull { it.wireName == value }
                ?: throw IllegalArgumentException("entity_type 无效: $value")
    }
}

/**
 * Typed canonical roots consumed by Room/domain code. The original closed JSON is
 * retained so future field-label presentation does not need to parse transport bytes.
 */
sealed interface ConflictRoot {
    val canonical: JsonObject
    val updatedAt: Long

    data class Baby(
        val nickname: String,
        val sex: String?,
        val birthday: String?,
        val birthWeightGrams: Int? = null,
        val avatarMediaUuid: String?,
        val createdByMembershipId: String,
        override val updatedAt: Long,
        override val canonical: JsonObject,
    ) : ConflictRoot

    data class Record(
        val babyClientUuid: String,
        val type: String,
        val customItemClientUuid: String?,
        val timestamp: Long,
        val endTimestamp: Long?,
        val note: String?,
        val payload: JsonObject,
        val schemaVersion: Int,
        val effectiveWakeObservationClientUuid: String?,
        val createdByMembershipId: String,
        override val updatedAt: Long,
        override val canonical: JsonObject,
    ) : ConflictRoot

    data class CarePlan(
        val babyClientUuid: String,
        val type: String,
        val scheduledAt: Long,
        val scheduledZoneId: String,
        val note: String?,
        val payload: JsonObject,
        val schemaVersion: Int,
        val status: String,
        val fulfilledRecordClientUuid: String?,
        val fulfilledAt: Long?,
        val sourceRecordClientUuid: String?,
        val customItemClientUuid: String?,
        val createdByMembershipId: String,
        override val updatedAt: Long,
        override val canonical: JsonObject,
    ) : ConflictRoot

    data class CustomItem(
        val name: String,
        val iconSlot: Int,
        val createdByMembershipId: String,
        override val updatedAt: Long,
        override val canonical: JsonObject,
    ) : ConflictRoot

    data class WakeObservation(
        val sleepRecordClientUuid: String,
        val wakeTimestamp: Long,
        val note: String?,
        val withdrawn: Boolean,
        val observerMembershipId: String,
        override val updatedAt: Long,
        override val canonical: JsonObject,
    ) : ConflictRoot
}

data class ConflictVersionSnapshot(
    val versionId: String,
    val baseVersion: String?,
    val root: ConflictRoot,
    val media: List<CausalMediaItem>,
    val deleted: Boolean,
    val mutationId: String,
    val actorId: String,
    val deviceId: String,
    val receivedAt: Long,
)

sealed interface ConflictOutcome {
    data class Set(val value: JsonElement) : ConflictOutcome
    data object Remove : ConflictOutcome
}

data class ConflictSource(
    val versionId: String,
    val mutationId: String,
    val actorId: String,
    val deviceId: String,
    val receivedAt: Long,
)

data class ConflictCandidate(
    val choiceId: String,
    val outcome: ConflictOutcome,
    val sources: List<ConflictSource>,
)

data class ConflictingPath(
    val path: String,
    val candidates: List<ConflictCandidate>,
)

data class AutoMergedPath(
    val path: String,
    val outcome: ConflictOutcome,
    val sources: List<ConflictSource>,
)

/**
 * One lossless server page. [ConflictSnapshotProjection] is the only module that
 * may assemble pages into the canonical complete page-0 view used by callers.
 */
data class ConflictSnapshot(
    val conflictId: String,
    val entityType: ConflictRootType,
    val clientUuid: String,
    val snapshotToken: String,
    val expiresAt: Long,
    val stable: ConflictVersionSnapshot,
    val branches: List<ConflictVersionSnapshot>,
    val conflicting: List<ConflictingPath>,
    val autoMerged: List<AutoMergedPath>,
    val pageIndex: Int,
    val continuation: String?,
    val complete: Boolean,
) {
    val branchVersionIds: List<String> get() = branches.map { it.versionId }
}
