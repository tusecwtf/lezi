package com.lezi.gf.care

import com.lezi.gf.kernel.ClientUuid
import kotlinx.serialization.Serializable

@Serializable
data class PhotoRef(
    val mediaUuid: String,
    val localPath: String,
    val byteSize: Long = 0,
    val isDraftOwned: Boolean = true,
)

@Serializable
data class CareRecord(
    val clientUuid: String,
    val babyClientUuid: String,
    val typeKey: String,
    val timestampMs: Long,
    val note: String = "",
    val payloadJson: String = "{}",
    val photos: List<PhotoRef> = emptyList(),
    val createdByMembershipId: String? = null,
    val deletedAtMs: Long? = null,
    val updatedAtMs: Long = 0,
    val customDefUuid: String? = null,
    val linkedPlanUuid: String? = null,
    /** Non-adopted conflict fulfill — excluded from normal timeline/export. */
    val isNonAdoptedFulfill: Boolean = false,
)

@Serializable
enum class PlanStatus {
    PENDING,
    MISSED,
    COMPLETED,
    SKIPPED,
}

@Serializable
data class CarePlan(
    val clientUuid: String,
    val babyClientUuid: String,
    val typeKey: String,
    val scheduledAtMs: Long,
    val scheduledZoneId: String = "Asia/Shanghai",
    val note: String = "",
    val payloadJson: String = "{}",
    val photos: List<PhotoRef> = emptyList(),
    val status: PlanStatus = PlanStatus.PENDING,
    val linkedRecordUuid: String? = null,
    val confirmedAtMs: Long? = null,
    val isNextFeedMarker: Boolean = false,
    val createdByMembershipId: String? = null,
    val deletedAtMs: Long? = null,
    val updatedAtMs: Long = 0,
)

@Serializable
data class CustomItemDef(
    val clientUuid: String,
    val title: String,
    val iconKey: String = "custom",
    val deletedAtMs: Long? = null,
    val updatedAtMs: Long = 0,
)

/**
 * Fresh-install four-slot dock (PRD ui.md §2.2): 尿尿 · 睡眠 · 母乳 · 配方奶.
 * Absolute L→R; empty slots stay empty; layout editor may reorder.
 */
val DEFAULT_DOCK_SLOTS: List<String?> = listOf("pee", "sleep", "nursing", "formula")

@Serializable
data class LayoutSnapshot(
    val dockSlots: List<String?> = DEFAULT_DOCK_SLOTS,
    val hiddenTypeKeys: Set<String> = emptySet(),
)

@Serializable
data class NursingTimerState(
    val babyClientUuid: String,
    val leftMs: Long = 0,
    val rightMs: Long = 0,
    val activeSide: String? = null, // "L" | "R" | null
    val startedAtMs: Long? = null,
    val frozen: Boolean = false,
)

@Serializable
data class DaySummary(
    val dayStartMs: Long,
    val milkMl: Int = 0,
    val nursingCount: Int = 0,
    val sleepMinutes: Int = 0,
    val peeCount: Int = 0,
    val poopCount: Int = 0,
)

/** Draft composer — open does not persist. */
data class ComposerDraft(
    val type: RecordType,
    val babyClientUuid: String,
    val timestampMs: Long,
    val note: String = "",
    val payloadJson: String = "{}",
    val photos: List<PhotoRef> = emptyList(),
    val customDefUuid: String? = null,
    val dirty: Boolean = false,
)

fun ClientUuid.asString(): String = value
