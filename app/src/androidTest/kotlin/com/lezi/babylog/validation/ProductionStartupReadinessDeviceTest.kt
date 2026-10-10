package com.lezi.babylog.validation

import android.net.ConnectivityManager
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.lezi.babylog.AndroidLocalDataUpgradeEnvironment
import com.lezi.babylog.LeziApp
import com.lezi.babylog.MainActivity
import com.lezi.babylog.core.common.LOCAL_DATA_GATE_MAX_ELAPSED_MILLIS
import com.lezi.babylog.core.common.LocalDataUpgradeBlockReason
import com.lezi.babylog.core.common.LocalDataUpgradeState
import com.lezi.babylog.core.common.validation.StartupBoundaryObservation
import com.lezi.babylog.sync.RealSyncPort
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * US-082 application-APK acceptance. Run this selector alone on API 26 and 35, with
 * -e leziStartupGateAcceptance true, a default network, and fresh disposable debug storage.
 * No HiltTestApplication, replacement module, fake SyncPort, fake gate, or business data reset.
 */
class ProductionStartupReadinessDeviceTest {
    @Test
    fun realApplicationAndDiKeepCheckingBlockedAndStaleGenerationsInactiveUntilReady() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(
            "Requires the dedicated startup runner and fresh disposable storage; not ordinary-suite evidence",
            instrumentation is ProductionStartupTestRunner &&
                InstrumentationRegistry.getArguments().getString("leziStartupGateAcceptance") == "true",
        )
        try {
            runAcceptance(instrumentation)
        } finally {
            // Early setup failures must not strand an inspection worker either.
            ProductionStartupSchedule.close()
        }
    }

    private fun runAcceptance(instrumentation: android.app.Instrumentation) {
        val context = instrumentation.targetContext
        val app = context.applicationContext as LeziApp
        assertEquals(LeziApp::class.java, app.javaClass)
        val component = EntryPointAccessors.fromApplication(
            app, ProductionAcceptanceEntryPoint::class.java,
        )
        assertTrue(component.localDataUpgradeEnvironment() is AndroidLocalDataUpgradeEnvironment)
        val gate = app.localDataGate
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = requireNotNull(manager.activeNetwork) {
            "An actual default network is required; this case must not silently skip"
        }
        // Read the actual callback owned/registered by LeziApp. Never replace the callback,
        // application, Lazy<SyncPort>, environment, or Hilt component.
        val callback = LeziApp::class.java.getDeclaredField("networkCallback").let {
            it.isAccessible = true
            it.get(app) as ConnectivityManager.NetworkCallback
        }
        val marker = File(app.noBackupFilesDir, "local-data-upgrade/contract.properties")
        assertTrue(ProductionStartupSchedule.first.entered.await(10, TimeUnit.SECONDS))
        assertTrue(gate.state.value is LocalDataUpgradeState.Checking)
        assertInactive("before Activity / still Checking")
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            activity.onActivity { assertSame(gate, it.localDataGate) }
            await("real foreground/default-network callback") {
                app.foregroundState.isForeground() && count("network:available") > 0
            }
            // At least one OS default-network callback reached the production registration
            // before any synthetic repeat; deterministic repeats then stress the same owner.
            repeat(8) { callback.onAvailable(network) }
            assertInactive("Checking with default network available")
            cycleActivity(activity, app) {
                repeat(8) { callback.onAvailable(network) }
                assertInactive("Checking after old callback while backgrounded")
            }
            repeat(8) { callback.onAvailable(network) }
            assertInactive("Checking after foreground re-entry")

            // Do not shorten or replace the production watchdog. Its actual failure preserves
            // the blocked state while the first platform read is still stalled.
            await("production watchdog Blocked", LOCAL_DATA_GATE_MAX_ELAPSED_MILLIS + 15_000) {
                gate.state.value is LocalDataUpgradeState.Blocked
            }
            assertEquals(
                LocalDataUpgradeBlockReason.TimedOut,
                (gate.state.value as LocalDataUpgradeState.Blocked).reason,
            )
            repeat(8) { callback.onAvailable(network) }
            assertInactive("Blocked with repeated default-network callbacks")
            assertFalse("Blocked startup must not commit a contract", marker.exists())

            // Real background/foreground starts retry generation 2 through Application.onStart.
            cycleActivity(activity, app) {
                repeat(8) { callback.onAvailable(network) }
                assertInactive("Blocked while backgrounded")
            }
            assertTrue(ProductionStartupSchedule.second.entered.await(10, TimeUnit.SECONDS))
            await("new generation Checking") { gate.state.value is LocalDataUpgradeState.Checking }
            repeat(8) { callback.onAvailable(network) }
            assertInactive("new unready generation")

            val rejectedBefore = count("gate:stale-terminal-rejected")
            ProductionStartupSchedule.first.release.complete(Unit)
            await("old generation's real result rejected") {
                count("gate:stale-terminal-rejected") > rejectedBefore
            }
            assertTrue(gate.state.value is LocalDataUpgradeState.Checking)
            repeat(8) { callback.onAvailable(network) }
            assertInactive("old result and callback cannot activate new Checking generation")
            assertFalse(marker.exists())

            // Only release I/O. Production inspect/verify/commit must themselves reach Ready.
            ProductionStartupSchedule.second.release.complete(Unit)
            await("real gate Ready and production consumers active", 15_000) {
                gate.state.value is LocalDataUpgradeState.Ready &&
                    count("sync:activate") == 1 && count("sync:recovery-start") == 1 &&
                    count("room:open") > 0 &&
                    count("sync:session-collect") == 1 && count("session:collect") > 0 &&
                    count("application:persistent-start") == 1
            }
            assertTrue(marker.isFile)
            assertEquals(1, count("room:construct"))
            assertEquals(1, count("sync:recovery-start"))
            assertTrue(app.syncPort.get() is RealSyncPort)
            assertSame(app.syncPort.get(), app.syncPort.get())
            assertTrue(component.database().isOpen)
            val session = runBlocking { component.syncPreferences().session.first() }
            assertFalse(session.isJoined)
            assertTrue(session.baseUrl.isBlank() && session.serverHost.isBlank())

            repeat(16) { callback.onAvailable(network) }
            cycleActivity(activity, app) { repeat(8) { callback.onAvailable(network) } }
            repeat(16) { callback.onAvailable(network) }
            instrumentation.waitForIdleSync()
            assertEquals("Singleton activation must remain once", 1, count("sync:activate"))
            assertEquals(1, count("sync:session-collect"))
            assertEquals(1, count("sync:recovery-start"))
            assertEquals(1, count("room:construct"))
            assertEquals(1, count("application:persistent-start"))
            assertEquals(0, count("network:business-request"))
            assertEquals(0, count("network:setup-request"))
        }
    }

    private fun cycleActivity(
        activity: ActivityScenario<MainActivity>,
        app: LeziApp,
        whileBackground: () -> Unit,
    ) {
        val stops = count("application:background")
        activity.moveToState(Lifecycle.State.CREATED)
        await("ProcessLifecycleOwner background") {
            count("application:background") > stops && !app.foregroundState.isForeground()
        }
        whileBackground()
        val starts = count("application:foreground")
        activity.moveToState(Lifecycle.State.RESUMED)
        await("ProcessLifecycleOwner foreground") {
            count("application:foreground") > starts && app.foregroundState.isForeground()
        }
    }

    private fun assertInactive(phase: String) {
        val observed = StartupBoundaryObservation.snapshot()
        listOf(
            "sync:activate", "sync:recovery-start", "sync:session-collect", "session:collect",
            "room:construct", "room:open", "application:persistent-start",
            "network:business-request", "network:setup-request",
        ).forEach { boundary ->
            assertEquals("$phase: $boundary; observed=$observed", 0, observed[boundary] ?: 0)
        }
    }

    private fun count(boundary: String): Int = StartupBoundaryObservation.snapshot()[boundary] ?: 0

    private fun await(label: String, timeoutMillis: Long = 10_000, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (!predicate()) {
            check(SystemClock.elapsedRealtime() < deadline) {
                "Timed out: $label; observed=${StartupBoundaryObservation.snapshot()}"
            }
            SystemClock.sleep(20)
        }
    }
}
