package com.lezi.babylog.feature.settings

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.domain.CalendarReminderMutationGuard
import java.lang.reflect.Proxy
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NextFeedSchedulerGuardTest {
    @Test
    fun capturedClearAlarmCancelUsesTheExistingMutationGuardLease() = runBlocking {
        val guard = CalendarReminderMutationGuard()
        val scheduler = NextFeedScheduler(
            settings = unusedSettingsStore(),
            mutationGuard = guard,
            context = InstrumentationRegistry.getInstrumentation().targetContext,
        )

        withTimeout(2_000L) {
            guard.withLock {
                withContext(NonCancellable) {
                    scheduler.cancelCapturedAlarmUnderGuard()
                }
            }
        }
    }

    @Test
    fun deliveredAlarmRequiresAndConsumesItsExactEpoch() = runBlocking {
        val consumed = mutableListOf<String>()
        val store = settingsStore { expectedEpoch ->
            consumed += expectedEpoch
            expectedEpoch == "current-epoch"
        }
        val scheduler = NextFeedScheduler(
            settings = store,
            mutationGuard = CalendarReminderMutationGuard(),
            context = InstrumentationRegistry.getInstrumentation().targetContext,
        )

        assertFalse(scheduler.consumeDeliveredAlarm(null))
        assertFalse(scheduler.consumeDeliveredAlarm("stale-epoch"))
        assertTrue(scheduler.consumeDeliveredAlarm("current-epoch"))
        assertTrue(consumed == listOf("stale-epoch", "current-epoch"))
    }

    @Suppress("UNCHECKED_CAST")
    private fun unusedSettingsStore(): SettingsStore =
        Proxy.newProxyInstance(
            SettingsStore::class.java.classLoader,
            arrayOf(SettingsStore::class.java),
        ) { _, method, _ ->
            error("Unexpected SettingsStore call: ${method.name}")
        } as SettingsStore

    @Suppress("UNCHECKED_CAST")
    private fun settingsStore(
        clearIfEpoch: (String) -> Boolean,
    ): SettingsStore = Proxy.newProxyInstance(
        SettingsStore::class.java.classLoader,
        arrayOf(SettingsStore::class.java),
    ) { _, method, args ->
        when (method.name) {
            "clearNextFeedAtIfEpoch" -> clearIfEpoch(args!![0] as String)
            else -> error("Unexpected SettingsStore call: ${method.name}")
        }
    } as SettingsStore
}
