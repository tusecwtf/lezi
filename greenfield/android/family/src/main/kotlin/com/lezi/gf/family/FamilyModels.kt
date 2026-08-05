package com.lezi.gf.family

import kotlinx.serialization.Serializable

@Serializable
enum class BabySex { UNKNOWN, MALE, FEMALE }

@Serializable
data class Baby(
    val clientUuid: String,
    val nickname: String,
    val sex: BabySex = BabySex.UNKNOWN,
    val birthdayEpochDay: Long = 0,
    val themeColor: String = "#F4A261",
    val sortOrder: Int = 0,
    /** True when this row is from family-authority set (Owner-managed). */
    val familyAuthority: Boolean = false,
    val avatarPath: String? = null,
    val deletedAtMs: Long? = null,
    val updatedAtMs: Long = 0,
)

@Serializable
enum class JoinState {
    UNJOINED,
    PENDING_APPROVAL,
    JOINED,
    BLOCKED_TRUST,
}

@Serializable
data class LocalAccount(
    val deviceLocalId: String,
    val displayName: String? = null,
    val joinState: JoinState = JoinState.UNJOINED,
    val familyId: String? = null,
    val familyName: String? = null,
    val membershipId: String? = null,
    val role: String? = null, // owner | member
    val deviceId: String? = null,
    val endpoint: String = com.lezi.gf.kernel.ProductVersion.DEFAULT_ENDPOINT,
    val trustedSpkiSha256: String? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val blockReason: String? = null,
)

@Serializable
data class FamilySnapshot(
    val babies: List<Baby> = emptyList(),
    val account: LocalAccount = LocalAccount(deviceLocalId = "local"),
    val currentBabyUuid: String? = null,
    val membershipNames: Map<String, String> = emptyMap(),
)
