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

data class Record(
    val id: Long = 0,
    val clientUuid: String,
    val babyId: Long,
    val type: RecordType,
    val timestamp: Long,
    val endTimestamp: Long? = null,
    val note: String? = null,
    val createdByUserId: Long,
    val payloadJson: String = "{}",
    val schemaVersion: Int = 1,
    val updatedAt: Long,
    val deletedAt: Long? = null,
) {
    val payload: RecordPayloadDocument
        get() = RecordPayloadCodec.decode(type, payloadJson, schemaVersion)
}

data class MediaAsset(
    val id: Long = 0,
    val recordId: Long,
    val localUri: String,
    val remoteUri: String? = null,
    val mime: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val createdAt: Long,
)

data class SettingsLocal(
    val itemOrderJson: String = "[]",
    val hiddenItems: Set<String> = emptySet(),
    val timerEnabled: Boolean = true,
    val recordAtStartOrEnd: String = "end",
    val nursingIntervalMin: Int = 180,
    val nextFeedAt: Long? = null,
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
)

enum class SyncStatus {
    Disabled,
    BlockedOfflineHome,
    Idle,
    Syncing,
    Error,
}
