package com.lezi.babylog.sync.session

import com.lezi.babylog.sync.FamilyMember
import com.lezi.babylog.sync.backend.LOCAL_DEVICE_DISPLAY_NAME

/**
 * Link-key decision (family-identity tickets 01 / 03):
 *
 * Current Record payloads attribute writers with server-owned
 * `created_by_membership_id`, the only author link key.
 * - Tokens / token hashes / family_id remain omitted from members.
 * - Self records must not show an uploader label; unresolved non-self use
 *   role/「家人」fallbacks, never 「我（本机）」.
 */
data class UploaderMemberRef(
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
    isFamilyJoined: Boolean,
    members: List<UploaderMemberRef>,
    createdByMembershipId: String?,
    selfMembershipId: String = "",
): String? {
    if (!isFamilyJoined) return null

    val membershipAuthor = createdByMembershipId?.trim().orEmpty()
    if (membershipAuthor.isNotEmpty()) {
        val selfMembership = selfMembershipId.trim()
        if (selfMembership.isNotEmpty() && membershipAuthor == selfMembership) return null
        val member = members.firstOrNull {
            it.membershipId?.trim() == membershipAuthor
        } ?: return FAMILY_MEMBER_FALLBACK_LABEL
        if (selfMembership.isEmpty() && member.isSelf) return null
        return member.uploaderLabel()
    }

    return FAMILY_MEMBER_FALLBACK_LABEL
}

private fun UploaderMemberRef.uploaderLabel(): String {
    val name = displayName?.trim().orEmpty()
    if (name.isNotEmpty() && name != LOCAL_DEVICE_DISPLAY_NAME) return name
    return familyRoleFallbackLabel(role)
}

/** Shared role fallback copy for timeline uploaders and conflict audit. */
fun familyRoleFallbackLabel(role: FamilyRole): String = when (role) {
    FamilyRole.Owner -> "家庭管理员"
    FamilyRole.Member, FamilyRole.None -> "家庭成员"
}

/** Unknown membership / missing name fallback. */
const val FAMILY_MEMBER_FALLBACK_LABEL: String = "家人"

fun FamilyMember.toUploaderRef(): UploaderMemberRef? {
    return UploaderMemberRef(
        displayName = displayName,
        role = role,
        isSelf = isSelf,
        membershipId = membershipId,
    )
}
