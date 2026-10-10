package com.lezi.babylog.validation.widget

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.lezi.babylog.core.common.DefaultLocalDataGate
import com.lezi.babylog.core.database.LeziDatabase
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.CreateBabyInput
import com.lezi.babylog.feature.widget.CareWidgetRefreshController
import com.lezi.babylog.feature.widget.DEFAULT_WIDGET_QUICK_TYPES
import com.lezi.babylog.feature.widget.WidgetConfiguration
import com.lezi.babylog.feature.widget.WidgetConfigurationActivity
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.validation.ProductionRoomTransactionLease
import dagger.Lazy
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/** Actual Activity/ViewModel/controller/Room with one counting storage delegate.
 * HiltTestApplication is deliberate; this must not be reported as LeziApp startup proof.
 */
@HiltAndroidTest
class CountingWidgetConfigurationDeviceTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    @Inject lateinit var gate: DefaultLocalDataGate
    @Inject lateinit var careLog: Lazy<CareLog>
    @Inject lateinit var sync: Lazy<SyncPort>
    @Inject lateinit var db: Lazy<LeziDatabase>
    @Inject lateinit var controller: Lazy<CareWidgetRefreshController>
    @Inject lateinit var store: CountingWidgetStateStore

    @Before fun setUp() { hilt.inject() }

    @Test fun failedSaveThenOneRetrySurvivesRealActivityRecreationWithoutDuplicateWrites() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val babyId = runBlocking {
            assertTrue(withTimeout(15_000) { gate.ensureReady() })
            val session = sync.get().sessionPresentation().first()
            assertFalse(session.isJoined)
            assertTrue(session.baseUrl.isBlank() && session.serverHost.isBlank())
            assertTrue(careLog.get().listBabies().isEmpty())
            careLog.get().createBaby(CreateBabyInput("AppGuard-widget-count", birthdayEpochDay = LocalDate.now().minusMonths(3).toEpochDay()))
        }
        val manager = AppWidgetManager.getInstance(context)
        val provider = manager.installedProviders.single {
            it.provider == ComponentName(context.packageName, "com.lezi.babylog.feature.widget.CareWidgetReceiver")
        }
        assertEquals(WidgetConfigurationActivity::class.java.name, provider.configure.className)
        val host = AppWidgetHost(context, 0x57474354)
        val id = host.allocateAppWidgetId()
        assertTrue(id > AppWidgetManager.INVALID_APPWIDGET_ID)
        val expected = WidgetConfiguration(id, babyId, DEFAULT_WIDGET_QUICK_TYPES)
        val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
            .setComponent(provider.configure).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        var primaryFailure: Throwable? = null
        try {
            assertNull(store.configuration(id))
            ActivityScenario.launchActivityForResult<WidgetConfigurationActivity>(intent).use { scenario ->
                awaitText("设置乐记小组件")
                store.rejectNextSave.set(true)
                compose.onNodeWithText("保存小组件").performScrollTo().performClick()
                awaitText("保存失败，请重试")
                assertEquals(listOf(expected), store.attempts.toList())
                assertNull(store.configuration(id))
                assertNull(store.snapshot(id))
                scenario.recreate()
                awaitText("保存失败，请重试")
                assertEquals(listOf(expected), store.attempts.toList())

                ProductionRoomTransactionLease(db.get()).use { lease ->
                    compose.onNodeWithText("保存小组件").performScrollTo().performClick()
                    awaitText("保存中…")
                    assertEquals(listOf(expected, expected), store.attempts.toList())
                    compose.onNodeWithText("保存中…").assertIsNotEnabled().performClick()
                    scenario.recreate()
                    awaitText("保存中…")
                    compose.onNodeWithText("保存中…").assertIsNotEnabled().performClick()
                    assertEquals(listOf(expected, expected), store.attempts.toList())
                    scenario.moveToState(Lifecycle.State.CREATED)
                    lease.releaseAndAwait()
                    compose.waitUntil(15_000) { store.snapshot(id) != null }
                    instrumentation.waitForIdleSync()
                    assertEquals(Lifecycle.State.CREATED, scenario.state)
                    assertEquals(listOf(expected, expected), store.attempts.toList())
                    scenario.moveToState(Lifecycle.State.RESUMED)
                    val result = scenario.result
                    assertEquals(Activity.RESULT_OK, result.resultCode)
                    assertEquals(id, result.resultData.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
                    assertEquals(expected, store.configuration(id))
                    assertEquals(listOf(expected, expected), store.attempts.toList())
                }
            }
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            try {
                runBlocking { controller.get().remove(id) }
            } catch (error: Throwable) {
                if (primaryFailure != null) primaryFailure.addSuppressed(error)
                else {
                    primaryFailure = error
                    throw error
                }
            } finally {
                try {
                    host.deleteAppWidgetId(id)
                } catch (error: Throwable) {
                    if (primaryFailure != null) primaryFailure.addSuppressed(error)
                    else throw error
                }
            }
        }
    }

    private fun awaitText(text: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
}
