package com.lezi.babylog.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lezi.babylog.core.model.NextFeedPlanOrigin
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
    fun failedPlanKeepsTheFactVisibleAndRetriesTheSameSelectedTime() {
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
        compose.onNodeWithText("记录已保存；下次喂养安排失败，请重试或选择不安排")
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

    private fun setFlow(
        onSchedule: (Long, (Boolean) -> Unit) -> Unit = { _, result -> result(true) },
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
                    onFinishedScheduled = onFinishedScheduled,
                    onFinishedWithoutPlan = onFinishedWithoutPlan,
                )
            }
        }
    }
}
