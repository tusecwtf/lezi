package com.lezi.babylog

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentSideEffectRehydratorTest {
    @Test
    fun packageReplaceRearmsRemindersAndRefreshesWidgetsThroughOneEntry() = runBlocking {
        val operations = mutableListOf<String>()
        val rehydrator = PersistentSideEffectRehydrator(
            ensureLocalDataReady = {
                operations += "gate"
                true
            },
            rehydrateCarePlanReminders = { operations += "reminders" },
            refreshWidgets = { operations += "widgets" },
        )

        val failures = rehydrator.run()

        assertEquals(listOf("gate", "reminders", "widgets"), operations)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun reminderFailureStillRefreshesWidgetsAndIsReportedForRetryTelemetry() = runBlocking {
        val operations = mutableListOf<String>()
        val rehydrator = PersistentSideEffectRehydrator(
            ensureLocalDataReady = { true },
            rehydrateCarePlanReminders = {
                operations += "reminders"
                error("alarm unavailable")
            },
            refreshWidgets = { operations += "widgets" },
        )

        val failures = rehydrator.run()

        assertEquals(listOf("reminders", "widgets"), operations)
        assertEquals("alarm unavailable", failures.single().message)
    }

    @Test
    fun receiverAcceptsOnlyBootPackageReplaceAndInstallerSuccessRehydrateActions() {
        assertTrue(shouldRehydratePersistentSideEffects("android.intent.action.BOOT_COMPLETED"))
        assertTrue(shouldRehydratePersistentSideEffects("android.intent.action.MY_PACKAGE_REPLACED"))
        assertTrue(
            shouldRehydratePersistentSideEffects(
                "com.lezi.babylog.sync.APP_UPDATE_REHYDRATE",
            ),
        )
        assertFalse(shouldRehydratePersistentSideEffects("android.intent.action.PACKAGE_REPLACED"))
        assertFalse(shouldRehydratePersistentSideEffects(null))
    }
}
