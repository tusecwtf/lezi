package com.lezi.babylog.core.model

/** Domain-facing baby profile without Android or Room types. */

data class Baby(
    val id: Long = 0,
    val familyId: Long,
    val nickname: String,
    val sex: Sex? = null,
    val birthdayEpochDay: Long,
    /** Birth weight in grams; null when not set. */
    val birthWeightGrams: Int? = null,
    /** App-private relative path for the cropped square avatar. */
    val avatarPath: String? = null,
    val themeColorArgb: Int,
    val sortOrder: Int = 0,
    val clientUuid: String,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

enum class Sex { MALE, FEMALE, UNKNOWN }

/** Parse UI/wire/legacy enum labels into [Sex]. */
fun parseBabySex(raw: String): Sex =
    runCatching { Sex.valueOf(raw) }.getOrNull()
        ?: when (raw.trim().lowercase()) {
            "male", "m", "男", "男宝" -> Sex.MALE
            "female", "f", "女", "女宝" -> Sex.FEMALE
            else -> Sex.UNKNOWN
        }

/** Canonical Room / wire storage: `female` / `male` / null. */
fun normalizeBabySex(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    return when (parseBabySex(raw)) {
        Sex.FEMALE -> "female"
        Sex.MALE -> "male"
        Sex.UNKNOWN -> null
    }
}

data class Family(
    val id: Long = 0,
    val ownerUserId: Long,
    val createdAt: Long,
)

data class LocalUser(
    val id: Long = 0,
    val displayName: String? = null,
    val deviceId: String,
    val createdAt: Long,
)

enum class MemberRole { OWNER, MEMBER }
enum class MemberStatus { ACTIVE, REVOKED }

data class Membership(
    val familyId: Long,
    val userId: Long,
    val role: MemberRole,
    val status: MemberStatus,
    val joinedAt: Long,
)

/**
 * Device-local evidence for whether a family has atomically received a root revision.
 * Media upload receipts are deliberately excluded from this state.
 */
enum class RootPublicationState {
    NEVER_PUBLISHED,
    PREVIOUS_VERSION_PUBLISHED,
    CURRENT_VERSION_PUBLISHED,
}

fun rootPublicationState(
    localUpdatedAt: Long,
    familyPublishedUpdatedAt: Long?,
): RootPublicationState = when {
    familyPublishedUpdatedAt != null &&
        familyPublishedUpdatedAt > 0L &&
        familyPublishedUpdatedAt == localUpdatedAt -> RootPublicationState.CURRENT_VERSION_PUBLISHED
    familyPublishedUpdatedAt != null &&
        familyPublishedUpdatedAt > 0L &&
        familyPublishedUpdatedAt < localUpdatedAt -> RootPublicationState.PREVIOUS_VERSION_PUBLISHED
    else -> RootPublicationState.NEVER_PUBLISHED
}

data class Record(
    val id: Long = 0,
    val clientUuid: String,
    val babyId: Long,
    val type: RecordType,
    val timestamp: Long,
    val endTimestamp: Long? = null,
    val note: String? = null,
    val payloadJson: String = "{}",
    val schemaVersion: Int = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    /**
     * True while this device still needs to publish the atomic record package.
     * Creator-local complete visibility uses this for amber “仅本机” chrome.
     */
    val syncDirty: Boolean = false,
    /** Server-minted membership that first created this record; empty before joining a family. */
    val createdByMembershipId: String = "",
    /** Device-local receipt for the last atomically published root revision. */
    val familyPublishedUpdatedAt: Long? = null,
) {
    val payload: RecordPayloadDocument
        get() = RecordPayloadCodec.decode(type, payloadJson, schemaVersion)
}

/**
 * Domain view of a media attachment (record photo, plan photo, or avatar).
 * Ownership is XOR for log kind: [recordId] or [carePlanId]; avatars use [babyId].
 */
data class MediaAsset(
    val id: Long = 0,
    val recordId: Long? = null,
    val carePlanId: Long? = null,
    val babyId: Long? = null,
    val clientUuid: String = "",
    /** "log" | "avatar" */
    val kind: String = "log",
    val localUri: String,
    val remoteUri: String? = null,
    val mime: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val byteSize: Long = 0,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    val deletedAt: Long? = null,
    val syncDirty: Boolean = true,
)

/**
 * Local care plan (future intent), separate from fact [Record].
 * Status [MISSED] may be derived when now > scheduledAt and still pending.
 */
enum class CarePlanStatus {
    PENDING,
    MISSED,
    COMPLETED,
    SKIPPED,
    ;

    val storageKey: String
        get() = name.lowercase()

    companion object {
        fun fromStorage(raw: String): CarePlanStatus =
            entries.firstOrNull { it.storageKey == raw.lowercase() || it.name == raw.uppercase() }
                ?: PENDING
    }
}

data class CarePlan(
    val id: Long = 0,
    val clientUuid: String,
    val babyId: Long,
    val type: RecordType,
    /** Local custom item row id when [type] is CUSTOM; null for built-ins. */
    val customItemId: Long? = null,
    val scheduledAt: Long,
    val scheduledZoneId: String,
    val note: String? = null,
    val payloadJson: String = "{}",
    val schemaVersion: Int = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
    val status: CarePlanStatus = CarePlanStatus.PENDING,
    val createdByMembershipId: String = "",
    val fulfilledRecordClientUuid: String? = null,
    val fulfilledAt: Long? = null,
    /**
     * Provenance when this plan was converted from a fact Record (soft-deleted).
     * Used for family-sync identity; null for plans created directly.
     */
    val sourceRecordClientUuid: String? = null,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    /**
     * True while this device still needs to publish the atomic plan package.
     * Creator-local complete visibility uses this for amber “仅本机” chrome;
     * receivers never see incomplete packages.
     */
    val syncDirty: Boolean = false,
    /** Device-local, per-plan desired calendar route; never family-synced. */
    val systemCalendarProjectionEnabled: Boolean = true,
    /** Device-local receipt for the last atomically published root revision. */
    val familyPublishedUpdatedAt: Long? = null,
) {
    /** Effective status for UI: pending past scheduledAt becomes missed. */
    fun effectiveStatus(nowMillis: Long = System.currentTimeMillis()): CarePlanStatus {
        if (status != CarePlanStatus.PENDING) return status
        return if (scheduledAt < nowMillis) CarePlanStatus.MISSED else CarePlanStatus.PENDING
    }
}

/**
 * Family-shared fulfillment attempt. Stable [clientUuid] + immutable [confirmedAt]
 * link a completed care plan to its fact [recordClientUuid]. Multi-candidate sets
 * are adjudicated by [FulfillmentAuthority]; [adoptionStatus] is device-local.
 */
data class FulfillmentCandidate(
    val id: Long = 0,
    val clientUuid: String,
    val carePlanClientUuid: String,
    val recordClientUuid: String,
    val actualTimestamp: Long? = null,
    val confirmedAt: Long,
    val submitterMembershipId: String = "",
    val submitterRole: String = "",
    /**
     * Local resolution mark: [FulfillmentAdoptionStatus.ADOPTED],
     * [FulfillmentAdoptionStatus.CONFLICT_NOT_ADOPTED], or empty before resolve.
     * Not a family wire field — each device re-derives from frozen evidence.
     */
    val adoptionStatus: String = "",
    /**
     * Local convert pointer: clientUuid of the independent ordinary Record created
     * from this conflict-not-adopted candidate. Empty until admin convert.
     * Not a family wire field.
     */
    val convertedRecordClientUuid: String = "",
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val syncDirty: Boolean = false,
) {
    val isConflictNotAdopted: Boolean
        get() = adoptionStatus == FulfillmentAdoptionStatus.CONFLICT_NOT_ADOPTED

    val hasConvertedIndependentRecord: Boolean
        get() = convertedRecordClientUuid.isNotBlank()
}

/**
 * Admin-facing audit projection of one conflict-not-adopted fulfillment.
 * Built only when the actor is family admin; never for ordinary timeline surfaces.
 */
data class ConflictNotAdoptedAudit(
    val candidateClientUuid: String,
    val carePlanClientUuid: String,
    val carePlanId: Long,
    val babyId: Long,
    val type: RecordType,
    /** Display label for the record/plan type (built-in or custom snapshot). */
    val typeLabel: String,
    val note: String?,
    val actualTimestamp: Long?,
    val confirmedAt: Long,
    val submitterMembershipId: String,
    val submitterRole: String,
    /** Best-effort 家庭称呼; falls back to membership id / role label. */
    val submitterDisplayName: String,
    /** Human-readable why this candidate lost authority. */
    val notAdoptedReason: String,
    val photoLocalPaths: List<String> = emptyList(),
    /** Loser fulfill record (audit source); still excluded from ordinary surfaces. */
    val sourceRecordClientUuid: String,
    val sourceRecordId: Long?,
    /**
     * Independent ordinary Record created by convert, if any.
     * Empty means not yet converted on this device.
     */
    val convertedRecordClientUuid: String = "",
    val convertedRecordId: Long? = null,
) {
    val isConverted: Boolean get() = convertedRecordClientUuid.isNotBlank()
}

data class SettingsLocal(
    /** Version of the complete device-local layout epoch represented by the fields below. */
    val deviceLayoutSnapshotVersion: Int = DEVICE_LAYOUT_SNAPSHOT_VERSION,
    val itemOrderJson: String = "[]",
    /**
     * Device-local order of record category sections (feeding/excretion/…).
     * JSON string array of storage keys; empty means default enum order.
     * Not shared with family.
     */
    val categoryOrderJson: String = "[]",
    val hiddenItems: Set<String> = emptySet(),
    /**
     * Four device-local home quick-slot catalog keys ([RecordItemIdentity.catalogKey]).
     * Empty string = intentionally empty slot. Defaults: pee / sleep / nursing / formula.
     * Not shared with family; independent of widget [quickTypes].
     */
    val quickRecordSlots: List<String> = DEFAULT_QUICK_RECORD_SLOTS,
    val timerEnabled: Boolean = true,
    val recordAtStartOrEnd: String = "end",
    val nursingIntervalMin: Int = 180,
    val nextFeedAt: Long? = null,
    /** Device-local identity of the write that owns [nextFeedAt]. */
    val nextFeedEpoch: String = "",
    val darkMode: String = "system",
    /** "warm" = card template; "journal" = compact logbook template. */
    val visualStyle: String = "warm",
    /** Preferred thumb side for the shared one-handed action dock: "left" or "right". */
    val preferredHand: String = "right",
    val dayCountMode: String = "full",
    val weekStart: Int = 1,
    val unitsJson: String = "{}",
    val amountStepMl: Int = 5,
    val timeStepMin: Int = 1,
    /**
     * Time editor style in record sheets:
     * - "dropdown": 24-hour hour/minute menus
     * - "dial": Material clock dial with 上午/下午 selection
     */
    val timePickerStyle: String = "dropdown",
    val infantFeverAdviceEnabled: Boolean = true,
    val curveDataset: String = "default",
    val timelineOrder: String = "newest_first",
    /**
     * Device-local default-on toggle for family care-plan AlarmManager reminders.
     * Not shared with the family NAS.
     */
    val carePlanLocalRemindersEnabled: Boolean = true,
    /**
     * Device-local system-calendar projection. Never family-synced.
     * [systemCalendarEnabled] is true only after the user completed permission
     * + writable calendar pick; [systemCalendarId] is the chosen account calendar.
     */
    val systemCalendarEnabled: Boolean = false,
    val systemCalendarId: String? = null,
    /**
     * Disclosure level for projected events: 1 = 仅乐记事件, 2 = 宝宝昵称·类型 (default),
     * 3 = + 备注/照片数与深链. Device-local only; never family-synced.
     */
    val systemCalendarDisclosureLevel: Int = 2,
    /**
     * JSON object map of carePlanClientUuid → platform event id.
     * Device-local only; not shared with family.
     */
    val systemCalendarEventMapJson: String = "{}",
    /**
     * Monotonic device-local receipt that the first layout drag guidance was completed.
     * Kept outside [DeviceLayoutSnapshot], so layout, catalog, and theme edits cannot reset it.
     */
    val layoutDragGuidanceCompleted: Boolean = false,
)

/** First-run / missing-key default for [SettingsLocal.quickRecordSlots]. */
val DEFAULT_QUICK_RECORD_SLOTS: List<String> = listOf(
    RecordType.PEE.key,
    RecordType.SLEEP.key,
    RecordType.NURSING.key,
    RecordType.FORMULA.key,
)

const val QUICK_RECORD_SLOT_COUNT: Int = 4

/**
 * Canonical pad/truncate for home quick-record slots.
 * Empty strings are intentional blanks; callers must not invent replacements.
 */
fun normalizeQuickRecordSlots(slots: List<String>): List<String> {
    val padded = slots.map { it.trim() }.toMutableList()
    while (padded.size < QUICK_RECORD_SLOT_COUNT) padded += ""
    return padded.take(QUICK_RECORD_SLOT_COUNT)
}

enum class SyncStatus {
    Disabled,
    BlockedOfflineHome,
    Idle,
    Syncing,
    Error,
}
