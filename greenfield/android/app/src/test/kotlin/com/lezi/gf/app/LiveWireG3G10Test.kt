package com.lezi.gf.app

import com.google.common.truth.Truth.assertThat
import com.lezi.gf.app.sync.ForegroundSyncCoordinator
import com.lezi.gf.care.CareService
import com.lezi.gf.care.PhotoRef
import com.lezi.gf.care.RecordType
import com.lezi.gf.family.FamilyService
import com.lezi.gf.kernel.GfResult
import com.lezi.gf.kernel.ProductVersion
import com.lezi.gf.syncsession.OkHttpWireTransport
import com.lezi.gf.syncsession.SyncSessionService
import com.lezi.gf.syncsession.WireAppUpdate
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * G3–G10 against **live** lezi-gf-sync on HTTPS 18765 via OkHttpWireTransport
 * (not FakeWireTransport). Skips only if server unreachable.
 */
class LiveWireG3G10Test {

    private val endpoint = ProductVersion.DEFAULT_ENDPOINT
    private val bootstrap = System.getenv("LEZI_GF_BOOTSTRAP_SECRET")
        ?: "greenfield-dev-bootstrap"

    @Before
    fun requireLiveServer() {
        val probe = SyncSessionService(http = OkHttpWireTransport())
        probe.configureEndpoint(endpoint)
        val h = probe.health()
        Assume.assumeTrue(
            "live server required at $endpoint",
            h is GfResult.Ok && (h as GfResult.Ok).value.version == "1.0.0",
        )
    }

    private fun freshOwner(): Triple<SyncSessionService, FamilyService, CareService> {
        // Prefer empty server: disaster restore if needed, or create on empty
        val sync = SyncSessionService(http = OkHttpWireTransport())
        sync.configureEndpoint(endpoint)
        val status = sync.setupStatus() as GfResult.Ok
        val session = if (status.value.state == "empty") {
            sync.createFamily(
                bootstrap,
                "Live家-${UUID.randomUUID().toString().take(6)}",
                "Owner",
                "LiveDev-${UUID.randomUUID().toString().take(4)}",
            )
        } else {
            // already configured — login as owner multi-device
            sync.ownerLogin(bootstrap, "LiveDev-${UUID.randomUUID().toString().take(4)}", takeover = false)
        }
        assertThat(session).isInstanceOf(GfResult.Ok::class.java)
        val s = (session as GfResult.Ok).value
        val family = FamilyService()
        family.markJoined(
            s.family_id,
            s.family_name,
            s.membership_id,
            s.role,
            s.display_name,
            s.device_id,
            s.access_token,
            s.refresh_token,
            sync.trustedSpkiSha256,
            endpoint,
        )
        val care = CareService(
            selfMembershipId = { family.selfMembershipId() },
            isOwner = { family.isOwner() },
        )
        return Triple(sync, family, care)
    }

    @Test
    fun g3_liveCreateOrLoginOwner_visibleOnAccountProjection() {
        val (sync, family, _) = freshOwner()
        assertThat(family.account().role).isEqualTo("owner")
        assertThat(family.account().familyName).isNotEmpty()
        assertThat(sync.accessToken).isNotNull()
        val cap = sync.capability() as GfResult.Ok
        assertThat(cap.value.wire_current).isEqualTo(ProductVersion.WIRE_CURRENT)
    }

