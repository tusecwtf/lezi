package com.lezi.babylog.sync

/**
 * Link-key decision (family-identity tickets 01 / 03):
 *
 * Current Record payloads attribute writers with server-owned
 * `created_by_membership_id`. Legacy payloads may only have
 * `created_by_device_id`.
 *
 * - `membership_id` is the primary server-minted link key.
 * - `device_id` is legacy-only and must never become authority or product copy.
 * - Tokens / token hashes / family_id remain omitted from members.
 * - Self records must not show an uploader label; unresolved non-self use
 *   role/「家人」fallbacks, never 「我（本机）」.
 */
data class UploaderMemberRef(
    val deviceId: String?,
    val displayName: String?,
    val role: FamilyRole,
    val isSelf: Boolean = false,
    val membershipId: String? = null,
)

/**
 * Pure membership-first author → 称呼 resolver for timeline upload labels.
 *
 * @return `null` when no uploader line should be shown (not joined, or self);
 *   otherwise a human label that never contains a raw device id.
 */
fun resolveRecordUploaderLabel(
    createdByDeviceId: String?,
    selfDeviceId: String,
    isFamilyJoined: Boolean,
    members: List<UploaderMemberRef>,
    createdByMembershipId: String? = null,
    selfMembershipId: String = "",
): String? {
    if (!isFamilyJoined) return null

    val membershipAuthor = createdByMembershipId?.trim().orEmpty()
    if (membershipAuthor.isNotEmpty()) {
        val selfMembership = selfMembershipId.trim()
        if (selfMembership.isNotEmpty() && membershipAuthor == selfMembership) return null
        val member = members.firstOrNull {
            it.membershipId?.trim() == membershipAuthor
        } ?: return "家人"
        if (selfMembership.isEmpty() && member.isSelf) return null
        return member.uploaderLabel()
    }

    val legacyDeviceAuthor = createdByDeviceId?.trim().orEmpty()
    if (legacyDeviceAuthor.isEmpty()) return "家人"
    if (selfDeviceId.isNotBlank() && legacyDeviceAuthor == selfDeviceId) return null
    val member = members.firstOrNull { it.deviceId?.trim() == legacyDeviceAuthor }
        ?: return "家人"
    if (member.isSelf) return null
    return member.uploaderLabel()
}

private fun UploaderMemberRef.uploaderLabel(): String {
    val name = displayName?.trim().orEmpty()
    if (name.isNotEmpty() && name != LOCAL_DEVICE_DISPLAY_NAME) return name
    return when (role) {
        FamilyRole.Owner -> "家庭管理员"
        FamilyRole.Member, FamilyRole.None -> "家庭成员"
    }
}

fun FamilyMember.toUploaderRef(): UploaderMemberRef? {
    val legacyDeviceId = deviceId?.trim()?.takeIf { it.isNotEmpty() }
    val stableMembershipId = membershipId?.trim()?.takeIf { it.isNotEmpty() }
    if (legacyDeviceId == null && stableMembershipId == null) return null
    return UploaderMemberRef(
        deviceId = legacyDeviceId,
        displayName = displayName,
        role = role,
        isSelf = isSelf,
        membershipId = stableMembershipId,
    )
}
