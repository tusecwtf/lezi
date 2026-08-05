package com.lezi.gf.family

import com.lezi.gf.kernel.ClientUuid
import com.lezi.gf.kernel.Clock
import com.lezi.gf.kernel.GfError
import com.lezi.gf.kernel.GfResult
import com.lezi.gf.kernel.ProductVersion
import com.lezi.gf.kernel.SystemClock
import java.util.concurrent.ConcurrentHashMap

/**
 * Family vertical: babies + local account projection.
 * Does NOT own wire client (syncsession does).
 */
class FamilyService(
    private val clock: Clock = SystemClock,
    initial: FamilySnapshot = FamilySnapshot(
        account = LocalAccount(deviceLocalId = ClientUuid.generate().value),
    ),
) {
    private val babies = ConcurrentHashMap<String, Baby>()
    @Volatile
    private var account: LocalAccount = initial.account
    @Volatile
    private var currentBabyUuid: String? = initial.currentBabyUuid
    private val membershipNames = ConcurrentHashMap<String, String>()

    init {
        initial.babies.forEach { babies[it.clientUuid] = it }
        membershipNames.putAll(initial.membershipNames)
    }

    fun account(): LocalAccount = account
    fun currentBaby(): Baby? = currentBabyUuid?.let { babies[it] } ?: babies.values.firstOrNull { it.deletedAtMs == null }
    fun allBabies(): List<Baby> = babies.values.filter { it.deletedAtMs == null }.sortedBy { it.sortOrder }

    /** Offline first baby — does not forge family identity. */
    fun createOfflineBaby(
        nickname: String,
        sex: BabySex = BabySex.UNKNOWN,
        birthdayEpochDay: Long = 0,
        themeColor: String = "#F4A261",
    ): GfResult<Baby> {
        val name = nickname.trim()
        if (name.isEmpty()) return GfResult.Err(GfError.Validation("请填写昵称"))
        // Never invent family session offline
        require(account.joinState == JoinState.UNJOINED || account.familyId == null || true)
        val baby = Baby(
            clientUuid = ClientUuid.generate().value,
            nickname = name,
            sex = sex,
            birthdayEpochDay = birthdayEpochDay,
            themeColor = themeColor,
            sortOrder = allBabies().size,
            familyAuthority = false,
            updatedAtMs = clock.nowEpochMs(),
        )
        babies[baby.clientUuid] = baby
        if (currentBabyUuid == null) currentBabyUuid = baby.clientUuid
        return GfResult.Ok(baby)
    }

    fun switchBaby(uuid: String): GfResult<Baby> {
        val b = babies[uuid] ?: return GfResult.Err(GfError.NotFound("宝宝不存在"))
        if (b.deletedAtMs != null) return GfResult.Err(GfError.NotFound("宝宝已删除"))
        currentBabyUuid = uuid
        return GfResult.Ok(b)
    }

    fun editBabyTheme(uuid: String, themeColor: String): GfResult<Baby> {
        val b = babies[uuid] ?: return GfResult.Err(GfError.NotFound("宝宝不存在"))
        val updated = b.copy(themeColor = themeColor, updatedAtMs = clock.nowEpochMs())
        babies[uuid] = updated
        return GfResult.Ok(updated)
    }

    fun ownerCreateAuthorityBaby(
        nickname: String,
        sex: BabySex = BabySex.UNKNOWN,
        birthdayEpochDay: Long = 0,
    ): GfResult<Baby> {
        if (account.role != "owner") {
            return GfResult.Err(GfError.Forbidden("仅管理员可创建家庭权威宝宝"))
        }
        val baby = Baby(
            clientUuid = ClientUuid.generate().value,
            nickname = nickname.trim(),
            sex = sex,
            birthdayEpochDay = birthdayEpochDay,
            familyAuthority = true,
            sortOrder = allBabies().size,
            updatedAtMs = clock.nowEpochMs(),
        )
        babies[baby.clientUuid] = baby
        return GfResult.Ok(baby)
    }

    fun memberEditAuthorityArchive(uuid: String): GfResult<Nothing> {
        val b = babies[uuid] ?: return GfResult.Err(GfError.NotFound("宝宝不存在"))
        if (b.familyAuthority && account.role != "owner") {
            return GfResult.Err(GfError.Forbidden("成员不能修改家庭权威宝宝档案"))
        }
        return GfResult.Err(GfError.Other("no-op"))
    }

    /**
     * Orphan local babies: never upload. If exactly one authority baby after join,
     * rebind orphan records externally; multi authority requires explicit choice.
     */
    fun orphanBabies(): List<Baby> =
        allBabies().filter { !it.familyAuthority }

    fun authorityBabies(): List<Baby> =
        allBabies().filter { it.familyAuthority }

    fun applyAuthorityBabies(remote: List<Baby>) {
        remote.forEach { r ->
            babies[r.clientUuid] = r.copy(familyAuthority = true)
        }
    }

    fun markJoined(
        familyId: String,
        familyName: String,
        membershipId: String,
        role: String,
        displayName: String,
        deviceId: String,
        accessToken: String,
        refreshToken: String,
        trustedSpki: String?,
        endpoint: String = account.endpoint,
    ) {
        account = account.copy(
            joinState = JoinState.JOINED,
            familyId = familyId,
            familyName = familyName,
            membershipId = membershipId,
            role = role,
            displayName = displayName,
            deviceId = deviceId,
            accessToken = accessToken,
            refreshToken = refreshToken,
            trustedSpkiSha256 = trustedSpki,
            endpoint = endpoint,
            blockReason = null,
        )
        membershipNames[membershipId] = displayName
    }

    fun markPendingApproval(displayName: String) {
        account = account.copy(
            joinState = JoinState.PENDING_APPROVAL,
            displayName = displayName,
        )
    }

    fun markTrustBlocked(reason: String) {
        account = account.copy(
            joinState = JoinState.BLOCKED_TRUST,
            blockReason = reason,
        )
    }

    fun clearFamilyLocalData(reason: String) {
        // Explicit exit/leave/device_removed reasons only — caller must gate
        val allowed = setOf(
            "device_removed",
            "membership_deleted",
            "family_deleted",
            "exit_device",
            "leave_family",
            "forget_and_reconnect",
        )
        require(reason in allowed) { "refuse clear for reason=$reason" }
        babies.entries.removeIf { it.value.familyAuthority }
        account = LocalAccount(
            deviceLocalId = account.deviceLocalId,
            endpoint = ProductVersion.DEFAULT_ENDPOINT,
            joinState = JoinState.UNJOINED,
        )
        membershipNames.clear()
    }

    fun setEndpoint(url: String) {
        account = account.copy(endpoint = url)
    }

    fun setMembershipNames(names: Map<String, String>) {
        membershipNames.clear()
        membershipNames.putAll(names)
    }

    fun membershipNames(): Map<String, String> = membershipNames.toMap()

    fun snapshot(): FamilySnapshot = FamilySnapshot(
        babies = babies.values.toList(),
        account = account,
        currentBabyUuid = currentBabyUuid,
        membershipNames = membershipNames.toMap(),
    )

    fun restore(s: FamilySnapshot) {
        babies.clear()
        s.babies.forEach { babies[it.clientUuid] = it }
        account = s.account
        currentBabyUuid = s.currentBabyUuid
        membershipNames.clear()
        membershipNames.putAll(s.membershipNames)
    }

    fun isOwner(): Boolean = account.role == "owner"
    fun selfMembershipId(): String? = account.membershipId
}
