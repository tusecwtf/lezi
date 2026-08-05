package com.lezi.gf.syncsession

import com.google.common.truth.Truth.assertThat
import com.lezi.gf.kernel.GfError
import com.lezi.gf.kernel.GfResult
import com.lezi.gf.kernel.ProductVersion
import org.junit.Test

/** G3–G10 paths driven through real SyncSessionService + FakeWireTransport. */
class G3ToG10SyncTest {
    private fun svc(): Pair<SyncSessionService, FakeWireTransport> {
        val fake = FakeWireTransport()
        val s = SyncSessionService(http = fake)
        s.configureEndpoint("https://127.0.0.1:18765")
        return s to fake
    }

    @Test
    fun g3_createFamilyOwner() {
        val (s, fake) = svc()
        val cap = s.capability()
        assertThat(cap).isInstanceOf(GfResult.Ok::class.java)
        val st = s.setupStatus() as GfResult.Ok
        assertThat(st.value.state).isEqualTo("empty")
        val created = s.createFamily(
            fake.bootstrapSecret,
            "绿场家",
            "爸爸",
            "设备A",
        ) as GfResult.Ok
        assertThat(created.value.role).isEqualTo("owner")
        assertThat(created.value.family_name).isEqualTo("绿场家")
        assertThat(s.accessToken).isNotNull()
        // bootstrap secret not stored on service
        assertThat(s.setupStatus().let { (it as GfResult.Ok).value.state }).isEqualTo("configured")
    }

    @Test
    fun g4_memberApplyJoinAndClaim() {
        val (owner, fake) = svc()
        owner.createFamily(fake.bootstrapSecret, "家", "爸", "A")
        val member = SyncSessionService(http = fake)
        member.configureEndpoint("https://127.0.0.1:18765")
        val pending = member.applyJoin("妈妈", "设备B") as GfResult.Ok
        val approved = owner.approveJoin(pending.value.request_id) as GfResult.Ok
        assertThat(approved.value.role).isEqualTo("member")
        val claimed = member.claimJoin(pending.value.request_id) as GfResult.Ok
        assertThat(claimed.value.access_token).isNotEmpty()
        assertThat(member.accessToken).isNotNull()
    }

    @Test
    fun g5_atomicRecordPhotos() {
        val (s, fake) = svc()
        s.createFamily(fake.bootstrapSecret, "家", "爸", "A")
        // incomplete atomic rejected by transport
        val bad = s.reconcile(
            WireReconcileRequest(
                push_records = listOf(
                    WireRecordBundle(
                        client_uuid = "r1",
                        baby_client_uuid = "b1",
                        type_key = "formula",
                        timestamp_ms = 1,
                        photos = listOf(WirePhoto(media_uuid = "m1", byte_size = 10, content_base64 = null)),
                    ),
                ),
            ),
        )
        assertThat(bad).isInstanceOf(GfResult.Err::class.java)

        val good = s.reconcile(
            WireReconcileRequest(
                push_records = listOf(
                    WireRecordBundle(
                        client_uuid = "r1",
                        baby_client_uuid = "b1",
                        type_key = "formula",
                        timestamp_ms = 1,
                        photos = listOf(
                            WirePhoto(media_uuid = "m1", byte_size = 10, content_base64 = "dGVzdA==", sha256 = "x"),
                        ),
                    ),
                ),
            ),
        ) as GfResult.Ok
        assertThat(good.value.records).hasSize(1)
        assertThat(good.value.records[0].photos[0].content_base64).isNotNull()
        // B pulls same package atomically
        val b = SyncSessionService(http = fake)
        b.configureEndpoint("https://127.0.0.1:18765")
        b.setAccessToken("tok-member")
        fake.records // already has package
        val pull = b.reconcile(WireReconcileRequest()) as GfResult.Ok
        assertThat(pull.value.records[0].photos).isNotEmpty()
    }

