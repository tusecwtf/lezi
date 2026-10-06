package com.lezi.babylog.feature.log

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.SettingsLocal
import com.lezi.babylog.designsystem.LeziHaptics
import com.lezi.babylog.designsystem.LeziTheme
import com.lezi.babylog.designsystem.LocalLeziHaptics
import com.lezi.babylog.feature.log.composer.PendingNextFeed
import com.lezi.babylog.feature.log.composer.RecordComposerPostSavePresentation
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Outcome haptic points (0.5.4 ticket 15, spec §M3): the list-level delete confirmation and
 * the Composer save-success presentation each emit exactly one confirm haptic on success and
 * stay silent otherwise. LayoutMotionHapticsDeviceTest is the recording-haptics precedent.
 *
 * Not run on this machine (no device attached); compiled against the androidTest source set.
 */
@RunWith(AndroidJUnit4::class)
class OutcomeHapticsDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun listDeleteConfirmSuccessEmitsExactlyOneConfirmHaptic() {
        val haptics = RecordingLeziHaptics()
        var dismissed = false
        composeRule.setContent {
            CompositionLocalProvider(LocalLeziHaptics provides haptics) {
                LeziTheme(visualStyle = "warm") {
                    LogDialogHost(
                        showCustomManage = false,
                        customItems = emptyList(),
                        onDismissCustomManage = {},
                        onAddCustomItem = { _, _, _ -> },
                        onUpdateCustomItem = { _, _ -> },
                        onDeleteCustomItem = { _, _ -> },
                        inLayoutEdit = false,
                        layoutFailure = null,
                        failedLayoutUndo = null,
                        dismissedLayoutFailure = null,
                        layoutExitInProgress = false,
                        onDismissLayoutFailure = {},
                        onRetryLayoutFailure = {},
                        publishChromeRecord = null,
                        lastSyncFailed = false,
                        onDismissPublishChrome = {},
                        onRetryPublishChrome = {},
                        onEditPublishChrome = {},
                        listDeleteTarget = ListDeleteTarget.RecordItem(record()),
                        onDismissListDelete = { dismissed = true },
                        onDeleteCarePlan = { _, done -> done(Result.success("已删除")) },
                        onDeleteRecord = { _, done -> done(Result.success("已删除")) },
                        onMessage = {},
                        showMore = false,
                        settings = SettingsLocal(),
                        onDismissMore = {},
                        onPickMore = {},
                        onLongPressMore = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        assertEquals(0, haptics.confirms)

        composeRule.onNodeWithText("确认删除").performClick()
        composeRule.waitForIdle()

        assertEquals(1, haptics.confirms)
        assertEquals(0, haptics.rejects)
        composeRule.runOnIdle { org.junit.Assert.assertTrue(dismissed) }
    }

    @Test
    fun listDeleteFailureNeverEmitsAHaptic() {
        val haptics = RecordingLeziHaptics()
        var dismissed = false
        composeRule.setContent {
            CompositionLocalProvider(LocalLeziHaptics provides haptics) {
                LeziTheme(visualStyle = "warm") {
                    LogDialogHost(
                        showCustomManage = false,
                        customItems = emptyList(),
                        onDismissCustomManage = {},
                        onAddCustomItem = { _, _, _ -> },
                        onUpdateCustomItem = { _, _ -> },
                        onDeleteCustomItem = { _, _ -> },
                        inLayoutEdit = false,
                        layoutFailure = null,
                        failedLayoutUndo = null,
                        dismissedLayoutFailure = null,
                        layoutExitInProgress = false,
                        onDismissLayoutFailure = {},
                        onRetryLayoutFailure = {},
                        publishChromeRecord = null,
                        lastSyncFailed = false,
                        onDismissPublishChrome = {},
                        onRetryPublishChrome = {},
                        onEditPublishChrome = {},
                        listDeleteTarget = ListDeleteTarget.RecordItem(record()),
                        onDismissListDelete = { dismissed = true },
                        onDeleteCarePlan = { _, done -> done(Result.success("已删除")) },
                        onDeleteRecord = { _, done ->
                            done(Result.failure(IllegalStateException("网络不可用，请重试")))
                        },
                        onMessage = {},
                        showMore = false,
                        settings = SettingsLocal(),
                        onDismissMore = {},
                        onPickMore = {},
                        onLongPressMore = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("确认删除").performClick()
        composeRule.waitForIdle()

        assertEquals(0, haptics.confirms)
        assertEquals(0, haptics.rejects)
        composeRule.runOnIdle { org.junit.Assert.assertFalse(dismissed) }
    }

    @Test
    fun composerFinishMessageEmitsExactlyOneSaveConfirmHaptic() {
        val haptics = RecordingLeziHaptics()
        var consumedRoot = 0
        val presented = mutableListOf<String>()
        var pendingFinishMessage by mutableStateOf<String?>(null)
        composeRule.setContent {
            CompositionLocalProvider(LocalLeziHaptics provides haptics) {
                RecordComposerPostSavePresentation(
                    request = null,
                    pendingNextFeedOffer = null,
                    pendingFinishMessage = pendingFinishMessage,
                    onConsumeRootRequest = { consumedRoot += 1 },
                    onPresentFinish = presented::add,
                )
            }
        }
        composeRule.waitForIdle()
        assertEquals(0, haptics.confirms)

        composeRule.runOnIdle { pendingFinishMessage = "已保存" }
        composeRule.waitForIdle()

        assertEquals(1, haptics.confirms)
        assertEquals(0, haptics.rejects)
        assertEquals(listOf("已保存"), presented)
        assertEquals(1, consumedRoot)

        // Recomposition without a new stage must not re-fire (one haptic per commit).
        composeRule.runOnIdle { pendingFinishMessage = "已保存" }
        composeRule.waitForIdle()
        assertEquals(1, haptics.confirms)
    }

    @Test
    fun composerNextFeedOfferStageConsumesRootWithoutAHaptic() {
        val haptics = RecordingLeziHaptics()
        var consumedRoot = 0
        val presented = mutableListOf<String>()
        composeRule.setContent {
            CompositionLocalProvider(LocalLeziHaptics provides haptics) {
                RecordComposerPostSavePresentation(
                    request = null,
                    pendingNextFeedOffer = PendingNextFeed(
                        babyId = 1L,
                        type = RecordType.NURSING,
                        suggestedAtMillis = 1_800_000L,
                        factMessage = "已保存喂奶记录",
                    ),
                    pendingFinishMessage = null,
                    onConsumeRootRequest = { consumedRoot += 1 },
                    onPresentFinish = presented::add,
                )
            }
        }
        composeRule.waitForIdle()

        // The offer owns the stage; the finish haptic waits for the finish message.
        assertEquals(0, haptics.confirms)
        assertEquals(0, haptics.rejects)
        assertEquals(emptyList<String>(), presented)
        assertEquals(1, consumedRoot)
    }

    private fun record() = Record(
        clientUuid = "outcome-haptics-device-test",
        babyId = 1L,
        type = RecordType.DIARY,
        timestamp = 1_000L,
        updatedAt = 1_000L,
    )

    private class RecordingLeziHaptics : LeziHaptics {
        var confirms = 0
        var rejects = 0

        override fun confirm() {
            confirms += 1
        }

        override fun reject() {
            rejects += 1
        }
    }
}
