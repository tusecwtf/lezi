package com.lezi.babylog.feature.settings.calendar

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lezi.babylog.designsystem.LeziTheme
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CalendarSelectedDayTransitionDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun exitingDateKeepsItsOwnSchedulingState() {
        var snapshot by mutableStateOf(CalendarSelectedDaySnapshot(
            LocalDate.of(2026, 1, 1), emptyList(), false, emptySet(), emptyMap()))
        compose.setContent {
            LeziTheme {
                CalendarSelectedDayTransition(snapshot) { day ->
                    Text("${day.date}: ${day.canSchedule}")
                }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { snapshot = snapshot.copy(date = LocalDate.of(2026, 1, 2), canSchedule = true) }
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithText("2026-01-01: false").assertExists()
        compose.onNodeWithText("2026-01-02: true").assertExists()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("2026-01-01: false").assertDoesNotExist()
    }
}
