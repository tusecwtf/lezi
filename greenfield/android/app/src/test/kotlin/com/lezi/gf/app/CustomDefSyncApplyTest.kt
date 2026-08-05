package com.lezi.gf.app

import com.google.common.truth.Truth.assertThat
import com.lezi.gf.app.sync.ForegroundSyncCoordinator
import com.lezi.gf.care.CareService
import com.lezi.gf.care.CustomItemDef
import com.lezi.gf.family.FamilyService
import com.lezi.gf.kernel.GfResult
import com.lezi.gf.syncsession.FakeWireTransport
import com.lezi.gf.syncsession.SyncSessionService
import com.lezi.gf.syncsession.WireCustomDef
import com.lezi.gf.syncsession.WireReconcileResponse
import org.junit.Test

/**
 * Ticket 25: custom defs sync to peer via composition-root apply;
 * layout stays local (not on wire).
 */
class CustomDefSyncApplyTest {

    @Test
    fun customDefPushedAndAppliedOnPeer_layoutNotOverwritten() {
        val fake = FakeWireTransport()
        val ownerSync = SyncSessionService(http = fake)
        ownerSync.configureEndpoint("https://127.0.0.1:18765")
        val created = ownerSync.createFamily(fake.bootstrapSecret, "家", "爸", "A") as GfResult.Ok

        val careA = CareService(selfMembershipId = { created.value.membership_id }, isOwner = { true })
        val familyA = FamilyService()
        familyA.markJoined(
            created.value.family_id, created.value.family_name, created.value.membership_id,
            created.value.role, created.value.display_name, created.value.device_id,
            created.value.access_token, created.value.refresh_token, "spki",
        )
        careA.setDockSlots(listOf("formula", "pee", "sleep", "nursing"))
        val layoutA = careA.store().layout
        val def = (careA.addCustomDef("自定义喂药") as GfResult.Ok).value

        val push = ForegroundSyncCoordinator(careA, familyA, ownerSync).run() as GfResult.Ok
        assertThat(push.value.custom_defs.map { it.client_uuid }).contains(def.clientUuid)
        // layout preserved on A
        assertThat(careA.store().layout.dockSlots).isEqualTo(layoutA.dockSlots)

        // Peer B
        val careB = CareService()
        careB.setDockSlots(listOf("nursing", null, null, "diary")) // different local layout
        val layoutBBefore = careB.store().layout
        val familyB = FamilyService()
        val syncB = SyncSessionService(http = fake)
        syncB.configureEndpoint("https://127.0.0.1:18765")
        val req = syncB.applyJoin("妈", "B") as GfResult.Ok
        ownerSync.approveJoin(req.value.request_id)
        val claimed = syncB.claimJoin(req.value.request_id) as GfResult.Ok
        familyB.markJoined(
            claimed.value.family_id, claimed.value.family_name, claimed.value.membership_id,
            claimed.value.role, claimed.value.display_name, claimed.value.device_id,
            claimed.value.access_token, claimed.value.refresh_token, "spki",
        )

        assertThat(careB.liveCustomDefs()).isEmpty()
        ForegroundSyncCoordinator(careB, familyB, syncB).run()
        // def visible on B
        assertThat(careB.liveCustomDefs().map { it.title }).contains("自定义喂药")
        // B layout unchanged (device-local)
        assertThat(careB.store().layout.dockSlots).isEqualTo(layoutBBefore.dockSlots)
    }

    @Test
    fun remoteSoftDeletedCustomReplacesLocalViaApplyRemote() {
        val care = CareService()
        care.store().putCustom(CustomItemDef("c1", "旧名"))
        val family = FamilyService()
        family.markJoined("f", "家", "m", "owner", "爸", "d", "t", "r", null)
        val sync = SyncSessionService(http = FakeWireTransport())
        val coord = ForegroundSyncCoordinator(care, family, sync)
        coord.applyRemote(
            WireReconcileResponse(
                revision = 2,
                custom_defs = listOf(
                    WireCustomDef(
                        client_uuid = "c1",
                        title = "旧名",
                        deleted_at_ms = 12345L,
                        updated_at_ms = 12345L,
                    ),
                ),
            ),
        )
        assertThat(care.liveCustomDefs()).isEmpty()
        assertThat(care.store().allCustoms().single().deletedAtMs).isEqualTo(12345L)
    }

    @Test
    fun remoteHardRemovalOfCustomClearsLocalBecauseReplaceAllClearsMap() {
        val care = CareService()
        care.store().putCustom(CustomItemDef("gone", "消失"))
        care.store().putCustom(CustomItemDef("keep", "保留"))
        val family = FamilyService()
        family.markJoined("f", "家", "m", "owner", "爸", "d", "t", "r", null)
        val coord = ForegroundSyncCoordinator(care, family, SyncSessionService(http = FakeWireTransport()))
        coord.applyRemote(
            WireReconcileResponse(
                revision = 3,
                custom_defs = listOf(WireCustomDef(client_uuid = "keep", title = "保留")),
            ),
        )
        assertThat(care.store().allCustoms().map { it.clientUuid }).containsExactly("keep")
    }
}
