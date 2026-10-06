package com.lezi.babylog.designsystem

import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerState
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity

/**
 * Keeps date selection intact at large system fonts without suppressing accessibility scaling.
 * Material's fixed seven-column calendar can overlap at enlarged fonts, so those users receive
 * its full-scale input mode instead; normal fonts retain the visual calendar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeziDatePicker(
    state: DatePickerState,
    modifier: Modifier = Modifier,
) {
    val useInputMode = useAccessibleDateInputMode(LocalDensity.current.fontScale)
    KeepAccessibleDatePickerMode(state, useInputMode)
    DatePicker(
        state = state,
        modifier = modifier,
        showModeToggle = !useInputMode,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeziDatePicker(
    state: DatePickerState,
    title: (@Composable () -> Unit)?,
    headline: (@Composable () -> Unit)?,
    showModeToggle: Boolean,
    modifier: Modifier = Modifier,
) {
    val useInputMode = useAccessibleDateInputMode(LocalDensity.current.fontScale)
    KeepAccessibleDatePickerMode(state, useInputMode)
    DatePicker(
        state = state,
        modifier = modifier,
        title = title,
        headline = headline,
        showModeToggle = showModeToggle && !useInputMode,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeepAccessibleDatePickerMode(
    state: DatePickerState,
    useInputMode: Boolean,
) {
    LaunchedEffect(state, useInputMode) {
        if (useInputMode) state.displayMode = DisplayMode.Input
    }
}

internal fun useAccessibleDateInputMode(fontScale: Float): Boolean = fontScale > 1f
