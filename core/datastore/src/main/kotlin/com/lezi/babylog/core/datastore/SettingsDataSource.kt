package com.lezi.babylog.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lezi.babylog.core.model.DEFAULT_QUICK_RECORD_SLOTS
import com.lezi.babylog.core.model.QUICK_RECORD_SLOT_COUNT
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
            categoryOrderJson = prefs[Keys.CATEGORY_ORDER] ?: "[]",
            hiddenItems = prefs[Keys.HIDDEN_ITEMS]
                ?.split(',')
                ?.filter { it.isNotBlank() }
                ?.toSet()
                ?: emptySet(),
            quickRecordSlots = parseQuickRecordSlots(prefs[Keys.QUICK_RECORD_SLOTS]),
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
            timePickerStyle = (prefs[Keys.TIME_PICKER_STYLE] ?: "dropdown")
                .takeIf { it == "dropdown" || it == "dial" }
                ?: "dropdown",
            infantFeverAdviceEnabled = prefs[Keys.INFANT_FEVER_ADVICE] ?: true,
            curveDataset = prefs[Keys.CURVE_DATASET] ?: "default",
            timelineOrder = prefs[Keys.TIMELINE_ORDER] ?: "newest_first",
            carePlanLocalRemindersEnabled = prefs[Keys.CARE_PLAN_LOCAL_REMINDERS] ?: true,
            systemCalendarEnabled = prefs[Keys.SYSTEM_CALENDAR_ENABLED] ?: false,
            systemCalendarId = prefs[Keys.SYSTEM_CALENDAR_ID],
            systemCalendarDisclosureLevel = (prefs[Keys.SYSTEM_CALENDAR_DISCLOSURE] ?: 2)
                .coerceIn(1, 3),
            systemCalendarEventMapJson = prefs[Keys.SYSTEM_CALENDAR_EVENT_MAP] ?: "{}",
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

    override suspend fun setTimePickerStyle(style: String) {
        require(style == "dropdown" || style == "dial") {
            "Time picker style must be dropdown or dial"
        }
        dataStore.edit { it[Keys.TIME_PICKER_STYLE] = style }
    }

    override suspend fun setInfantFeverAdviceEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.INFANT_FEVER_ADVICE] = enabled }
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

    override suspend fun setCategoryOrderJson(json: String) {
        dataStore.edit { it[Keys.CATEGORY_ORDER] = json }
    }

    override suspend fun setHiddenItems(items: Set<String>) {
        dataStore.edit { it[Keys.HIDDEN_ITEMS] = items.joinToString(",") }
    }

    override suspend fun setQuickRecordSlots(slots: List<String>) {
        val normalized = normalizeQuickRecordSlots(slots)
        dataStore.edit { it[Keys.QUICK_RECORD_SLOTS] = encodeQuickRecordSlots(normalized) }
    }

    override suspend fun setTimelineOrder(order: String) {
        require(order == "newest_first" || order == "oldest_first")
        dataStore.edit { it[Keys.TIMELINE_ORDER] = order }
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

    override suspend fun setCarePlanLocalRemindersEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.CARE_PLAN_LOCAL_REMINDERS] = enabled }
    }

    override suspend fun setSystemCalendarEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.SYSTEM_CALENDAR_ENABLED] = enabled }
    }

    override suspend fun setSystemCalendarId(calendarId: String?) {
        dataStore.edit { prefs ->
            if (calendarId.isNullOrBlank()) prefs.remove(Keys.SYSTEM_CALENDAR_ID)
            else prefs[Keys.SYSTEM_CALENDAR_ID] = calendarId
        }
    }

    override suspend fun setSystemCalendarDisclosureLevel(level: Int) {
        dataStore.edit { it[Keys.SYSTEM_CALENDAR_DISCLOSURE] = level.coerceIn(1, 3) }
    }

    override suspend fun setSystemCalendarEventMapJson(json: String) {
        dataStore.edit { it[Keys.SYSTEM_CALENDAR_EVENT_MAP] = json.ifBlank { "{}" } }
    }

    private object Keys {
        val ITEM_ORDER = stringPreferencesKey("item_order_json")
        val CATEGORY_ORDER = stringPreferencesKey("category_order_json")
        val HIDDEN_ITEMS = stringPreferencesKey("hidden_items")
        /** Comma-separated catalog keys; empty segments keep empty slots. Absent → defaults. */
        val QUICK_RECORD_SLOTS = stringPreferencesKey("quick_record_slots")
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
        val TIME_PICKER_STYLE = stringPreferencesKey("time_picker_style")
        val INFANT_FEVER_ADVICE = booleanPreferencesKey("infant_fever_advice")
        val CURVE_DATASET = stringPreferencesKey("curve_dataset")
        val TIMELINE_ORDER = stringPreferencesKey("timeline_order")
        val CURRENT_BABY_ID = longPreferencesKey("current_baby_id")
        val NURSING_TIMER_JSON = stringPreferencesKey("nursing_timer_json")
        val SHOW_AVG_SLEEP = booleanPreferencesKey("show_avg_sleep")
        val COMPARE_PREV_WEEK = booleanPreferencesKey("compare_prev_week")
        val CARE_PLAN_LOCAL_REMINDERS = booleanPreferencesKey("care_plan_local_reminders")
        val SYSTEM_CALENDAR_ENABLED = booleanPreferencesKey("system_calendar_enabled")
        val SYSTEM_CALENDAR_ID = stringPreferencesKey("system_calendar_id")
        val SYSTEM_CALENDAR_DISCLOSURE = intPreferencesKey("system_calendar_disclosure")
        val SYSTEM_CALENDAR_EVENT_MAP = stringPreferencesKey("system_calendar_event_map")
    }
}

/**
 * Missing preference → first-run defaults (pee/sleep/nursing/formula).
 * Present value is padded/truncated to exactly [QUICK_RECORD_SLOT_COUNT] slots.
 */
internal fun parseQuickRecordSlots(raw: String?): List<String> {
    if (raw == null) return DEFAULT_QUICK_RECORD_SLOTS
    // Empty stored string still means "user cleared all" once written; only null is migrate.
    val parts = raw.split(',')
    return normalizeQuickRecordSlots(parts)
}

internal fun normalizeQuickRecordSlots(slots: List<String>): List<String> {
    val padded = slots.map { it.trim() }.toMutableList()
    while (padded.size < QUICK_RECORD_SLOT_COUNT) padded += ""
    return padded.take(QUICK_RECORD_SLOT_COUNT)
}

internal fun encodeQuickRecordSlots(slots: List<String>): String =
    normalizeQuickRecordSlots(slots).joinToString(",")

