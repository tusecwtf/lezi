package com.lezi.gf.app

import com.google.common.truth.Truth.assertThat
import com.lezi.gf.care.CareAggregation
import com.lezi.gf.care.PhotoRef
import com.lezi.gf.care.PlanStatus
import com.lezi.gf.care.RecordType
import com.lezi.gf.family.JoinState
import com.lezi.gf.kernel.FixedClock
import com.lezi.gf.kernel.GfResult
import com.lezi.gf.kernel.ProductVersion
import com.lezi.gf.syncsession.FakeWireTransport
import com.lezi.gf.syncsession.SyncSessionService
import com.lezi.gf.syncsession.WirePhoto
import com.lezi.gf.syncsession.WirePlanBundle
import com.lezi.gf.syncsession.WireRecordBundle
import com.lezi.gf.syncsession.WireReconcileRequest
import org.junit.Test

/**
 * G1–G10 observable-outcome integration at the composition boundary
 * (care + family + syncsession with fakes — no emulator required).
 */
class GoldenPathsIntegrationTest {

    @Test
    fun g1_offlineLogPipeline() {
        val clock = FixedClock(1_700_000_000_000L)
        val family = com.lezi.gf.family.FamilyService(clock = clock)
        val baby = (family.createOfflineBaby("豆豆") as GfResult.Ok).value
        val care = com.lezi.gf.care.CareService(clock = clock)
        assertThat(care.store().allRecords()).isEmpty()
        val draft = care.openComposer(RecordType.FORMULA, baby.clientUuid)
        assertThat(care.store().allRecords()).isEmpty()
        care.confirmCreate(draft.copy(payloadJson = """{"amount_ml":90,"amount_step_ml":5}""", dirty = true))
        care.confirmCreate(care.openComposer(RecordType.PEE, baby.clientUuid).copy(dirty = true))
        val day = CareAggregation.dayStartMs(clock.nowEpochMs())
        assertThat(care.daySummary(baby.clientUuid, day).milkMl).isEqualTo(90)
        assertThat(care.timeline(baby.clientUuid, day)).hasSize(2)
    }

    @Test
    fun g2_timerNextFeedFulfill() {
        val clock = FixedClock(1_000L)
        val care = com.lezi.gf.care.CareService(clock = clock)
        care.startTimer("b", "L")
        clock.advance(5_000)
        val draft = (care.completeTimer() as GfResult.Ok).value
        assertThat(care.store().allRecords()).isEmpty()
        care.confirmCreate(draft)
        val plan = (care.createPlan("b", "nursing", clock.nowEpochMs() + 10_000, isNextFeed = true) as GfResult.Ok).value
        assertThat(care.pendingPlans("b")).isNotEmpty()
        clock.advance(20_000)
        val (done, rec) = (care.fulfillPlan(plan.clientUuid) as GfResult.Ok).value
        assertThat(done.status).isEqualTo(PlanStatus.COMPLETED)
        assertThat(rec.linkedPlanUuid).isEqualTo(plan.clientUuid)
    }

    @Test
    fun g3_to_g10_familySyncMatrix() {
        val fake = FakeWireTransport()
        val ownerSync = SyncSessionService(http = fake)
        ownerSync.configureEndpoint(ProductVersion.DEFAULT_ENDPOINT)
        val familyOwner = com.lezi.gf.family.FamilyService()
        familyOwner.createOfflineBaby("宝")

        // G3
        val created = ownerSync.createFamily(fake.bootstrapSecret, "绿场", "爸爸", "A") as GfResult.Ok
        familyOwner.markJoined(
            created.value.family_id,
            created.value.family_name,
            created.value.membership_id,
            created.value.role,
            created.value.display_name,
            created.value.device_id,
            created.value.access_token,
            created.value.refresh_token,
            "spki",
        )
        assertThat(familyOwner.account().joinState).isEqualTo(JoinState.JOINED)
        assertThat(familyOwner.account().role).isEqualTo("owner")

        // G4
        val memberSync = SyncSessionService(http = fake)
        memberSync.configureEndpoint(ProductVersion.DEFAULT_ENDPOINT)
        val req = (memberSync.applyJoin("妈妈", "B") as GfResult.Ok).value
        ownerSync.approveJoin(req.request_id)
        val claimed = memberSync.claimJoin(req.request_id) as GfResult.Ok
        assertThat(claimed.value.role).isEqualTo("member")

        // G5 atomic
        val careA = com.lezi.gf.care.CareService(selfMembershipId = { created.value.membership_id }, isOwner = { true })
        val rec = (careA.confirmCreate(
            careA.openComposer(RecordType.DIARY, "b").copy(
                photos = listOf(PhotoRef("m1", "p.jpg", byteSize = 12, isDraftOwned = false)),
                dirty = true,
            ),
        ) as GfResult.Ok).value
        val push = ownerSync.reconcile(
            WireReconcileRequest(
                push_records = listOf(
                    WireRecordBundle(
                        client_uuid = rec.clientUuid,
                        baby_client_uuid = rec.babyClientUuid,
                        type_key = rec.typeKey,
                        timestamp_ms = rec.timestampMs,
                        photos = listOf(WirePhoto("m1", byte_size = 12, content_base64 = "YQ==", sha256 = "h")),
                        created_by_membership_id = created.value.membership_id,
                    ),
                ),
            ),
        ) as GfResult.Ok
        assertThat(push.value.records.first().photos.first().content_base64).isNotNull()

        // G6
        ownerSync.reconcile(
            WireReconcileRequest(
                push_plans = listOf(
                    WirePlanBundle("p1", "b", "nursing", 99, status = "COMPLETED", linked_record_uuid = "rf"),
                ),
                push_records = listOf(WireRecordBundle("rf", "b", "nursing", 99, linked_plan_uuid = "p1")),
            ),
        )
        assertThat(fake.plans.first().status).isEqualTo("COMPLETED")

        // G7 ACL domain
        val careMember = com.lezi.gf.care.CareService(
            selfMembershipId = { claimed.value.membership_id },
            isOwner = { false },
        )
        careMember.store().putRecord(rec.copy(createdByMembershipId = created.value.membership_id))
        assertThat(careMember.deleteRecord(rec.clientUuid, true)).isInstanceOf(GfResult.Err::class.java)

        // G8
        ownerSync.setTrustedSpki("old")
        assertThat(ownerSync.evaluateSpkiChange("new")).isInstanceOf(GfResult.Err::class.java)

        // G9
        ownerSync.injectMockUpdate(
            com.lezi.gf.syncsession.WireAppUpdate(200, "1.1.0", force = true, min_supported_version_code = 200),
        )
        assertThat(ownerSync.forcedUpdateShell()).isNotNull()

        // G10
        assertThat(ownerSync.exitDevice()).isInstanceOf(GfResult.Ok::class.java)
        familyOwner.clearFamilyLocalData("exit_device")
        assertThat(familyOwner.account().joinState).isEqualTo(JoinState.UNJOINED)
    }

    @Test
    fun versionAndIsolationConstants() {
        assertThat(ProductVersion.NAME).isEqualTo("1.0.0")
        assertThat(ProductVersion.APPLICATION_ID).isEqualTo("com.lezi.babylog.gf")
        assertThat(ProductVersion.DEFAULT_PORT).isEqualTo(18765)
    }
}
