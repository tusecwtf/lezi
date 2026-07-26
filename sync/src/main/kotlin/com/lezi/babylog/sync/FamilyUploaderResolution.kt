package com.lezi.babylog.sync

/**
 * Link-key decision (family-identity ticket 01):
 *
 * Record payloads attribute writers with `created_by_device_id`. Members list
 * returns the same value as `device_id` so clients can resolve the writer's
 * **current** 家庭称呼 without a historical name snapshot.
 *
 * - `device_id` is a **client-only link key** — never render it in product UI.
 * - Tokens / token hashes / family_id remain omitted from members.
 * - Self records must not show an uploader label; unresolved non-self use
 *   role/「家人」fallbacks, never 「我（本机）」.
 */
data class UploaderMemberRef(
    val deviceId: String,
    val displayName: String?,
    val role: FamilyRole,
    val isSelf: Boolean = false,
)

/**
 * Pure createdByDeviceId → 称呼 resolver for timeline upload labels (ticket 05).
 *
 * @return `null` when no uploader line should be shown (not joined, or self);
 *   otherwise a human label that never contains a raw device id.
 */
fun resolveRecordUploaderLabel(
    createdByDeviceId: String?,
    selfDeviceId: String,
    isFamilyJoined: Boolean,
    members: List<UploaderMemberRef>,
): String? {
    if (!isFamilyJoined) return null
    val created = createdByDeviceId?.trim().orEmpty()
    if (created.isEmpty()) return "家人"
    if (selfDeviceId.isNotBlank() && created == selfDeviceId) return null
    val member = members.firstOrNull { it.deviceId == created }
        ?: return "家人"
    if (member.isSelf) return null
    val name = member.displayName?.trim().orEmpty()
    if (name.isNotEmpty() && name != LOCAL_DEVICE_DISPLAY_NAME) return name
    return when (member.role) {
        FamilyRole.Owner -> "家庭管理员"
        FamilyRole.Member, FamilyRole.None -> "家庭成员"
    }
}

fun FamilyMember.toUploaderRef(): UploaderMemberRef? {
    val id = deviceId?.trim().orEmpty()
    if (id.isEmpty()) return null
    return UploaderMemberRef(
        deviceId = id,
        displayName = displayName,
        role = role,
        isSelf = isSelf,
    )
}
