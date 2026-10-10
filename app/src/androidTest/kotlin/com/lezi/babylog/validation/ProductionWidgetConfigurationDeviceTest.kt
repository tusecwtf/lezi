package com.lezi.babylog.validation

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.ui.presentation
import com.lezi.babylog.feature.widget.DEFAULT_WIDGET_QUICK_TYPES
import com.lezi.babylog.feature.widget.WidgetConfigurationActivity
import dagger.hilt.android.EntryPointAccessors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real target Application/Hilt/Activity, allocated host ID, Room and widget preferences.
 * Does not grant bind permission or claim launcher placement / RemoteViews rendering.
 */
class ProductionWidgetConfigurationDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun failedConfigureRetainsEditedSelectionAcrossRecreationAndRetryReturnsExactId() = withHost { fixture, owners, id, intent ->
        val initialBaby = fixture.babyId
        val secondBaby = fixture.seedBaby()
        runBlocking { owners.settingsStore().setCurrentBabyId(initialBaby) }
        val nickname = runBlocking { fixture.careLog.listBabies().single { it.id == secondBaby }.nickname }
        ActivityScenario.launchActivityForResult<WidgetConfigurationActivity>(intent).use { activity ->
            awaitText("设置乐记小组件")
            compose.onNodeWithText(nickname).performScrollTo().performClick()
            // Remove one default quick type, so recreation must preserve an edited type list too.
            toggleNursingOff(fixture)
            activity.recreate()
            awaitText("设置乐记小组件")
            assertRadioSelected(nickname)
            assertUnchecked(nursingLabel)
            assertNull(owners.widgetStateStore().configuration(id))

            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            // Fixture leases cover slow emulator/Compose recreation, never product deadlines.
            val exclusion = CoroutineScope(Dispatchers.IO).async {
                owners.localDataMutationEpoch().withClearEpoch {
                    entered.complete(Unit)
                    withTimeout(90_000) { release.await() }
                }
            }
            try {
                runBlocking { withTimeout(10_000) { entered.await() } }
                compose.onNodeWithText("保存小组件").performScrollTo().performClick()
                awaitText("保存失败，请重试")
                activity.onActivity { assertFalse("Failed save must not finish", it.isFinishing) }
                assertEquals(Lifecycle.State.RESUMED, activity.state)
                assertNull("Rejected mutation must not persist", owners.widgetStateStore().configuration(id))
                assertRadioSelected(nickname)
                assertUnchecked(nursingLabel)
                activity.recreate()
                awaitText("保存失败，请重试")
                assertRadioSelected(nickname)
                assertUnchecked(nursingLabel)
            } finally {
                release.complete(Unit)
                runBlocking { exclusion.await() }
            }
            compose.onNodeWithText("保存小组件").performScrollTo().performClick()
            val result = activity.result
            assertEquals(Activity.RESULT_OK, result.resultCode)
            assertEquals(id, result.resultData.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
            val stored = owners.widgetStateStore().configuration(id)!!
            assertEquals(secondBaby, stored.babyId)
            assertEquals(DEFAULT_WIDGET_QUICK_TYPES - RecordType.NURSING, stored.quickTypes)
            assertNotNull(owners.widgetStateStore().snapshot(id))
        }
    }