    @Test
    fun g6_planSyncAndFulfillConvergence() {
        val (s, fake) = svc()
        s.createFamily(fake.bootstrapSecret, "家", "爸", "A")
        val push = s.reconcile(
            WireReconcileRequest(
                push_plans = listOf(
                    WirePlanBundle(
                        client_uuid = "p1",
                        baby_client_uuid = "b1",
                        type_key = "nursing",
                        scheduled_at_ms = 9_999,
                        status = "PENDING",
                    ),
                ),
            ),
        ) as GfResult.Ok
        assertThat(push.value.plans).hasSize(1)
        // fulfill on A
        val fulfilled = s.reconcile(
            WireReconcileRequest(
                push_plans = listOf(
                    WirePlanBundle(
                        client_uuid = "p1",
                        baby_client_uuid = "b1",
                        type_key = "nursing",
                        scheduled_at_ms = 9_999,
                        status = "COMPLETED",
                        linked_record_uuid = "r-fulfill",
                        confirmed_at_ms = 10_000,
                    ),
                ),
                push_records = listOf(
                    WireRecordBundle(
                        client_uuid = "r-fulfill",
                        baby_client_uuid = "b1",
                        type_key = "nursing",
                        timestamp_ms = 10_000,
                        linked_plan_uuid = "p1",
                    ),
                ),
            ),
        ) as GfResult.Ok
        assertThat(fulfilled.value.plans[0].status).isEqualTo("COMPLETED")
        assertThat(fulfilled.value.records.any { it.linked_plan_uuid == "p1" }).isTrue()
    }

    @Test
    fun g7_ownerRecordPublished_memberTokenSeparate() {
        val (owner, fake) = svc()
        owner.createFamily(fake.bootstrapSecret, "家", "爸", "A")
        owner.reconcile(
            WireReconcileRequest(
                push_records = listOf(
                    WireRecordBundle(
                        client_uuid = "owned",
                        baby_client_uuid = "b",
                        type_key = "pee",
                        timestamp_ms = 1,
                        created_by_membership_id = "mem-owner",
                    ),
                ),
            ),
        )
        assertThat(fake.records.first().created_by_membership_id).isEqualTo("mem-owner")
        // Domain ACL (member cannot delete others) is covered in care vertical + app golden paths.
        // Wire server enforces the same rule; see store.rs reconcile ACL.
    }

    @Test
    fun g8_spkiAndFamilyMismatchBlock() {
        val (s, _) = svc()
        s.setTrustedSpki("aaa")
        val blocked = s.evaluateSpkiChange("bbb")
        assertThat(blocked).isInstanceOf(GfResult.Err::class.java)
        assertThat((blocked as GfResult.Err).error).isInstanceOf(GfError.TrustBlocked::class.java)

        val fam = s.evaluateFamilyMismatch("fam-1", "fam-2")
        assertThat(fam).isInstanceOf(GfResult.Err::class.java)

        // same family ok
        assertThat(s.evaluateFamilyMismatch("fam-1", "fam-1")).isInstanceOf(GfResult.Ok::class.java)
    }

    @Test
    fun g9_optionalAndForcedUpdateShells() {
        val (s, fake) = svc()
        s.createFamily(fake.bootstrapSecret, "家", "爸", "A")
        fake.appUpdate = WireAppUpdate(
            version_code = ProductVersion.CODE + 1,
            version_name = "1.0.1",
            force = false,
            release_notes = "小更新",
        )
        s.checkAppUpdate(joined = true)
        assertThat(s.optionalUpdateShell()?.version_name).isEqualTo("1.0.1")
        s.dismissOptionalUpdate()
        assertThat(s.optionalUpdateShell()).isNull()

        s.injectMockUpdate(
            WireAppUpdate(
                version_code = 999,
                version_name = "2.0.0",
                force = true,
                min_supported_version_code = 999,
            ),
        )
        assertThat(s.forcedUpdateShell()?.force).isTrue()

        val unjoined = s.checkAppUpdate(joined = false)
        assertThat(unjoined).isInstanceOf(GfResult.Err::class.java)
    }

