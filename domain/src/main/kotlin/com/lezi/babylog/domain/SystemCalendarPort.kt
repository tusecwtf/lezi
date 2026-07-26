package com.lezi.babylog.domain

/**
 * Device-local optional projection of CarePlan into the Android system calendar.
 *
 * Not family-synced. Passive plan reads never request permission; only an explicit
 * user enable path may prompt. Failures must never roll back CarePlan saves.
 */
interface SystemCalendarPort {
    /** Whether the app currently holds the needed calendar permissions. */
    fun hasCalendarPermission(): Boolean

    /**
     * Writable calendars the user may pick. Empty when permission denied or
     * the provider is unavailable.
     */
    suspend fun listWritableCalendars(): List<SystemCalendarTarget>

    /**
     * Insert or update a begin-time alert event for [request].
     * @return platform event id on success; null on deny/fail/missing target.
     */
    suspend fun upsertEvent(request: SystemCalendarUpsert): String?

    /** Best-effort delete of a previously projected event. */
    suspend fun deleteEvent(eventId: String): Boolean

    /**
     * Whether [eventId] still exists in the provider.
     * Used to detect vanished events after permission/target loss.
     */
    suspend fun eventExists(eventId: String): Boolean

    /**
     * Whether [calendarId] is still among writable calendars.
     * False when permission denied, target deleted, or provider unavailable.
     */
    suspend fun isWritableCalendar(calendarId: String): Boolean
}

data class SystemCalendarTarget(
    val calendarId: String,
    val displayName: String,
    val accountName: String,
)

/**
 * Graded system-calendar disclosure (device-local; never family-synced).
 * 1 = 仅乐记事件, 2 = 宝宝昵称·类型 (default), 3 = + 备注/照片数/深链.
 */
enum class SystemCalendarDisclosureLevel(val stored: Int) {
    /** Title only: 「乐记 · 护理计划」. */
    EVENT_ONLY(1),

    /** Title: 「宝宝昵称 · 记录类型」. First-config default. */
    BABY_AND_TYPE(2),

    /**
     * Title same as [BABY_AND_TYPE]; description may include note text,
     * 「照片 N 张，打开乐记查看」, and a stable deep link. Never photo bytes/URIs.
     */
    DETAILS(3),
    ;

    companion object {
        fun fromStored(level: Int): SystemCalendarDisclosureLevel = when (level.coerceIn(1, 3)) {
            1 -> EVENT_ONLY
            3 -> DETAILS
            else -> BABY_AND_TYPE
        }
    }
}

/** Pure projected title/description for a CarePlan copy. */
data class SystemCalendarProjectedContent(
    val title: String,
    val description: String?,
    /** Stable app deep link for L3; null for L1/L2. */
    val deepLinkUri: String?,
)

/**
 * Pure L1/L2/L3 content policy. Call sites must not inject photo bytes or
 * local photo URIs into [SystemCalendarUpsert].
 */
object SystemCalendarDisclosurePolicy {
    const val L1_TITLE: String = "乐记 · 护理计划"

    fun deepLinkUri(carePlanClientUuid: String): String =
        "lezi://care-plan/$carePlanClientUuid"

    fun photoCountLine(photoCount: Int): String =
        "照片 ${photoCount.coerceAtLeast(0)} 张，打开乐记查看"

    fun build(
        level: SystemCalendarDisclosureLevel,
        babyNickname: String,
        recordTypeLabel: String,
        note: String?,
        photoCount: Int,
        carePlanClientUuid: String,
    ): SystemCalendarProjectedContent {
        val nickname = babyNickname.trim().ifBlank { "宝宝" }
        val typeLabel = recordTypeLabel.trim().ifBlank { "护理" }
        val babyAndType = "$nickname · $typeLabel"
        return when (level) {
            SystemCalendarDisclosureLevel.EVENT_ONLY -> SystemCalendarProjectedContent(
                title = L1_TITLE,
                description = null,
                deepLinkUri = null,
            )
            SystemCalendarDisclosureLevel.BABY_AND_TYPE -> SystemCalendarProjectedContent(
                title = babyAndType,
                description = null,
                deepLinkUri = null,
            )
            SystemCalendarDisclosureLevel.DETAILS -> {
                val deepLink = deepLinkUri(carePlanClientUuid)
                val parts = mutableListOf<String>()
                note?.trim()?.takeIf { it.isNotBlank() }?.let { parts += it }
                if (photoCount > 0) {
                    parts += photoCountLine(photoCount)
                }
                // Always surface deep link in description text: OEMs may ignore CUSTOM_APP_URI.
                parts += deepLink
                SystemCalendarProjectedContent(
                    title = babyAndType,
                    description = parts.joinToString("\n"),
                    deepLinkUri = deepLink,
                )
            }
        }
    }

