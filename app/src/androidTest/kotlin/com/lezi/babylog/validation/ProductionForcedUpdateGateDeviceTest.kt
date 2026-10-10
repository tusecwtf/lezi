package com.lezi.babylog.validation

import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import com.lezi.babylog.MainActivity
import com.lezi.babylog.core.ui.UiTags
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.SyncSession
import java.util.UUID
import com.lezi.babylog.sync.ForcedAppUpdateState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import dagger.hilt.android.EntryPointAccessors
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** US-056: actual MainActivity → Hilt RootViewModel → LeziRoot → business windows. */
class ProductionForcedUpdateGateDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun composerWindowIsDetachedForEveryInputPathAndDraftReturns() = scenario(Window.Composer)
    @Test fun timerCompletionIsDetachedForEveryInputPathAndDraftReturns() = scenario(Window.Timer)
    @Test fun familyDialogIsDetachedForEveryInputPathAndDraftReturns() = scenario(Window.Family)

    private enum class Window { Composer, Timer, Family }

    private fun scenario(window: Window) {
        val fixture = ProductionAppFixture()
        fixture.seedBaby()
        val diagnostics = ProductionUiDiagnostics(fixture, compose, "force-${window.name}")
        val preferences = EntryPointAccessors.fromApplication(
            fixture.app, ProductionAcceptanceEntryPoint::class.java,
        ).syncPreferences()
        val originalSession = runBlocking { preferences.session.first() }
        // Preserve an existing device-local identifier, but reject any prior family/endpoint state.
        assertEquals(SyncSession(deviceId = originalSession.deviceId), originalSession)
        runBlocking {
            assertNull(preferences.verifiedEndpoint.first())
            assertNull(preferences.pendingMemberLogin.first())
            assertNull(preferences.disasterRestoreCheckpoint.first())
            assertNull(preferences.lastServerHealthyAt.first())
            assertTrue(preferences.familyMemberDirectory.first().isEmpty())
        }
        val syntheticId = UUID.randomUUID().toString()
        val retainedIdentity = SyncSession(
            familyId = "app-guard-family-$syntheticId",
            membershipId = "app-guard-member-$syntheticId",
            deviceId = "app-guard-device-$syntheticId",
            role = FamilyRole.Owner,
            serverHost = "127.0.0.1",
            serverPort = 9,
        )
        val forced = fixture.forceFlow()
        assertNull("Fixture must start without a pre-existing force state", forced.value)
        val automation = fixture.instrumentation.uiAutomation
        val previousInfo = automation.serviceInfo
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        try {
            // No token is ever supplied. This is never joined/pushable and cannot authenticate.
            // The real retained-reauth check returns locally before contacting this loopback origin.
            runBlocking {
                preferences.saveSession(retainedIdentity)
                preferences.clearDeviceCredentialsForReauth()
                val actual = preferences.session.first()
                assertEquals(retainedIdentity.copy(reauthRequired = true), actual)
                assertFalse(actual.isJoined)
            }
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                diagnostics.beforeTeardown(activity) {
                    diagnostics.phase("await actual root and business route")
                    awaitTag(UiTags.ROOT)
                    lateinit var settings: SettingsStore
                    activity.onActivity { settings = it.settingsStore }
                    // The actual nickname field caps input at 20 characters. Use a valid
                    // draft so this test measures retention rather than input truncation.
                    val draft = if (window == Window.Family) "Guard-Family-draft" else "AppGuard-${window.name}-draft"
                    val save = when (window) {
                        Window.Composer -> {
                            // The real dock renders with the LogUiState loading seed; clicks in that
                            // seed return early while baby is null. Wait for its actual empty snapshot.
                            diagnostics.phase("await loaded log snapshot before Composer click")
                            awaitTag("log_records_empty")
                            diagnostics.phase("click real pee dock and await Composer")
                            compose.onNodeWithTag("one_hand_action_pee").performClick()
                            awaitTag("quick_record_confirm_sheet")
                            compose.onNodeWithTag("record_composer_note_field").performScrollTo()
                                .performTextReplacement(draft)
                            compose.onNodeWithTag("quick_record_confirm_save")
                        }
                        Window.Timer -> {
                            diagnostics.phase("await loaded log snapshot before Timer Composer click")
                            awaitTag("log_records_empty")
                            diagnostics.phase("click real nursing dock and await Composer")
                            compose.onNodeWithTag("one_hand_action_nursing").performClick()
                            awaitTag("quick_record_confirm_sheet")
                            compose.onNodeWithText("打开左右计时器").performScrollTo().performClick()
                            awaitText("完成并记录")
                            compose.onNodeWithText("完成并记录").performClick()
                            awaitText("确认母乳记录")
                            compose.onNode(hasText("左侧（分钟）") and hasSetTextAction())
                                .performTextReplacement("1")
                            compose.onNode(hasText("备注（可选）") and hasSetTextAction())
                                .performScrollTo().performTextReplacement(draft)
                            compose.onNodeWithText("确认记录")
                        }
                        Window.Family -> {
                            diagnostics.phase("open actual Family Add Baby dialog")
                            compose.onNode(hasText("账户") and hasClickAction()).performClick()
                            awaitText("添加宝宝")
                            compose.onNode(hasText("添加宝宝") and hasClickAction()).performScrollTo().performClick()
                            compose.onNode(hasText("昵称（不可重复）") and hasSetTextAction())
                                .performScrollTo().performTextReplacement(draft)
                            assertEditableEquals(
                                compose.onNode(hasText("昵称（不可重复）") and hasSetTextAction()),
                                draft,
                            )
                            compose.onNode(hasText("添加") and hasClickAction())
                        }
                    }
                    // Close only the keyboard; Back itself is tested after the force gate arrives.
                    androidx.test.espresso.Espresso.closeSoftKeyboard()
                    compose.waitForIdle()
                    save.assertIsDisplayed().assertIsEnabled()
                    val saveLabel = save.fetchSemanticsNode().config
                        .getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }
                        .joinToString { it.text }
                    assertTrue("Save semantics must contain its label", saveLabel.isNotBlank())
                    val bounds = save.fetchSemanticsNode().boundsInRoot
                    automation.waitForIdle(250, 5_000)
                    val staleSave = findAccessibilityNode(fixture.context.packageName) { node ->
                        node.isClickable && nodeTreeText(node).contains(saveLabel)
                    } ?: error("Real accessibility save target missing before force injection: $saveLabel")
                    val screenBounds = android.graphics.Rect().also(staleSave::getBoundsInScreen)
                    assertTrue(screenBounds.width() > 0 && bounds.width > 0f)
                    val recordsBefore = fixture.records()
                    val babiesBefore = runBlocking { fixture.careLog.listBabies() }
                    val timerBefore = runBlocking { settings.nursingTimerJson.first() }
                    if (window == Window.Timer) assertNotNull("Timer handoff must own a durable session", timerBefore)
                    val sessionBefore = runBlocking { preferences.session.first() }
                    fun assertBlocked() {
                        compose.waitForIdle()
                        compose.onNodeWithTag(UiTags.ROOT).assertDoesNotExist()
                        compose.onNodeWithTag("quick_record_confirm_sheet").assertDoesNotExist()
                        compose.onNode(hasText(draft) and hasSetTextAction()).assertDoesNotExist()
                        assertEquals(recordsBefore, fixture.records())
                        assertEquals(babiesBefore, runBlocking { fixture.careLog.listBabies() })
                        assertNotNull(forced.value)
                        assertEquals(fixture.babyId, runBlocking { fixture.careLog.getCurrentBaby()!!.id })
                        assertEquals(sessionBefore, runBlocking { preferences.session.first() })
                        activity.onActivity { assertFalse(it.isFinishing) }
                    }
                    diagnostics.phase("inject force with edited business window open")
                    forced.value = ForcedAppUpdateState.PackageUnknown
                    awaitTag("forced_app_update_overlay")
                    assertBlocked()

                    // Positive control: an allowed shell operation really runs without clearing this
                    // valid retained force state. Do not confuse it with a business-window bypass.
                    diagnostics.phase("allowed real retry positive control")
                    compose.onNodeWithText("重试检查更新").performClick()
                    awaitText("仍须更新乐记，但暂时无法从家庭服务器获取更新包，请再试「重试检查更新」。")
                    assertBlocked()
                    recordInput(fixture, "allowed shell retry completed and retained force")

                    // Classify the old save coordinate before tapping. Never accidentally launch a
                    // browser/installer/settings action merely because it now occupies the same pixel.
                    diagnostics.phase("retired business coordinate touch")
                    val collision = findAccessibilityNode(fixture.context.packageName) { node ->
                        node.isClickable && !nodeTreeText(node).contains("强制更新乐记") &&
                            android.graphics.Rect().also(node::getBoundsInScreen)
                                .contains(screenBounds.centerX(), screenBounds.centerY())
                    }
                    val collisionLabel = collision?.let(::nodeTreeText).orEmpty()
                    collision?.recycle()
                    val safeToTapOldSave = collisionLabel.isBlank() || SAFE_GATE_ACTIONS.any { collisionLabel.contains(it) }
                    recordInput(fixture, "old save coordinate now overlaps: ${collisionLabel.ifBlank { "gate sink" }}")
                    val touchBounds = if (safeToTapOldSave) screenBounds else {
                        // The original target is an allowed external gate control, so use the gate
                        // sink as the touch boundary probe and preserve this limitation in output.
                        recordInput(fixture, "external gate collision: old coordinate not activated; touching gate sink")
                        val rootBounds = compose.onNodeWithTag("forced_app_update_overlay").fetchSemanticsNode().boundsInRoot
                        android.graphics.Rect(24, (rootBounds.height * 0.12f).toInt(), 26, (rootBounds.height * 0.12f).toInt() + 2)
                    }
                    val now = SystemClock.uptimeMillis()
                    listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
                        MotionEvent.obtain(now, SystemClock.uptimeMillis(), action,
                            touchBounds.exactCenterX(), touchBounds.exactCenterY(), 0).also { event ->
                            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
                        }
                    }
                    assertBlocked()
                    diagnostics.phase("Back while full forced shell is active")
                    fixture.instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                    assertBlocked()
                    returnToFullGateIfNeeded()
                    // Traverse actual hardware focus. Activate only local allowed gate controls or
                    // an inert sink; external gate actions are not ordinary business navigation.
                    diagnostics.phase("hardware focus traversal under force")
                    repeat(8) {
                        fixture.instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_TAB)
                        automation.waitForIdle(100, 5_000)
                        val focused = automation.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                        val focusedText = focused?.let(::nodeTreeText).orEmpty()
                        val ownFocus = focused?.packageName?.toString() == fixture.context.packageName
                        recordInput(fixture, "hardware focus: $focusedText; own package=$ownFocus")
                        if (ownFocus && (focused?.isClickable != true || SAFE_GATE_ACTIONS.any { focusedText.contains(it) })) {
                            fixture.instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                        } else recordInput(fixture, "external/unknown focus activation excluded")
                        focused?.recycle()
                        assertBlocked()
                        returnToFullGateIfNeeded()
                    }
                    // Exercise a stale Android accessibility action, not a captured Compose callback.
                    diagnostics.phase("retired accessibility save action")
                    val staleRefreshed = staleSave.refresh()
                    val staleNow = if (staleRefreshed) nodeTreeText(staleSave) else "retired node"
                    recordInput(fixture, "stale accessibility target now: $staleNow")
                    if (!staleRefreshed || SAFE_GATE_ACTIONS.any { staleNow.contains(it) } || staleNow.contains(saveLabel)) {
                        staleSave.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    } else recordInput(fixture, "reused accessibility id belongs to another gate control; excluded activation")
                    assertBlocked()
                    automation.waitForIdle(250, 5_000)
                    assertNull("Removed business field must not be exposed to accessibility", findAccessibilityNode(
                        fixture.context.packageName,
                    ) { node -> node.isEditable && node.text?.toString() == draft })
                    staleSave.recycle()

                    // The genuine retained-reauth projection allows only bounded session recovery.
                    diagnostics.phase("enter bounded recovery and its offline branch")
                    returnToFullGateIfNeeded()
                    awaitText("重新登录家庭")
                    compose.onNodeWithText("重新登录家庭").performClick()
                    awaitTag("forced_app_update_recovery_banner")
                    assertBlocked()
                    compose.onNodeWithTag(UiTags.ONBOARDING).assertExists()
                    compose.onNodeWithTag(UiTags.ONBOARDING_OFFLINE_MODE).performClick()
                    awaitText("请先完成更新，再添加宝宝")
                    compose.onNode(hasText("昵称（不可重复）") and hasSetTextAction()).assertDoesNotExist()
                    assertBlocked()
                    diagnostics.phase("Back from recovery-only CreateBaby notice")
                    diagnostics.capture(activity, "before-recovery-back")
                    fixture.instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                    assertBlocked()
                    awaitTag("forced_app_update_overlay")
                    compose.onNodeWithTag("forced_app_update_recovery_banner").assertDoesNotExist()
                    diagnostics.phase("recovery Back returned to full force shell with Activity retained")

                    // Removing only the injected condition restores the exact unsaved production draft.
                    diagnostics.phase("clear only injected force and verify exact retained draft")
                    forced.value = null
                    awaitTag(UiTags.ROOT)
                    when (window) {
                        Window.Composer -> {
                            compose.onNodeWithTag("quick_record_confirm_sheet").assertExists()
                            assertEditableEquals(compose.onNodeWithTag("record_composer_note_field"), draft)
                        }
                        Window.Timer -> {
                            compose.onNodeWithText("确认母乳记录").assertExists()
                            assertEditableEquals(compose.onNode(hasText("备注（可选）") and hasSetTextAction()), draft)
                            assertEditableEquals(compose.onNode(hasText("左侧（分钟）") and hasSetTextAction()), "1")
                            assertEquals("Timer identity/ownership must survive", timerBefore,
                                runBlocking { settings.nursingTimerJson.first() })
                        }
                        Window.Family -> {
                            compose.onNode(hasText("添加宝宝") and !hasClickAction()).assertExists()
                            assertEditableEquals(compose.onNode(hasText("昵称（不可重复）") and hasSetTextAction()), draft)
                        }
                    }
                    assertEquals(recordsBefore, fixture.records())
                    assertEquals(babiesBefore, runBlocking { fixture.careLog.listBabies() })
                    assertEquals(fixture.babyId, runBlocking { fixture.careLog.getCurrentBaby()!!.id })
                    assertEquals(sessionBefore, runBlocking { preferences.session.first() })
                }
            }
        } finally {
            forced.value = null
            runBlocking {
                // saveSession(blank endpoint) intentionally does not erase an endpoint; retire
                // only this prechecked synthetic sync config, then restore the prior device id.
                preferences.clearAllLocalSyncConfig()
                preferences.saveSession(originalSession)
                assertEquals(originalSession, preferences.session.first())
                assertNull(preferences.verifiedEndpoint.first())
            }
            automation.serviceInfo = previousInfo
        }
    }

    private fun returnToFullGateIfNeeded() {
        if (compose.onAllNodesWithTag("forced_app_update_recovery_banner").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("返回强制更新").performClick()
            awaitTag("forced_app_update_overlay")
        }
    }

    private fun assertEditableEquals(node: SemanticsNodeInteraction, expected: String) {
        assertEquals(expected, node.fetchSemanticsNode().config[
            androidx.compose.ui.semantics.SemanticsProperties.EditableText
        ].text)
    }

    private fun recordInput(fixture: ProductionAppFixture, description: String) {
        fixture.instrumentation.sendStatus(0, Bundle().apply { putString("appGuardInput", description) })
    }

    private fun awaitText(text: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun awaitTag(tag: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    private fun findAccessibilityNode(packageName: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            for (index in 0 until node.childCount) visit(node.getChild(index))?.let { return it }
            if (node.packageName?.toString() == packageName && predicate(node)) return node
            node.recycle()
            return null
        }
        val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        for (window in automation.windows) visit(window.root)?.let { return it }
        return visit(automation.rootInActiveWindow)
    }

    private fun nodeTreeText(node: AccessibilityNodeInfo): String = buildString {
        append(node.text?.toString().orEmpty())
        append(node.contentDescription?.toString().orEmpty())
        for (index in 0 until node.childCount) node.getChild(index)?.let { child ->
            append(nodeTreeText(child))
            child.recycle()
        }
    }
    private companion object {
        val SAFE_GATE_ACTIONS = setOf("重试检查更新", "重新登录家庭", "返回强制更新")
    }
}
