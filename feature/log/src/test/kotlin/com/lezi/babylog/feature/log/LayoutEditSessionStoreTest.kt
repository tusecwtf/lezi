package com.lezi.babylog.feature.log

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutEditSessionStoreTest {
    @Test
    fun retainedStoreKeepsEditorContextWhileANewColdStartStoreIsIdle() {
        val retained = LayoutEditSessionStore()
        val context = LayoutEditSessionContext(
            babyId = 42L,
            day = LocalDate.of(2026, 7, 30),
        )
        val prefs = DeviceLayoutPrefs(
            quickRecordSlots = listOf("sleep", "pee", "", ""),
            hiddenItems = setOf("bath"),
            itemOrderJson = "[]",
            categoryOrderJson = "[]",
        )

        retained.open(context = context, prefs = prefs)

        assertEquals(context, retained.current?.context)
        assertEquals(prefs, retained.current?.prefs)
        assertNull(LayoutEditSessionStore().current)
    }

    @Test
    fun catalogScrollRestoresTheSameRelativePositionAfterGeometryChanges() {
        val store = LayoutEditSessionStore()
        store.open(
            context = LayoutEditSessionContext(
                babyId = 42L,
                day = LocalDate.of(2026, 7, 30),
            ),
            prefs = emptyPrefs(),
        )
        val position = LayoutCatalogScrollPosition(value = 400, maxValue = 1_000)

        store.updateCatalogScroll(position)

        assertEquals(position, store.current?.catalogScroll)
        assertEquals(200, position.valueFor(maxValue = 500))
        assertEquals(480, position.valueFor(maxValue = 1_200))
    }

    @Test
    fun recreationProjectsTheRetainedDraftWithTheRealSavingAndFailureStates() {
        val store = LayoutEditSessionStore()
        val context = LayoutEditSessionContext(
            babyId = 42L,
            day = LocalDate.of(2026, 7, 30),
        )
        val before = emptyPrefs().copy(
            quickRecordSlots = listOf("pee", "", "", ""),
        )
        val after = before.copy(quickRecordSlots = listOf("sleep", "", "", ""))
        store.open(context, before)
        store.updatePrefs(after, hasSubmittedIntent = true)

        val saving = layoutEditPresentation(
            session = store.current,
            writeState = DeviceLayoutWriteState.Saving(after.toSnapshot()),
        )
        val failedState = DeviceLayoutWriteState.Failed(
            sequence = 7L,
            snapshot = after.toSnapshot(),
            cause = IllegalStateException("disk full"),
        )
        val failed = layoutEditPresentation(store.current, failedState)

        assertEquals(after, saving?.session?.prefs)
        assertTrue(saving?.session?.hasSubmittedIntent == true)
        assertTrue(saving?.writeState is DeviceLayoutWriteState.Saving)
        assertEquals(failedState, failed?.writeState)

        store.close()
        assertNull(layoutEditPresentation(store.current, failedState))
    }

    private fun emptyPrefs(): DeviceLayoutPrefs = DeviceLayoutPrefs(
        quickRecordSlots = listOf("", "", "", ""),
        hiddenItems = emptySet(),
        itemOrderJson = "[]",
        categoryOrderJson = "[]",
    )
}
