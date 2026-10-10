package com.lezi.babylog.feature.settings.calendar

import com.lezi.babylog.core.common.productUiError
import com.lezi.babylog.domain.calendar.SystemCalendarConfigurationCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SystemCalendarSetupCommandState(
    val busy: Boolean = false,
    val selection: SystemCalendarSetupSelection? = null,
    val completed: Boolean = false,
    val error: String? = null,
)

/** Retained by each host ViewModel; the dialog only renders the request and its outcome. */
class SystemCalendarSetupCommand(private val configuration: SystemCalendarConfigurationCoordinator) {
    private val mutableState = MutableStateFlow(SystemCalendarSetupCommandState())
    val state = mutableState.asStateFlow()

    suspend fun confirm(selection: SystemCalendarSetupSelection): Boolean = run(selection) {
        configuration.confirm(selection.calendarId, selection.disclosureLevel)
    }

    suspend fun disable(): Boolean = run(null) { configuration.disable() }

    fun consume() {
        if (!mutableState.value.busy) mutableState.value = SystemCalendarSetupCommandState()
    }

    private suspend fun run(selection: SystemCalendarSetupSelection?, action: suspend () -> Unit): Boolean {
        val before = mutableState.value
        if (before.busy || !mutableState.compareAndSet(before,
                SystemCalendarSetupCommandState(busy = true, selection = selection))) return false
        try {
            action()
            mutableState.value = SystemCalendarSetupCommandState(selection = selection, completed = true)
        } catch (cancelled: CancellationException) {
            mutableState.value = SystemCalendarSetupCommandState(selection = selection)
            throw cancelled
        } catch (error: Throwable) {
            mutableState.value = SystemCalendarSetupCommandState(selection = selection,
                error = productUiError(error, "日历设置未全部完成，请保留当前选择并重试"))
        }
        return true
    }
}
