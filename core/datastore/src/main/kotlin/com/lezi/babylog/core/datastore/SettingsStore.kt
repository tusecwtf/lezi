package com.lezi.babylog.core.datastore

import com.lezi.babylog.core.model.DeviceLayoutSnapshot
import com.lezi.babylog.core.model.SettingsLocal
import kotlinx.coroutines.flow.Flow

/** Exact device-local settings epoch captured before a durable Room clear commits. */
data class LocalClearSettingsSnapshot(
    val currentBabyId: Long?,
    val nextFeedAt: Long?,
    /** Exact provider identity captured for each plan UUID; IDs alone are ABA-prone. */
    val systemCalendarProjections: Map<String, String> = emptyMap(),
    /** Stable identity of the next-feed write, so equal timestamps cannot form an ABA. */
    val nextFeedEpoch: String = "",
)

data class LocalClearSettingsFinish(
    /** Safe to cancel the shared PendingIntent because no newer feed epoch exists. */
    val cancelNextFeedAlarm: Boolean,
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
    /** Stores the time as a new alarm epoch and returns that stable epoch identity. */
    suspend fun setNextFeedAt(epochMs: Long?): String
    suspend fun clearNextFeedAt()
    /** Atomically consumes only the alarm epoch carried by a delivered PendingIntent. */
    suspend fun clearNextFeedAtIfEpoch(expectedEpoch: String): Boolean
    /** Persist one complete device-local layout in one atomic store transaction. */
    suspend fun setDeviceLayoutSnapshot(snapshot: DeviceLayoutSnapshot) {
        error("This SettingsStore does not implement atomic device-layout snapshots")
    }
    suspend fun setItemOrderJson(json: String)
    suspend fun setCategoryOrderJson(json: String)
    suspend fun setHiddenItems(items: Set<String>)
    /** Exactly four catalog keys (or empty strings). Values are normalized on write. */
    suspend fun setQuickRecordSlots(slots: List<String>)
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
    ): LocalClearSettingsFinish
}
