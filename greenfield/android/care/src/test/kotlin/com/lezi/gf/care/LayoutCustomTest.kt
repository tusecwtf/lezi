package com.lezi.gf.care

import com.google.common.truth.Truth.assertThat
import com.lezi.gf.kernel.GfResult
import org.junit.Test

/** Ticket 12: custom ≤10, dock 4 absolute, hide set, single-level undo. */
class LayoutCustomTest {

    @Test
    fun customMaxTen_rename_softDelete() {
        val care = CareService()
        repeat(10) { care.addCustomDef("项$it") }
        assertThat(care.addCustomDef("溢出")).isInstanceOf(GfResult.Err::class.java)
        val first = care.liveCustomDefs().first()
        care.renameCustomDef(first.clientUuid, "改名")
        assertThat(care.store().allCustoms().find { it.clientUuid == first.clientUuid }?.title)
            .isEqualTo("改名")
        care.deleteCustomDef(first.clientUuid)
        assertThat(care.liveCustomDefs().map { it.clientUuid }).doesNotContain(first.clientUuid)
        assertThat(care.store().allCustoms().find { it.clientUuid == first.clientUuid }?.deletedAtMs)
            .isNotNull()
    }

    @Test
    fun dockFourSlots_moveAbsolute_singleLevelUndo() {
        val care = CareService()
        care.setDockSlots(listOf("formula", "pee", "sleep", "nursing"))
        care.moveDockSlot(0, 3)
        assertThat(care.store().layout.dockSlots).isEqualTo(
            listOf("pee", "sleep", "nursing", "formula"),
        )
        assertThat(care.canUndoLayout()).isTrue()
        care.undoLayout()
        assertThat(care.store().layout.dockSlots).isEqualTo(
            listOf("formula", "pee", "sleep", "nursing"),
        )
        assertThat(care.canUndoLayout()).isFalse()
        assertThat(care.undoLayout()).isNull()
    }

    @Test
    fun hideClearsDockSlot_unhideRestoresCatalogOnly() {
        val care = CareService()
        care.setDockSlots(listOf("formula", "pee", null, null))
        care.hideType("formula")
        assertThat(care.store().layout.dockSlots[0]).isNull()
        assertThat(care.store().layout.hiddenTypeKeys).contains("formula")
        care.unhideType("formula")
        assertThat(care.store().layout.hiddenTypeKeys).doesNotContain("formula")
        // dock slot stays empty until user re-adds (hide cleared it)
        assertThat(care.store().layout.dockSlots[0]).isNull()
    }

    @Test
    fun replaceAllClearsCustomsSoRemoteDeleteApplies() {
        val store = InMemoryCareStore()
        store.putCustom(CustomItemDef("a", "本地A"))
        store.putCustom(CustomItemDef("b", "本地B"))
        // remote only keeps b (a hard-removed from family set)
        store.replaceAll(
            recordsIn = emptyList(),
            plansIn = emptyList(),
            customsIn = listOf(CustomItemDef("b", "远端B", deletedAtMs = null)),
        )
        assertThat(store.allCustoms().map { it.clientUuid }).containsExactly("b")
        assertThat(store.allCustoms().find { it.clientUuid == "a" }).isNull()
    }

    @Test
    fun replaceAllAppliesSoftDeletedCustom() {
        val store = InMemoryCareStore()
        store.putCustom(CustomItemDef("a", "A"))
        store.replaceAll(
            emptyList(),
            emptyList(),
            listOf(CustomItemDef("a", "A", deletedAtMs = 99L)),
        )
        assertThat(store.allCustoms().single().deletedAtMs).isEqualTo(99L)
    }
}