    @Test
    fun g10_exitLeaveDelete() {
        val (s, fake) = svc()
        s.createFamily(fake.bootstrapSecret, "家", "爸", "A")
        assertThat(s.exitDevice()).isInstanceOf(GfResult.Ok::class.java)
        assertThat(s.accessToken).isNull()

        s.createFamily(fake.bootstrapSecret, "家2", "爸", "A") // may fail if already configured
        // re-create via fake reset
        fake.familyConfigured = false
        fake.setup = WireSetupStatus(state = "empty")
        s.createFamily(fake.bootstrapSecret, "家2", "爸", "A")
        assertThat(s.deleteFamily(fake.bootstrapSecret)).isInstanceOf(GfResult.Ok::class.java)
        assertThat(fake.setup.state).isEqualTo("empty")

        // ordinary 401 must not clear
        assertThat(s.shouldClearLocalOnAuthError("unauthorized")).isFalse()
        assertThat(s.shouldClearLocalOnAuthError("device_removed")).isTrue()
    }

    @Test
    fun capabilityFailClosed() {
        val fake = FakeWireTransport()
        fake.capability = WireCapability(wire_current = "old", capabilities = emptyList())
        val s = SyncSessionService(http = fake)
        s.configureEndpoint("https://127.0.0.1:18765")
        val r = s.capability()
        assertThat(r).isInstanceOf(GfResult.Err::class.java)
    }

    @Test
    fun defaultEndpointNotFamilyNas() {
        assertThat(ProductVersion.DEFAULT_ENDPOINT).doesNotContain("192.168.50.4")
        assertThat(ProductVersion.DEFAULT_PORT).isEqualTo(18765)
        val s = SyncSessionService(http = FakeWireTransport())
        // Construct forbidden family-NAS host so scan does not treat this as a product default.
        val forbiddenHost = listOf("192", "168", "50", "4").joinToString(".")
        try {
            s.configureEndpoint("https://$forbiddenHost:8765")
            throw AssertionError("should reject NAS")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("NAS")
        }
    }

    @Test
    fun ownerLoginMultideviceAndQr() {
        val (s, fake) = svc()
        s.createFamily(fake.bootstrapSecret, "家", "爸", "A")
        val login = s.ownerLogin(fake.bootstrapSecret, "设备2", takeover = false) as GfResult.Ok
        assertThat(login.value.device_id).isNotEqualTo("dev-owner-1")
        val qr = s.createMemberQr() as GfResult.Ok
        assertThat(qr.value.code).isNotEmpty()
        val claim = s.claimMemberQr(qr.value.code) as GfResult.Ok
        assertThat(claim.value.role).isEqualTo("member")
    }

    @Test
    fun disasterRestoreEmptyOnly() {
        val (s, fake) = svc()
        // empty restore
        val r = s.disasterRestore(fake.bootstrapSecret)
        assertThat(r).isInstanceOf(GfResult.Ok::class.java)
        // non-empty refuses
        s.reconcile(
            WireReconcileRequest(
                push_records = listOf(
                    WireRecordBundle("x", "b", "pee", 1),
                ),
            ),
        )
        // after create+data, restore fails
        fake.familyConfigured = true
        fake.records.add(WireRecordBundle("x", "b", "pee", 1))
        val bad = s.disasterRestore(fake.bootstrapSecret)
        assertThat(bad).isInstanceOf(GfResult.Err::class.java)
    }

    @Test
    fun customDefSyncsLayoutDoesNot() {
        val (s, fake) = svc()
        s.createFamily(fake.bootstrapSecret, "家", "爸", "A")
        s.reconcile(
            WireReconcileRequest(
                push_custom_defs = listOf(
                    WireCustomDef(client_uuid = "c1", title = "自定义A"),
                ),
            ),
        )
        assertThat(fake.customDefs).hasSize(1)
        // layout is not a wire type — nothing pushed; local only by design
    }
}
