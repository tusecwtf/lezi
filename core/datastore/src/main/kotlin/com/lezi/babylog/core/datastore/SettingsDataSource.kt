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
) {
    val settings: Flow<SettingsLocal> = dataStore.data.map { prefs ->
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
            dayCountMode = prefs[Keys.DAY_COUNT_MODE] ?: "full",
            weekStart = prefs[Keys.WEEK_START] ?: 1,
            unitsJson = prefs[Keys.UNITS] ?: "{}",
            amountStepMl = prefs[Keys.AMOUNT_STEP] ?: 10,
            timeStepMin = prefs[Keys.TIME_STEP] ?: 1,
            curveDataset = prefs[Keys.CURVE_DATASET] ?: "default",
            timelineOrder = prefs[Keys.TIMELINE_ORDER] ?: "newest_first",
        )
    }

    suspend fun setDarkMode(mode: String) {
        dataStore.edit { it[Keys.DARK_MODE] = mode }
    }

    suspend fun setTimerEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.TIMER_ENABLED] = enabled }
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
        val DAY_COUNT_MODE = stringPreferencesKey("day_count_mode")
        val WEEK_START = intPreferencesKey("week_start")
        val UNITS = stringPreferencesKey("units_json")
        val AMOUNT_STEP = intPreferencesKey("amount_step_ml")
        val TIME_STEP = intPreferencesKey("time_step_min")
        val CURVE_DATASET = stringPreferencesKey("curve_dataset")
        val TIMELINE_ORDER = stringPreferencesKey("timeline_order")
    }
}
