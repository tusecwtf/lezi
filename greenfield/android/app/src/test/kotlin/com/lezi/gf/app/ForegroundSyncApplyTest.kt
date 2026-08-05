package com.lezi.gf.app

import com.google.common.truth.Truth.assertThat
import com.lezi.gf.app.sync.ForegroundSyncCoordinator
import com.lezi.gf.care.CareRecord
import com.lezi.gf.care.CareService
import com.lezi.gf.care.PhotoRef
import com.lezi.gf.care.PlanStatus
import com.lezi.gf.care.RecordType
import com.lezi.gf.family.FamilyService
import com.lezi.gf.kernel.GfResult
import com.lezi.gf.syncsession.FakeWireTransport
import com.lezi.gf.syncsession.SyncSessionService
import com.lezi.gf.syncsession.WirePhoto
import com.lezi.gf.syncsession.WirePlanBundle
import com.lezi.gf.syncsession.WireRecordBundle
import com.lezi.gf.syncsession.WireReconcileRequest
import org.junit.Test

/**
 * Proves runForegroundSync apply path: remote packages land in CareStore
 * (G4/G5/G6 composition-root observable outcomes).
 */
class ForegroundSyncApplyTest {

    @Test
    fun applyRemoteWritesRecordsPlansPhotosIntoCareStore() {
        val fake = FakeWireTransport()
        val ownerSync = SyncSessionService(http = fake)
        ownerSync.configureEndpoint("https://127.0.0.1:18765")
        val created = ownerSync.createFamily(
            fake.bootstrapSecret,
            "家",
            "爸",
            "A",
        ) as GfResult.Ok

        val careA = CareService(selfMembershipId = { created.value.membership_id }, isOwner = { true })
        val familyA = FamilyService()
        familyA.markJoined(
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
        val baby = (familyA.createOfflineBaby("豆豆") as GfResult.Ok).value

        // A writes record+photo and plan
        val rec = (careA.confirmCreate(
            careA.openComposer(RecordType.DIARY, baby.clientUuid).copy(
                photos = listOf(
                    PhotoRef("m1", "b64:dGVzdA==", byteSize = 4, isDraftOwned = false),
                ),
                dirty = true,
            ),
        ) as GfResult.Ok).value
        val plan = (careA.createPlan(
            baby.clientUuid,
            RecordType.NURSING.key,
            scheduledAtMs = 9_999_999L,
        ) as GfResult.Ok).value

        val coordA = ForegroundSyncCoordinator(careA, familyA, ownerSync)
        val push = coordA.run() as GfResult.Ok
        assertThat(push.value.records).isNotEmpty()
        assertThat(push.value.records.first().photos.first().content_base64).isNotNull()

        // Device B: empty local care, pull via apply
        val careB = CareService(selfMembershipId = { "mem-b" }, isOwner = { false })
        val familyB = FamilyService()
        val syncB = SyncSessionService(http = fake)
        syncB.configureEndpoint("https://127.0.0.1:18765")
        // claim member token from fake after join path
        val pending = syncB.applyJoin("妈", "B") as GfResult.Ok
        ownerSync.approveJoin(pending.value.request_id)
        val claimed = syncB.claimJoin(pending.value.request_id) as GfResult.Ok
        familyB.markJoined(
            claimed.value.family_id,
            claimed.value.family_name,
            claimed.value.membership_id,
            claimed.value.role,
            claimed.value.display_name,
            claimed.value.device_id,
            claimed.value.access_token,
            claimed.value.refresh_token,
            "spki",
        )
        assertThat(careB.store().allRecords()).isEmpty()

        val coordB = ForegroundSyncCoordinator(careB, familyB, syncB)
        val pull = coordB.run() as GfResult.Ok
        // APPLY must put remote into B's CareStore
        assertThat(careB.store().allRecords().map { it.clientUuid }).contains(rec.clientUuid)
        val pulled = careB.store().getRecord(rec.clientUuid)!!
        assertThat(pulled.photos).isNotEmpty()
        assertThat(pulled.photos.first().mediaUuid).isEqualTo("m1")
        assertThat(careB.store().allPlans().map { it.clientUuid }).contains(plan.clientUuid)
        assertThat(pull.value.records.first().photos).isNotEmpty()
    }

    @Test
    fun halfPhotoPackageNotAppliedLocally() {
        val care = CareService()
        val family = FamilyService()
        val fake = FakeWireTransport()
        val sync = SyncSessionService(http = fake)
        // Manually inject incomplete record into fake store then apply
        val incomplete = WireRecordBundle(
            client_uuid = "half",
            baby_client_uuid = "b",
            type_key = "diary",
            timestamp_ms = 1,
            photos = listOf(WirePhoto("m", byte_size = 99, content_base64 = null, sha256 = "")),
        )
        assertThat(ForegroundSyncCoordinator.isAtomicComplete(incomplete.photos)).isFalse()
        val coord = ForegroundSyncCoordinator(care, family, sync)
        coord.applyRemote(
            com.lezi.gf.syncsession.WireReconcileResponse(
                revision = 1,
                records = listOf(incomplete),
            ),
        )
        assertThat(care.store().getRecord("half")).isNull()
    }

    @Test
    fun g6_fulfillOnA_visibleOnB_afterApply() {
        val fake = FakeWireTransport()
        val ownerSync = SyncSessionService(http = fake)
        ownerSync.configureEndpoint("https://127.0.0.1:18765")
        ownerSync.createFamily(fake.bootstrapSecret, "家", "爸", "A")
        // seed completed plan package on wire
        ownerSync.reconcile(
            WireReconcileRequest(
                push_plans = listOf(
                    WirePlanBundle(
                        client_uuid = "p1",
                        baby_client_uuid = "b",
                        type_key = "nursing",
                        scheduled_at_ms = 1,
                        status = "COMPLETED",
                        linked_record_uuid = "r1",
                        confirmed_at_ms = 2,
                    ),
                ),
                push_records = listOf(
                    WireRecordBundle(
                        client_uuid = "r1",
                        baby_client_uuid = "b",
                        type_key = "nursing",
                        timestamp_ms = 2,
                        linked_plan_uuid = "p1",
                    ),
                ),
            ),
        )
        val careB = CareService()
        val familyB = FamilyService()
        familyB.markJoined("fam-1", "家", "mem-b", "member", "妈", "d", "tok-member", "ref", null)
        val syncB = SyncSessionService(http = fake)
        syncB.configureEndpoint("https://127.0.0.1:18765")
        syncB.setAccessToken("tok-member")
        ForegroundSyncCoordinator(careB, familyB, syncB).run()
        assertThat(careB.store().getPlan("p1")?.status).isEqualTo(PlanStatus.COMPLETED)
        assertThat(careB.store().getRecord("r1")?.linkedPlanUuid).isEqualTo("p1")
    }
}
