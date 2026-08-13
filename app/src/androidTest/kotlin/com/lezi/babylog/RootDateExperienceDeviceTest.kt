package com.lezi.babylog

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lezi.babylog.designsystem.LeziTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RootDateExperienceDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun timelineCommitUpdatesTopBarAndTopBarChangeReturnsThroughSameOwner() {
        val today = LocalDate.of(2026, 8, 8)
        val owner = RootSelectedDateOwner(today, today)
        lateinit var commitTimelineDay: (LocalDate) -> Unit
        compose.setContent {
            val selectedDate by owner.selectedDate.collectAsState()
            commitTimelineDay = { owner.select(it, today) }
            LeziTheme {
                AppHeaderBar(
                    babyName = "测试宝宝",
                    babyAge = "1岁",
                    avatarPath = null,
                    sleeping = false,
                    selectedDate = selectedDate,
                    today = today,
                    canCycleBaby = false,
                    canGoNext = selectedDate.isBefore(today),
                    dark = false,
                    onCycleBaby = {},
                    onJumpSiblingSameDayAge = {},
                    onPreviousDate = { owner.shift(-1, today) },
                    onNextDate = { owner.shift(1, today) },
                    onOpenDatePicker = {},
                    onSearch = {},
                )
            }
        }

        compose.runOnIdle { commitTimelineDay(today.minusDays(1)) }
        compose.onNodeWithText("昨天").assertIsDisplayed()

        compose.onNodeWithContentDescription("前一天").performClick()
        compose.runOnIdle { assertEquals(today.minusDays(2), owner.selectedDate.value) }
        compose.onNodeWithText("8月6日").assertIsDisplayed()
    }
}
