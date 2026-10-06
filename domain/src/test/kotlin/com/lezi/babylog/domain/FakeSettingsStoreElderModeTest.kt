package com.lezi.babylog.domain

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class FakeSettingsStoreElderModeTest {
    @Test
    fun setElderModeIsReadableAndDoesNotRewriteDarkMode() = runBlocking {
        val store = FakeSettingsStore()
        store.setDarkMode("dark")
        store.setElderMode("l3")

        val settings = store.settings.first()
        assertEquals("l3", settings.elderMode)
        assertEquals("dark", settings.darkMode)
    }

    @Test
    fun fakeRejectsIllegalElderMode() = runBlocking {
        val store = FakeSettingsStore()
        try {
            store.setElderMode("huge")
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals("off", store.settings.first().elderMode)
    }
}
