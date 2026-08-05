package com.lezi.gf.app.invite

import com.lezi.gf.app.AppContainer
import com.lezi.gf.family.JoinState
import com.lezi.gf.kernel.GfResult
import com.lezi.gf.syncsession.MemberLoginQrEnvelope

/**
 * Product scan-result handler for account join / member QR.
 * Camera / paste / system-camera only supply a [raw] string — all join rules live here.
 */
object InviteScanHandler {
    sealed class Outcome {
        /** Open LAN invite install page; must NOT markJoined. */
        data class OpenInviteInstall(
            val pageUrl: String,
            val userMessage: String,
            val grantCodePresent: Boolean,
        ) : Outcome()

        /** Successfully claimed grant and marked local account joined. */
        data class Joined(
            val familyName: String,
            val displayName: String,
            val userMessage: String,
        ) : Outcome()

        data class Failed(val userMessage: String) : Outcome()
    }

    /**
     * @param openInviteInBrowser optional side-effect (UI starts ACTION_VIEW); never marks joined.
     */
    fun handle(
        container: AppContainer,
        raw: String,
        openInviteInBrowser: (String) -> Unit = {},
    ): Outcome {
        val joinBefore = container.family.account().joinState
        return when (val parsed = MemberLoginQrEnvelope.parseScan(raw)) {
            is MemberLoginQrEnvelope.ParseResult.Err ->
                Outcome.Failed(parsed.message)

            is MemberLoginQrEnvelope.ParseResult.Ok -> {
                val grant = parsed.grant
                // Invite page only (no claimable code): open install surface, never join.
                // Full URL with #v1.grant is re-scanned in-app after install → claim below.
                if (parsed.isInviteUrl && !MemberLoginQrEnvelope.isClaimable(grant)) {
                    val page = when {
                        !grant.endpoint.isNullOrBlank() ->
                            MemberLoginQrEnvelope.invitePageUrl(grant.endpoint!!)
                        raw.contains('#') -> raw.substringBefore('#')
                        else -> raw.trim()
                    }
                    openInviteInBrowser(page)
                    if (container.family.account().joinState != joinBefore) {
                        return Outcome.Failed("内部错误：邀请页路径不得改写加入状态")
                    }
                    return Outcome.OpenInviteInstall(
                        pageUrl = page,
                        userMessage = "已打开邀请安装页（不含完整授权）。安装后请使用管理员提供的授权码再扫一次。",
                        grantCodePresent = false,
                    )
                }

                if (!MemberLoginQrEnvelope.isClaimable(grant)) {
                    return Outcome.Failed("授权码为空，无法加入家庭")
                }

                grant.endpoint?.takeIf { it.isNotBlank() }?.let { ep ->
                    container.family.setEndpoint(ep)
                    container.sync.configureEndpoint(ep)
                }
                grant.trustedSpkiSha256?.let { spki ->
                    when (val t = container.sync.evaluateSpkiChange(spki)) {
                        is GfResult.Err -> {
                            container.family.markTrustBlocked(t.error.toString())
                            return Outcome.Failed("信任阻断：请忘记并重新连接")
                        }
                        is GfResult.Ok -> container.sync.setTrustedSpki(spki)
                    }
                }

                when (val claim = container.sync.claimMemberQr(grant.code)) {
                    is GfResult.Ok -> {
                        val s = claim.value
                        container.family.markJoined(
                            familyId = s.family_id,
                            familyName = s.family_name,
                            membershipId = s.membership_id,
                            role = s.role,
                            displayName = s.display_name,
                            deviceId = s.device_id,
                            accessToken = s.access_token,
                            refreshToken = s.refresh_token,
                            trustedSpki = container.sync.trustedSpkiSha256
                                ?: grant.trustedSpkiSha256,
                            endpoint = grant.endpoint ?: container.family.account().endpoint,
                        )
                        Outcome.Joined(
                            familyName = s.family_name,
                            displayName = s.display_name,
                            userMessage = "已加入家庭「${s.family_name}」",
                        )
                    }
                    is GfResult.Err -> {
                        if (joinBefore != JoinState.JOINED &&
                            container.family.account().joinState == JoinState.JOINED
                        ) {
                            return Outcome.Failed("内部错误：失败路径不应标记已加入")
                        }
                        Outcome.Failed("领取授权失败，请检查码是否过期或已使用")
                    }
                }
            }
        }
    }

    /** Admin QR string for system camera (full landing URL with #v1.grant). */
    fun adminDisplayPayload(
        apiEndpoint: String,
        code: String,
        trustedSpki: String? = null,
    ): String {
        val grant = MemberLoginQrEnvelope.MemberLoginGrant(
            code = code,
            endpoint = apiEndpoint,
            trustedSpkiSha256 = trustedSpki,
        )
        return MemberLoginQrEnvelope.buildLandingUrl(apiEndpoint, grant)
    }
}
