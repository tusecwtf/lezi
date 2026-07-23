package com.lezi.babylog.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lezi.babylog.core.model.SettingsLocal
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class SettingsDataSource @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SettingsStore {
    override val settings: Flow<SettingsLocal> = dataStore.data.map { prefs ->
        SettingsLocal(
            itemOrderJson = prefs[Keys.ITEM_ORDER] ?: "[]",
            hiddenItems = prefs[Keys.HIDDEN_ITEMS]
                ?.split(',')
                ?.filter { it.isNotBlank() }
                ?.toSet()
                ?: emptySet(),
            actionButtonsJson = prefs[Keys.ACTION_BUTTONS] ?: "{}",
            timerEnabled = prefs[Keys.TIMER_ENABLED] ?: true,
            recordAtStartOrEnd = prefs[Keys.RECORD_AT] ?: "end",
            nursingIntervalMin = prefs[Keys.NURSING_INTERVAL] ?: 180,
            nextFeedAt = prefs[Keys.NEXT_FEED_AT],
            darkMode = prefs[Keys.DARK_MODE] ?: "system",
            visualStyle = prefs[Keys.VISUAL_STYLE] ?: "warm",
            preferredHand = prefs[Keys.PREFERRED_HAND] ?: "right",
            dayCountMode = prefs[Keys.DAY_COUNT_MODE] ?: "full",
            weekStart = prefs[Keys.WEEK_START] ?: 1,
            unitsJson = prefs[Keys.UNITS] ?: "{}",
            amountStepMl = prefs[Keys.AMOUNT_STEP] ?: 5,
            timeStepMin = (prefs[Keys.TIME_STEP] ?: 1).takeIf { it == 1 || it == 5 } ?: 1,
            curveDataset = prefs[Keys.CURVE_DATASET] ?: "default",
            timelineOrder = prefs[Keys.TIMELINE_ORDER] ?: "newest_first",
        )
    }

    override val currentBabyId: Flow<Long?> = dataStore.data.map { prefs ->
        prefs[Keys.CURRENT_BABY_ID]
    }

    override val nursingTimerJson: Flow<String?> = dataStore.data.map { prefs ->
        prefs[Keys.NURSING_TIMER_JSON]
    }

    override suspend fun setCurrentBabyId(id: Long?) {
        dataStore.edit { prefs ->
            if (id == null) prefs.remove(Keys.CURRENT_BABY_ID)
            else prefs[Keys.CURRENT_BABY_ID] = id
        }
    }

    override suspend fun setDarkMode(mode: String) {
        dataStore.edit { it[Keys.DARK_MODE] = mode }
    }

    override suspend fun setVisualStyle(style: String) {
        require(style == "warm" || style == "journal") { "Unknown visual style: $style" }
        dataStore.edit { it[Keys.VISUAL_STYLE] = style }
    }

    override suspend fun setPreferredHand(hand: String) {
        require(hand == "left" || hand == "right") { "Unknown preferred hand: $hand" }
        dataStore.edit { it[Keys.PREFERRED_HAND] = hand }
    }

    override suspend fun setTimerEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.TIMER_ENABLED] = enabled }
    }

    override suspend fun setAmountStepMl(step: Int) {
        dataStore.edit { it[Keys.AMOUNT_STEP] = step }
    }

    override suspend fun setTimeStepMin(step: Int) {
        require(step == 1 || step == 5) { "Time step must be 1 or 5 minutes" }
        dataStore.edit { it[Keys.TIME_STEP] = step }
    }

    override suspend fun setNursingIntervalMin(min: Int) {
        dataStore.edit { it[Keys.NURSING_INTERVAL] = min }
    }

    override suspend fun setRecordAt(startOrEnd: String) {
        dataStore.edit { it[Keys.RECORD_AT] = startOrEnd }
    }

    override suspend fun setNextFeedAt(epochMs: Long?) {
        dataStore.edit { prefs ->
            if (epochMs == null) prefs.remove(Keys.NEXT_FEED_AT)
            else prefs[Keys.NEXT_FEED_AT] = epochMs
        }
    }

    override suspend fun clearNextFeedAt() = setNextFeedAt(null)

    override suspend fun setItemOrderJson(json: String) {
        dataStore.edit { it[Keys.ITEM_ORDER] = json }
    }

    override suspend fun setHiddenItems(items: Set<String>) {
        dataStore.edit { it[Keys.HIDDEN_ITEMS] = items.joinToString(",") }
    }

    override suspend fun setNursingTimerJson(json: String?) {
        dataStore.edit { prefs ->
            if (json.isNullOrBlank()) prefs.remove(Keys.NURSING_TIMER_JSON)
            else prefs[Keys.NURSING_TIMER_JSON] = json
        }
    }

    override suspend fun setWeekStart(day: Int) {
        dataStore.edit { it[Keys.WEEK_START] = day }
    }

    override val showAvgSleep: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.SHOW_AVG_SLEEP] ?: false
    }

    override val comparePrevWeek: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.COMPARE_PREV_WEEK] ?: false
    }

    override suspend fun setShowAvgSleep(enabled: Boolean) {
        dataStore.edit { it[Keys.SHOW_AVG_SLEEP] = enabled }
    }

    override suspend fun setComparePrevWeek(enabled: Boolean) {
        dataStore.edit { it[Keys.COMPARE_PREV_WEEK] = enabled }
    }

    private object Keys {
        val ITEM_ORDER = stringPreferencesKey("item_order_json")
        val HIDDEN_ITEMS = stringPreferencesKey("hidden_items")
        val ACTION_BUTTONS = stringPreferencesKey("action_buttons")
        val TIMER_ENABLED = booleanPreferencesKey("timer_enabled")
        val RECORD_AT = stringPreferencesKey("record_at")
        val NURSING_INTERVAL = intPreferencesKey("nursing_interval_min")
        val NEXT_FEED_AT = longPreferencesKey("next_feed_at")
        val DARK_MODE = stringPreferencesKey("dark_mode")
        val VISUAL_STYLE = stringPreferencesKey("visual_style")
        val PREFERRED_HAND = stringPreferencesKey("preferred_hand")
        val DAY_COUNT_MODE = stringPreferencesKey("day_count_mode")
        val WEEK_START = intPreferencesKey("week_start")
        val UNITS = stringPreferencesKey("units_json")
        val AMOUNT_STEP = intPreferencesKey("amount_step_ml")
        val TIME_STEP = intPreferencesKey("time_step_min")
        val CURVE_DATASET = stringPreferencesKey("curve_dataset")
        val TIMELINE_ORDER = stringPreferencesKey("timeline_order")
        val CURRENT_BABY_ID = longPreferencesKey("current_baby_id")
        val NURSING_TIMER_JSON = stringPreferencesKey("nursing_timer_json")
        val SHOW_AVG_SLEEP = booleanPreferencesKey("show_avg_sleep")
        val COMPARE_PREV_WEEK = booleanPreferencesKey("compare_prev_week")
    }
}
