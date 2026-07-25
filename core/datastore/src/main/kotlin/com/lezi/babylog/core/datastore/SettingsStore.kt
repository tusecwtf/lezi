package com.lezi.babylog.core.datastore

import com.lezi.babylog.core.model.SettingsLocal
import kotlinx.coroutines.flow.Flow

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
    suspend fun setCorrectedAgeEnabled(enabled: Boolean)
    suspend fun setNursingIntervalMin(min: Int)
    suspend fun setRecordAt(startOrEnd: String)
    suspend fun setNextFeedAt(epochMs: Long?)
    suspend fun clearNextFeedAt()
    suspend fun setItemOrderJson(json: String)
    suspend fun setHiddenItems(items: Set<String>)
    suspend fun setTimelineOrder(order: String)
    suspend fun setNursingTimerJson(json: String?)
    suspend fun setShowAvgSleep(enabled: Boolean)
    suspend fun setComparePrevWeek(enabled: Boolean)
    val showAvgSleep: kotlinx.coroutines.flow.Flow<Boolean>
    val comparePrevWeek: kotlinx.coroutines.flow.Flow<Boolean>
    suspend fun setWeekStart(day: Int)
}