    @Test
    fun g4_g5_liveMemberPullAtomicRecordWithPhoto() {
        val (ownerSync, ownerFamily, ownerCare) = freshOwner()
        val baby = (ownerFamily.createOfflineBaby("LiveBaby") as GfResult.Ok).value
        val rec = (ownerCare.confirmCreate(
            ownerCare.openComposer(RecordType.DIARY, baby.clientUuid).copy(
                note = "live-g5",
                photos = listOf(
                    PhotoRef(
                        mediaUuid = UUID.randomUUID().toString(),
                        localPath = "b64:dGVzdA==",
                        byteSize = 4,
                        isDraftOwned = false,
                    ),
                ),
                dirty = true,
            ),
        ) as GfResult.Ok).value
        val push = ForegroundSyncCoordinator(ownerCare, ownerFamily, ownerSync).run()
        assertThat(push).isInstanceOf(GfResult.Ok::class.java)

        // Member apply-join on live server
        val memberSync = SyncSessionService(http = OkHttpWireTransport())
        memberSync.configureEndpoint(endpoint)
        val req = memberSync.applyJoin(
            "Member-${UUID.randomUUID().toString().take(4)}",
            "MDev",
        ) as GfResult.Ok
        val approved = ownerSync.approveJoin(req.value.request_id) as GfResult.Ok
        assertThat(approved.value.role).isEqualTo("member")
        val claimed = memberSync.claimJoin(req.value.request_id) as GfResult.Ok
        val memberFamily = FamilyService()
        memberFamily.markJoined(
            claimed.value.family_id,
            claimed.value.family_name,
            claimed.value.membership_id,
            claimed.value.role,
            claimed.value.display_name,
            claimed.value.device_id,
            claimed.value.access_token,
            claimed.value.refresh_token,
            null,
            endpoint,
        )
        val memberCare = CareService(
            selfMembershipId = { memberFamily.selfMembershipId() },
            isOwner = { false },
        )
        assertThat(memberCare.store().allRecords()).isEmpty()
        val pull = ForegroundSyncCoordinator(memberCare, memberFamily, memberSync).run()
        assertThat(pull).isInstanceOf(GfResult.Ok::class.java)
        val seen = memberCare.store().getRecord(rec.clientUuid)
        assertThat(seen).isNotNull()
        assertThat(seen!!.photos).isNotEmpty()
        assertThat(seen.note).isEqualTo("live-g5")
    }

    @Test
    fun g6_livePlanFulfillConvergesOnSecondClientApply() {
        val (ownerSync, ownerFamily, ownerCare) = freshOwner()
        val baby = (ownerFamily.createOfflineBaby("PlanBaby") as GfResult.Ok).value
        val plan = (ownerCare.createPlan(
            baby.clientUuid,
            RecordType.NURSING.key,
            scheduledAtMs = System.currentTimeMillis() + 60_000,
        ) as GfResult.Ok).value
        ForegroundSyncCoordinator(ownerCare, ownerFamily, ownerSync).run()

        // fulfill locally and push
        val fulfilled = ownerCare.fulfillPlan(plan.clientUuid) as GfResult.Ok
        ForegroundSyncCoordinator(ownerCare, ownerFamily, ownerSync).run()

        // second owner device login + pull
        val other = SyncSessionService(http = OkHttpWireTransport())
        other.configureEndpoint(endpoint)
        val login = other.ownerLogin(bootstrap, "OtherDev-${UUID.randomUUID().toString().take(4)}") as GfResult.Ok
        val f2 = FamilyService()
        f2.markJoined(
            login.value.family_id,
            login.value.family_name,
            login.value.membership_id,
            login.value.role,
            login.value.display_name,
            login.value.device_id,
            login.value.access_token,
            login.value.refresh_token,
            null,
            endpoint,
        )
        val c2 = CareService()
        ForegroundSyncCoordinator(c2, f2, other).run()
        val remotePlan = c2.store().getPlan(plan.clientUuid)
        assertThat(remotePlan?.status?.name).isEqualTo("COMPLETED")
        assertThat(c2.store().getRecord(fulfilled.value.second.clientUuid)).isNotNull()
    }

