package com.lezi.babylog.feature.log.layout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lezi.babylog.designsystem.LeziTheme
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.timeline.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.composer.*
import com.lezi.babylog.feature.log.photo.*

@RunWith(AndroidJUnit4::class)
class LayoutConfigurationRecreationDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun idleRecreationKeepsEditorContextPrefsAndCatalogPosition() {
        val store = openSession()
        val restoration = StateRestorationTester(compose)
        restoration.setContent { RetainedEditor(store) }

        compose.waitUntil(timeoutMillis = 5_000L) {
            (store.current?.catalogScroll?.maxValue ?: 0) > 0
        }
        compose.onNodeWithTag("layout_edit_item_vaccine")
            .performScrollTo()
            .assertIsDisplayed()
        compose.waitUntil(timeoutMillis = 5_000L) {
            (store.current?.catalogScroll?.value ?: 0) > 0
        }
        val before = checkNotNull(store.current)

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithTag("layout_edit_title").assertIsDisplayed()
        compose.onNodeWithTag("layout_edit_item_vaccine").assertIsDisplayed()
        assertEquals(before.context, store.current?.context)
        assertEquals(before.prefs, store.current?.prefs)
        assertTrue((store.current?.catalogScroll?.value ?: 0) > 0)
    }

    @Test
    fun recreationKeepsRealSavingThenRetryableFailurePresentation() {
        val store = openSession().also {
            it.updatePrefs(checkNotNull(it.current).prefs, hasSubmittedIntent = true)
        }
        val snapshot = checkNotNull(store.current).prefs.toSnapshot()
        var writeState by mutableStateOf<DeviceLayoutWriteState>(
            DeviceLayoutWriteState.Saving(snapshot),
        )
        val restoration = StateRestorationTester(compose)
        restoration.setContent { RetainedEditor(store, writeState) }

        compose.onNodeWithTag("layout_edit_save_feedback").assertTextEquals("正在保存布局")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("layout_edit_save_feedback").assertTextEquals("正在保存布局")

        compose.runOnIdle {
            writeState = DeviceLayoutWriteState.Failed(
                sequence = 5L,
                snapshot = snapshot,
                cause = IllegalStateException("disk full"),
            )
        }

        compose.onNodeWithTag("layout_edit_save_feedback")
            .assertTextEquals("布局没有保存成功，下次进入会用默认布局，可重试")
        assertTrue(store.current != null)
    }

    @Test
    fun themeRecreationCancelsActiveDragWithoutIntentOrSessionLoss() {
        val store = openSession()
        val emitted = CopyOnWriteArrayList<LayoutEditIntent>()
        var style by mutableStateOf("warm")
        compose.setContent {
            RetainedEditor(
                store = store,
                onIntent = emitted::add,
                visualStyle = style,
                configurationSessionKey = style,
            )
        }
        val source = compose.onNodeWithTag("layout_edit_item_pee")
        source.performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100L)
            moveBy(Offset(1f, 1f))
        }
        compose.onNodeWithTag("layout_edit_drag_avatar").assertIsDisplayed()

        compose.runOnIdle { style = "journal" }

        compose.onNodeWithTag("layout_edit_drag_avatar").assertDoesNotExist()
        assertTrue(emitted.isEmpty())
        assertTrue(store.current != null)
    }

    @Test
    fun newColdStartStoreDoesNotOpenTheEditor() {
        val coldStart = LayoutEditSessionStore()
        compose.setContent {
            if (coldStart.current == null) {
                Text("普通记录页", Modifier.testTag("ordinary_log_screen"))
            } else {
                RetainedEditor(coldStart)
            }
        }

        compose.onNodeWithTag("ordinary_log_screen").assertIsDisplayed()
        compose.onNodeWithTag("layout_edit_title").assertDoesNotExist()
    }

    private fun openSession(): LayoutEditSessionStore = LayoutEditSessionStore().also { store ->
        store.open(
            context = LayoutEditSessionContext(
                babyId = 42L,
                day = LocalDate.of(2026, 7, 30),
            ),
            prefs = DeviceLayoutPrefs(
                quickRecordSlots = listOf("pee", "sleep", "nursing", "formula"),
                hiddenItems = emptySet(),
                itemOrderJson = "[]",
                categoryOrderJson = "[]",
            ),
        )
    }

    @androidx.compose.runtime.Composable
    private fun RetainedEditor(
        store: LayoutEditSessionStore,
        writeState: DeviceLayoutWriteState = DeviceLayoutWriteState.Saved(),
        onIntent: (LayoutEditIntent) -> Unit = {},
        visualStyle: String = "warm",
        configurationSessionKey: Any? = null,
    ) {
        val session = store.current ?: return
        LeziTheme(visualStyle = visualStyle) {
            LayoutEditCanvas(
                prefs = session.prefs,
                customItems = emptyList(),
                onIntent = onIntent,
                onDone = {},
                onOpenCustomManage = {},
                writeState = writeState,
                hasSubmittedIntent = session.hasSubmittedIntent,
                initialCatalogScroll = session.catalogScroll,
                onCatalogScrollChanged = store::updateCatalogScroll,
                configurationSessionKey = configurationSessionKey,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