    @Test fun persistedStoppedSaveWaitsForStartAfterRecreation() = withHost { _, owners, id, intent ->
        ActivityScenario.launchActivityForResult<WidgetConfigurationActivity>(intent).use { activity ->
            awaitText("设置乐记小组件")
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            // Widget summary uses the real @Transaction loadRecordProjection. Occupy its
            // serial transaction executor, without modifying a row or replacing a binding.
            owners.database().transactionExecutor.execute {
                try {
                    owners.database().runInTransaction {
                        entered.countDown()
                        check(release.await(90, TimeUnit.SECONDS)) { "Widget fixture transaction lease timed out, not a product save timeout" }
                    }
                } catch (error: Throwable) {
                    failure.set(error)
                } finally {
                    finished.countDown()
                }
            }
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                compose.onNodeWithText("保存小组件").performScrollTo().performClick()
                awaitText("保存中…")
                compose.onNodeWithText("保存中…").assertIsNotEnabled()
                // Attempt through the actual semantics control again while its command is busy.
                compose.onNodeWithText("保存中…").performClick()
                activity.recreate()
                awaitText("保存中…")
                compose.onNodeWithText("保存中…").performScrollTo().assertIsNotEnabled()
                activity.moveToState(Lifecycle.State.CREATED)
                release.countDown()
                assertTrue(finished.await(10, TimeUnit.SECONDS))
                failure.get()?.let { throw AssertionError("Transaction blocker failed", it) }
                compose.waitUntil(15_000) { owners.widgetStateStore().snapshot(id) != null }
                // Drain main-thread continuations: STARTED result observation must still be off.
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                assertEquals(Lifecycle.State.CREATED, activity.state)
                activity.moveToState(Lifecycle.State.RESUMED)
                val result = activity.result
                assertEquals(Activity.RESULT_OK, result.resultCode)
                assertEquals(id, result.resultData.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
                // Identity assertion only: repeated writes to this key would overwrite it.
                // Disabled UI is covered above; this does not count configure invocations.
                assertEquals(listOf(id), owners.widgetStateStore().configurations().filter { it.widgetId == id }.map { it.widgetId })
            } finally {
                release.countDown()
                assertTrue("Release test transaction before fixture teardown", finished.await(10, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun backCancelsWithoutPersistenceAndRealSettingsControlLightAndDarkSystemBars() = withHost { fixture, owners, id, intent ->
        val settings = owners.settingsStore()
        val original = runBlocking { settings.settings.first() }
        try {
            listOf("light", "dark").forEach { mode ->
                runBlocking {
                    settings.setDarkMode(mode)
                    settings.setVisualStyle("journal")
                    settings.setElderMode("l3")
                }
                ActivityScenario.launchActivityForResult<WidgetConfigurationActivity>(intent).use { activity ->
                    awaitText("设置乐记小组件")
                    compose.waitForIdle()
                    activity.onActivity {
                        val bars = WindowInsetsControllerCompat(it.window, it.window.decorView)
                        assertEquals("Status bar icon mode from real settings", mode == "light", bars.isAppearanceLightStatusBars)
                        assertEquals("Navigation bar icon mode from real settings", mode == "light", bars.isAppearanceLightNavigationBars)
                    }
                    toggleNursingOff(fixture)
                    requireOwnedBackTarget(fixture, activity, mode)
                    fixture.instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                    assertEquals(Activity.RESULT_CANCELED, activity.result.resultCode)
                    assertNull(owners.widgetStateStore().configuration(id))
                    assertNull(owners.widgetStateStore().snapshot(id))
                }
            }
        } finally {
            runBlocking {
                settings.setDarkMode(original.darkMode)
                settings.setVisualStyle(original.visualStyle)
                settings.setElderMode(original.elderMode)
            }
        }
    }

    @Test fun invalidIdReturnsCancelledWithoutChangingWidgetStorage() {
        val fixture = ProductionAppFixture()
        fixture.requireSyntheticSandbox()
        val owners = owners(fixture)
        val before = owners.widgetStateStore().configurations()
        ActivityScenario.launchActivityForResult<WidgetConfigurationActivity>(
            Intent(fixture.context, WidgetConfigurationActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID),
        ).use { activity -> assertEquals(Activity.RESULT_CANCELED, activity.result.resultCode) }
        assertEquals(before, owners.widgetStateStore().configurations())
    }

    private fun withHost(block: (ProductionAppFixture, ProductionAcceptanceEntryPoint, Int, Intent) -> Unit) {
        val fixture = ProductionAppFixture()
        fixture.seedBaby()
        val owners = owners(fixture)
        val manager = AppWidgetManager.getInstance(fixture.context)
        val provider = manager.installedProviders.single {
            it.provider == ComponentName(fixture.context.packageName, "com.lezi.babylog.feature.widget.CareWidgetReceiver")
        }
        assertEquals(WidgetConfigurationActivity::class.java.name, provider.configure.className)
        val host = AppWidgetHost(fixture.context, HOST_ID)
        val id = host.allocateAppWidgetId()
        assertTrue(id > AppWidgetManager.INVALID_APPWIDGET_ID)
        assertNull("Allocated test ID must not already have persisted configuration", owners.widgetStateStore().configuration(id))
        val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
            .setComponent(provider.configure).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        try {
            block(fixture, owners, id, intent)
        } finally {
            // Delete only the ID allocated here. Never delete all hosts or another launcher ID.
            runBlocking { owners.acceptanceWidgetController().remove(id) }
            host.deleteAppWidgetId(id)
        }
    }

    private fun owners(fixture: ProductionAppFixture) = EntryPointAccessors.fromApplication(
        fixture.app, ProductionAcceptanceEntryPoint::class.java,
    )

    private fun requireOwnedBackTarget(
        fixture: ProductionAppFixture,
        scenario: ActivityScenario<WidgetConfigurationActivity>,
        mode: String,
    ) {
        // An earlier API26 run targeted a SystemUI ANR dialog despite the Compose
        // checkbox remaining queryable. Read ownership; never dismiss/activate a
        // foreign window or change the injection API/permissions to get past it.
        val root = fixture.instrumentation.uiAutomation.rootInActiveWindow
        val activePackage: String?
        val activeWindow: String
        try {
            activePackage = root?.packageName?.toString()
            activeWindow = "package=$activePackage windowId=${root?.windowId} " +
                "class=${root?.className}"
        } finally {
            root?.recycle()
        }
        val scenarioState = scenario.state
        var ownedAndFocused = false
        var activityWindow = "Activity unavailable in scenario=$scenarioState"
        if (scenarioState == Lifecycle.State.RESUMED) {
            scenario.onActivity { activity ->
                val resumed = activity.lifecycle.currentState == Lifecycle.State.RESUMED
                val focused = activity.hasWindowFocus() && activity.window.decorView.hasWindowFocus()
                activityWindow = "component=${activity.componentName.flattenToShortString()} " +
                    "lifecycle=${activity.lifecycle.currentState} focused=$focused " +
                    "finishing=${activity.isFinishing} destroyed=${activity.isDestroyed}"
                ownedAndFocused = activity.packageName == fixture.context.packageName &&
                    resumed && focused && !activity.isFinishing && !activity.isDestroyed
            }
        }
        fixture.instrumentation.sendStatus(0, Bundle().apply {
            putString("widgetBackTarget", "mode=$mode scenario=$scenarioState; $activityWindow; activeRoot=$activeWindow")
        })
        assertTrue(
            "BLOCKED: intended system Back has no verified focused owned Widget Activity; " +
                "no Back input sent. $activityWindow; activeRoot=$activeWindow",
            ownedAndFocused && activePackage == fixture.context.packageName,
        )
        // This precondition cannot make window ownership atomic with OS dispatch.
        // Any subsequent SecurityException remains a test failure, never retried.
    }

    // recordTypePresentation's first argument is a tip (左右计时), not the row label.
    // Resolve the same label the production Activity renders; persistence below still
    // independently asserts the expected RecordType list rather than display strings.
    private val nursingLabel get() = RecordType.NURSING.presentation.label

    private fun toggleNursingOff(fixture: ProductionAppFixture) {
        try {
            val node = compose.onNode(hasText(nursingLabel) and isToggleable())
            node.performScrollTo().assertIsOn()
            fixture.instrumentation.sendStatus(0, Bundle().apply {
                putString("widgetSelectionSemanticsBefore", node.printToString())
            })
            node.performClick().assertIsOff()
            fixture.instrumentation.sendStatus(0, Bundle().apply {
                putString("widgetSelectionSemanticsAfter", node.printToString())
            })
        } catch (failure: Throwable) {
            // Capture before ActivityScenario.use tears down the real configuration UI.
            runCatching { compose.onRoot().printToLog("WidgetSelectionFailure") }
                .onFailure(failure::addSuppressed)
            throw failure
        }
    }

    private fun assertRadioSelected(label: String) {
        compose.onNode(hasText(label) and isSelectable()).assertIsSelected()
    }

    private fun assertUnchecked(label: String) {
        compose.onNode(hasText(label) and isToggleable()).assertIsOff()
    }

    private fun awaitText(text: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private companion object { const val HOST_ID = 0x57494447 }
}