    @Test
    fun g7_liveMemberForbiddenOnOthersRecordViaCareAcl() {
        val (ownerSync, ownerFamily, ownerCare) = freshOwner()
        val baby = (ownerFamily.createOfflineBaby("AclBaby") as GfResult.Ok).value
        val rec = (ownerCare.confirmCreate(
            ownerCare.openComposer(RecordType.PEE, baby.clientUuid).copy(dirty = true),
        ) as GfResult.Ok).value
        ForegroundSyncCoordinator(ownerCare, ownerFamily, ownerSync).run()

        val memberSync = SyncSessionService(http = OkHttpWireTransport())
        memberSync.configureEndpoint(endpoint)
        val req = memberSync.applyJoin("AclMem", "dev") as GfResult.Ok
        ownerSync.approveJoin(req.value.request_id)
        val claimed = memberSync.claimJoin(req.value.request_id) as GfResult.Ok
        val memberCare = CareService(
            selfMembershipId = { claimed.value.membership_id },
            isOwner = { false },
        )
        // pull then try delete owner's record
        val mf = FamilyService()
        mf.markJoined(
            claimed.value.family_id, claimed.value.family_name, claimed.value.membership_id,
            claimed.value.role, claimed.value.display_name, claimed.value.device_id,
            claimed.value.access_token, claimed.value.refresh_token, null, endpoint,
        )
        ForegroundSyncCoordinator(memberCare, mf, memberSync).run()
        val denied = memberCare.deleteRecord(rec.clientUuid, confirmed = true)
        assertThat(denied).isInstanceOf(GfResult.Err::class.java)
    }

    @Test
    fun g8_liveSpkiAndFamilyMismatchBlock() {
        val sync = SyncSessionService(http = OkHttpWireTransport())
        sync.configureEndpoint(endpoint)
        sync.setTrustedSpki("pinned-spki-aaa")
        val blocked = sync.evaluateSpkiChange("pinned-spki-bbb")
        assertThat(blocked).isInstanceOf(GfResult.Err::class.java)
        assertThat(sync.evaluateFamilyMismatch("fam-a", "fam-b")).isInstanceOf(GfResult.Err::class.java)
    }

    @Test
    fun g9_liveUpdateShellAndUnjoinedHonesty() {
        val (sync, _, _) = freshOwner()
        // live app-update endpoint
        val u = sync.checkAppUpdate(joined = true)
        assertThat(u).isInstanceOf(GfResult.Ok::class.java)
        sync.injectMockUpdate(
            WireAppUpdate(999, "9.9.9", force = true, min_supported_version_code = 999),
        )
        assertThat(sync.forcedUpdateShell()?.force).isTrue()
        assertThat(sync.checkAppUpdate(joined = false)).isInstanceOf(GfResult.Err::class.java)
    }

    @Test
    fun g10_liveExitDeviceClearsSession_plain401DoesNotClearFlag() {
        val (sync, family, _) = freshOwner()
        assertThat(sync.exitDevice()).isInstanceOf(GfResult.Ok::class.java)
        assertThat(sync.accessToken).isNull()
        family.clearFamilyLocalData("exit_device")
        assertThat(family.account().joinState).isEqualTo(com.lezi.gf.family.JoinState.UNJOINED)
        assertThat(sync.shouldClearLocalOnAuthError("unauthorized")).isFalse()
        assertThat(sync.shouldClearLocalOnAuthError("device_removed")).isTrue()
    }

    @Test
    fun inviteDownloadFailClosedWhenHashEmpty() {
        // Live invite meta + download against 18765
        val client = okhttp3.OkHttpClient.Builder()
            .hostnameVerifier { _, _ -> true }
            .apply {
                val trustAll = object : javax.net.ssl.X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                }
                val ssl = javax.net.ssl.SSLContext.getInstance("TLS")
                ssl.init(null, arrayOf<javax.net.ssl.TrustManager>(trustAll), java.security.SecureRandom())
                sslSocketFactory(ssl.socketFactory, trustAll)
            }
            .build()
        val meta = client.newCall(
            okhttp3.Request.Builder().url("$endpoint/invite/apk").get().build(),
        ).execute()
        assertThat(meta.isSuccessful).isTrue()
        val body = meta.body?.string().orEmpty()
        assertThat(body).contains("sha256_required")
        assertThat(body).contains("login_via_invite")
        val dl = client.newCall(
            okhttp3.Request.Builder().url("$endpoint/invite/apk/download").get().build(),
        ).execute()
        // empty hash → fail closed (403)
        assertThat(dl.code).isEqualTo(403)
        val err = dl.body?.string().orEmpty()
        assertThat(err).contains("hash_required")
    }
}
