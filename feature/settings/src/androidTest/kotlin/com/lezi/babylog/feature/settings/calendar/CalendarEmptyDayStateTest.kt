package com.lezi.babylog.feature.settings.calendar

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.designsystem.LeziTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CalendarEmptyDayStateTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun browseOnlyDayDoesNotExposeAnEnabledScheduleAction() {
        compose.setContent {
            LeziTheme {
                CalendarEmptyDayState(
                    canScheduleSelectedDate = false,
                    onSchedule = {},
                )
            }
        }

        assertThat(compose.onAllNodesWithText("安排护理").fetchSemanticsNodes()).isEmpty()
        assertThat(
            compose.onAllNodesWithText("该日期仅供查看；护理计划只能安排在未来时刻。")
                .fetchSemanticsNodes(),
        ).hasSize(1)
    }

    @Test
    fun futureEmptyDayKeepsTheScheduleAction() {
        var clicked = false
        compose.setContent {
            LeziTheme {
                CalendarEmptyDayState(
                    canScheduleSelectedDate = true,
                    onSchedule = { clicked = true },
                )
            }
        }

        compose.onNodeWithText("安排护理").performClick()
        compose.runOnIdle {
            assertThat(clicked).isTrue()
        }
    }

    @Test
    fun capabilityExpiryRemovesThePreviouslyAvailableAction() {
        val canSchedule = mutableStateOf(true)
        compose.setContent {
            LeziTheme {
                CalendarEmptyDayState(
                    canScheduleSelectedDate = canSchedule.value,
                    onSchedule = {},
                )
            }
        }
        assertThat(compose.onAllNodesWithText("安排护理").fetchSemanticsNodes()).hasSize(1)

        compose.runOnIdle { canSchedule.value = false }
        compose.waitForIdle()

        assertThat(compose.onAllNodesWithText("安排护理").fetchSemanticsNodes()).isEmpty()
        assertThat(
            compose.onAllNodesWithText("该日期仅供查看；护理计划只能安排在未来时刻。")
                .fetchSemanticsNodes(),
        ).hasSize(1)
    }
}
