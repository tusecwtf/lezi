package com.lezi.babylog.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lezi.babylog.core.model.NextFeedPlanOrigin
import com.lezi.babylog.core.model.NextFeedPlanReconciliation
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NextFeedPlanFlowDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_800_000_000_000L
    private val suggestedAt = now + 180 * 60_000L

    @Test
    fun noPlanFinishesThePersistedFactWithoutScheduling() {
        val scheduleCalls = AtomicInteger()
        val finishedWithoutPlan = AtomicBoolean()
        setFlow(
            onSchedule = { _, _ -> scheduleCalls.incrementAndGet() },
            onFinishedWithoutPlan = { finishedWithoutPlan.set(true) },
        )

        compose.onNodeWithText("不安排").performClick()

        assertTrue(finishedWithoutPlan.get())
        assertEquals(0, scheduleCalls.get())
        compose.onNodeWithText("安排下次喂养？").assertDoesNotExist()
    }

    @Test
    fun confirmedAbsentPlanKeepsTheFactVisibleAndRetriesTheSameSelectedTime() {
        val attempts = mutableListOf<Long>()
        val finishedScheduled = AtomicBoolean()
        setFlow(
            onSchedule = { atMillis, onResult ->
                attempts += atMillis
                onResult(attempts.size > 1)
            },
            onFinishedScheduled = { finishedScheduled.set(true) },
        )

        compose.onNodeWithText("确认安排").performClick()
        compose.onNodeWithText("记录已保存；未发现已保存的下次喂养安排，请重试或选择不安排")
            .assertIsDisplayed()
        compose.onNodeWithText("确认安排").performClick()
        compose.onNodeWithText("已安排下次喂养").assertIsDisplayed()
        compose.onNodeWithText("完成").performClick()

        assertEquals(listOf(suggestedAt, suggestedAt), attempts)
        assertTrue(finishedScheduled.get())
    }

    @Test
    fun inFlightScheduleDisablesDuplicateSubmitAndSharedDateClockIsReachable() {
        val scheduleCalls = AtomicInteger()
        var pendingResult: ((Boolean) -> Unit)? = null
        setFlow(
            onSchedule = { _, onResult ->
                scheduleCalls.incrementAndGet()
                pendingResult = onResult
            },
        )

        compose.onNodeWithText("调整时间").performClick()
        compose.onNodeWithText("选择下次喂养时间").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("确认安排").performClick()
        compose.onNodeWithText("正在安排…").assertIsNotEnabled()

        assertEquals(1, scheduleCalls.get())
        compose.runOnUiThread { pendingResult?.invoke(false) }
        compose.onNodeWithText("确认安排").assertIsDisplayed()
    }

    @Test
    fun restoredInFlightScheduleBlocksSkipUntilDurableTruthIsKnown() {
        val restoration = StateRestorationTester(compose)
        var scheduleResult: ((Boolean) -> Unit)? = null
        var reconciliationResult: ((NextFeedPlanReconciliation) -> Unit)? = null
        val finishedWithoutPlan = AtomicBoolean()
        restoration.setContent {
            MaterialTheme {
                LeziNextFeedPlanFlow(
                    flowKey = "restored-device-test",
                    origin = NextFeedPlanOrigin.RecordComposer,
                    factMessage = "记录已保存",
                    suggestedAtMillis = suggestedAt,
                    scheduledMessage = "护理计划已加入乐记日程",
                    minuteStep = 1,
                    timePickerStyle = "dropdown",
                    preferredHand = "right",
                    nowMillis = { now },
                    onSchedule = { _, callback -> scheduleResult = callback },
                    onReconcile = { callback -> reconciliationResult = callback },
                    onFinishedScheduled = {},
                    onFinishedWithoutPlan = { finishedWithoutPlan.set(true) },
                )
            }
        }

        compose.onNodeWithText("确认安排").performClick()
        assertTrue(scheduleResult != null)
        restoration.emulateSavedInstanceStateRestore()

        compose.waitUntil(5_000) { reconciliationResult != null }
        compose.onNodeWithText("正在核对下次喂养").assertIsDisplayed()
        compose.onNodeWithText("不安排").assertDoesNotExist()
        assertTrue(!finishedWithoutPlan.get())

        compose.runOnUiThread {
            reconciliationResult?.invoke(
                NextFeedPlanReconciliation.Found(
                    clientUuid = "next-feed-plan",
                    scheduledAtMillis = suggestedAt + 60_000L,
                ),
            )
        }
        compose.onNodeWithText("已安排下次喂养").assertIsDisplayed()
        assertTrue(!finishedWithoutPlan.get())
    }

    @Test
    fun reconciliationFailureOnlyOffersTruthQueryRetry() {
        val restoration = StateRestorationTester(compose)
        var reconciliationAttempts = 0
        restoration.setContent {
            MaterialTheme {
                LeziNextFeedPlanFlow(
                    flowKey = "reconciliation-retry-test",
                    origin = NextFeedPlanOrigin.NursingTimer,
                    factMessage = "记录已保存",
                    suggestedAtMillis = suggestedAt,
                    scheduledMessage = "护理计划已加入乐记日程",
                    minuteStep = 1,
                    timePickerStyle = "dropdown",
                    preferredHand = "right",
                    nowMillis = { now },
                    onSchedule = { _, _ -> },
                    onReconcile = { callback ->
                        reconciliationAttempts += 1
                        callback(
                            if (reconciliationAttempts == 1) {
                                NextFeedPlanReconciliation.Failed("持久化查询失败")
                            } else {
                                NextFeedPlanReconciliation.Found(
                                    clientUuid = "next-feed-plan",
                                    scheduledAtMillis = suggestedAt,
                                )
                            },
                        )
                    },
                    onFinishedScheduled = {},
                    onFinishedWithoutPlan = {},
                )
            }
        }

        compose.onNodeWithText("确认安排").performClick()
        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("持久化查询失败").assertIsDisplayed()
        compose.onNodeWithText("不安排").assertDoesNotExist()
        compose.onNodeWithText("重新核对").performClick()
        compose.onNodeWithText("已安排下次喂养").assertIsDisplayed()
        assertEquals(2, reconciliationAttempts)
    }

    private fun setFlow(
        onSchedule: (Long, (Boolean) -> Unit) -> Unit = { _, result -> result(true) },
        onReconcile: ((NextFeedPlanReconciliation) -> Unit) -> Unit = { result ->
            result(NextFeedPlanReconciliation.Absent)
        },
        onFinishedScheduled: () -> Unit = {},
        onFinishedWithoutPlan: () -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                LeziNextFeedPlanFlow(
                    flowKey = "device-test",
                    origin = NextFeedPlanOrigin.RecordComposer,
                    factMessage = "记录已保存",
                    suggestedAtMillis = suggestedAt,
                    scheduledMessage = "护理计划已加入乐记日程",
                    minuteStep = 1,
                    timePickerStyle = "dropdown",
                    preferredHand = "right",
                    nowMillis = { now },
                    onSchedule = onSchedule,
                    onReconcile = onReconcile,
                    onFinishedScheduled = onFinishedScheduled,
                    onFinishedWithoutPlan = onFinishedWithoutPlan,
                )
            }
        }
    }
}
