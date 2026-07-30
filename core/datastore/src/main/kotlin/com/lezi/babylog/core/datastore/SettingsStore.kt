package com.lezi.babylog.core.datastore

import com.lezi.babylog.core.model.DeviceLayoutSnapshot
import com.lezi.babylog.core.model.SettingsLocal
import kotlinx.coroutines.flow.Flow

/** Exact device-local settings epoch captured before a durable Room clear commits. */
data class LocalClearSettingsSnapshot(
    val currentBabyId: Long?,
    /** Exact provider identity captured for each plan UUID; IDs alone are ABA-prone. */
    val systemCalendarProjections: Map<String, String> = emptyMap(),
)

/**
 * Preference surface used by domain. [SettingsDataSource] is the production impl;
 * tests can fake this without Android DataStore.
 */
interface SettingsStore {
    val settings: Flow<SettingsLocal>
    val currentBabyId: Flow<Long?>
    val nursingTimerJson: Flow<String?>

    suspend fun setCurrentBabyId(id: Long?)
    suspend fun setDarkMode(mode: String)
    suspend fun setVisualStyle(style: String)
    suspend fun setPreferredHand(hand: String)
    suspend fun setTimerEnabled(enabled: Boolean)
    suspend fun setAmountStepMl(step: Int)
    suspend fun setTimeStepMin(step: Int)
    suspend fun setTimePickerStyle(style: String)
    suspend fun setInfantFeverAdviceEnabled(enabled: Boolean)
    suspend fun setNursingIntervalMin(min: Int)
    suspend fun setRecordAt(startOrEnd: String)
    /** Persist one complete device-local layout in one atomic store transaction. */
    suspend fun setDeviceLayoutSnapshot(snapshot: DeviceLayoutSnapshot) {
        error("This SettingsStore does not implement atomic device-layout snapshots")
    }
    /** Monotonically complete the device-local first layout-drag guidance. */
    suspend fun markLayoutDragGuidanceCompleted() {
        error("This SettingsStore does not implement layout drag guidance completion")
    }
    suspend fun setTimelineOrder(order: String)
    suspend fun setNursingTimerJson(json: String?)
    suspend fun setShowAvgSleep(enabled: Boolean)
    suspend fun setComparePrevWeek(enabled: Boolean)
    val showAvgSleep: kotlinx.coroutines.flow.Flow<Boolean>
    val comparePrevWeek: kotlinx.coroutines.flow.Flow<Boolean>
    suspend fun setWeekStart(day: Int)
    /** Device-local care-plan reminder toggle (default on). */
    suspend fun setCarePlanLocalRemindersEnabled(enabled: Boolean)

    /** Device-local system calendar projection (never family-synced). */
    suspend fun setSystemCalendarEnabled(enabled: Boolean)
    suspend fun setSystemCalendarId(calendarId: String?)
    suspend fun setSystemCalendarDisclosureLevel(level: Int)
    /**
     * Persist the user-confirmed target, enabled state, and disclosure grade as one setting.
     * A null/blank target disables projection while preserving the last disclosure choice.
     */
    suspend fun setSystemCalendarConfiguration(
        calendarId: String?,
        disclosureLevel: Int? = null,
    ) {
        setSystemCalendarId(calendarId)
        disclosureLevel?.let { setSystemCalendarDisclosureLevel(it) }
        setSystemCalendarEnabled(!calendarId.isNullOrBlank())
    }
    suspend fun setSystemCalendarEventMapJson(json: String)

    /** Capture all settings that a local clear may later finalize. */
    suspend fun captureLocalClearSettings(): LocalClearSettingsSnapshot

    /**
     * Remove only values captured by [snapshot]. Values written after the Room
     * commit are a newer epoch and must survive crash-recovery finalization.
     */
    suspend fun finishLocalClearSettings(
        snapshot: LocalClearSettingsSnapshot,
        clearCurrentBabyId: Boolean,
    )
}