    /**
     * Detect accidental photo-path / content-URI leaks in projected strings.
     * Unit tests lock that L1–L3 output never contains these patterns from app media.
     */
    fun containsForbiddenPhotoLeak(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val lower = text.lowercase()
        return lower.contains("content://") ||
            lower.contains("file://") ||
            lower.contains("/storage/") ||
            lower.contains("/data/data/") ||
            lower.contains("fileprovider") ||
            // Common private app media segments (not user-visible deep links).
            lower.contains("/cache/") && lower.contains("photo")
    }
}

/**
 * Insert/update payload for [SystemCalendarPort.upsertEvent].
 * Title/description come from [SystemCalendarDisclosurePolicy] — never photo bytes.
 */
data class SystemCalendarUpsert(
    val calendarId: String,
    val carePlanClientUuid: String,
    val beginAtMillis: Long,
    val title: String,
    /** Optional description; L1/L2 leave this blank; L3 may include note + photo count + deep link. */
    val description: String? = null,
    /** Existing platform event id to update; null inserts. */
    val existingEventId: String? = null,
    /**
     * Optional [CalendarContract.Events.CUSTOM_APP_URI] for L3 deep link.
     * Always also mirrored in [description] for OEM compatibility.
     */
    val customAppUri: String? = null,
)

/** No-op adapter for tests and devices without calendar integration. */
class NoOpSystemCalendarPort : SystemCalendarPort {
    override fun hasCalendarPermission(): Boolean = false
    override suspend fun listWritableCalendars(): List<SystemCalendarTarget> = emptyList()
    override suspend fun upsertEvent(request: SystemCalendarUpsert): String? = null
    override suspend fun deleteEvent(eventId: String): Boolean = false
    override suspend fun eventExists(eventId: String): Boolean = false
    override suspend fun isWritableCalendar(calendarId: String): Boolean = false
}

/** User-facing status when a plan wanted system calendar but projection is missing. */
const val SYSTEM_CALENDAR_UNSYNCED_LABEL = "未同步到系统日历"

/**
 * Pure policy for “未同步到系统日历” chrome.
 * Only meaningful when the user enabled system calendar and picked a target.
 */
fun evaluateCarePlanSystemCalendarUnsynced(
    systemCalendarEnabled: Boolean,
    systemCalendarId: String?,
    hasPermission: Boolean,
    targetWritable: Boolean,
    mappedEventId: String?,
    eventExists: Boolean,
): Boolean {
    if (!systemCalendarEnabled || systemCalendarId.isNullOrBlank()) return false
    if (!hasPermission) return true
    if (!targetWritable) return true
    if (mappedEventId.isNullOrBlank()) return true
    return !eventExists
}

/**
 * Stable CalendarContract contract used by [AndroidSystemCalendarPort].
 * Kept pure so unit tests can lock the provider decisions without Robolectric.
 */
object SystemCalendarProjectionContract {
    /** Begin-time alert (minutes before start = 0). */
    const val BEGIN_REMINDER_MINUTES: Int = 0

    /** Point events need a non-zero DTEND for OEM acceptance. */
    const val POINT_EVENT_DURATION_MS: Long = 30L * 60_000L

    fun eventUid(carePlanClientUuid: String): String =
        "lezi-care-plan-$carePlanClientUuid"

    /** CalendarContract access levels at or above contributor are writable. */
    fun isWritableAccessLevel(accessLevel: Int, contributorThreshold: Int): Boolean =
        accessLevel >= contributorThreshold

    /**
     * Stable fulfill deep link used for L3 CUSTOM_APP_URI and description text.
     * Host path only — never includes photo URIs or local file paths.
     */
    fun carePlanDeepLink(carePlanClientUuid: String): String =
        SystemCalendarDisclosurePolicy.deepLinkUri(carePlanClientUuid)
}
