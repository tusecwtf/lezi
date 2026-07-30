package com.lezi.babylog.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lezi.babylog.core.model.DEVICE_LAYOUT_SNAPSHOT_VERSION
import com.lezi.babylog.core.model.DEFAULT_QUICK_RECORD_SLOTS
import com.lezi.babylog.core.model.DeviceLayoutSnapshot
import com.lezi.babylog.core.model.QUICK_RECORD_SLOT_COUNT
import com.lezi.babylog.core.model.SettingsLocal
import com.lezi.babylog.core.model.normalizeDeviceLayoutSnapshot
import com.lezi.babylog.core.model.normalizeQuickRecordSlots
import com.lezi.babylog.core.model.requireCurrentDeviceLayoutVersion
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

@Singleton
class SettingsDataSource @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SettingsStore {
    override val settings: Flow<SettingsLocal> = dataStore.data.map { prefs ->
        val layout = readDeviceLayoutSnapshot(prefs)
        SettingsLocal(
            deviceLayoutSnapshotVersion = layout.version,
            itemOrderJson = layout.itemOrderJson,
            categoryOrderJson = layout.categoryOrderJson,
            hiddenItems = layout.hiddenItems,
            quickRecordSlots = layout.quickRecordSlots,
            timerEnabled = prefs[Keys.TIMER_ENABLED] ?: true,
            recordAtStartOrEnd = prefs[Keys.RECORD_AT] ?: "end",
            nursingIntervalMin = prefs[Keys.NURSING_INTERVAL] ?: 180,
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
            layoutDragGuidanceCompleted = prefs[Keys.LAYOUT_DRAG_GUIDANCE_COMPLETED] ?: false,
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

    override suspend fun setDeviceLayoutSnapshot(snapshot: DeviceLayoutSnapshot) {
        val normalized = normalizeDeviceLayoutSnapshot(snapshot)
        requireCurrentDeviceLayoutVersion(normalized)
        dataStore.edit { prefs ->
            writeDeviceLayoutSnapshot(prefs, normalized)
        }
    }

    override suspend fun markLayoutDragGuidanceCompleted() {
        dataStore.edit { prefs ->
            if (prefs[Keys.LAYOUT_DRAG_GUIDANCE_COMPLETED] != true) {
                prefs[Keys.LAYOUT_DRAG_GUIDANCE_COMPLETED] = true
            }
        }
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

    override suspend fun setSystemCalendarConfiguration(
        calendarId: String?,
        disclosureLevel: Int?,
    ) {
        val normalizedId = calendarId?.trim()?.takeIf(String::isNotEmpty)
        dataStore.edit { prefs ->
            if (normalizedId == null) {
                prefs.remove(Keys.SYSTEM_CALENDAR_ID)
                prefs[Keys.SYSTEM_CALENDAR_ENABLED] = false
            } else {
                prefs[Keys.SYSTEM_CALENDAR_ID] = normalizedId
                prefs[Keys.SYSTEM_CALENDAR_ENABLED] = true
                disclosureLevel?.let {
                    prefs[Keys.SYSTEM_CALENDAR_DISCLOSURE] = it.coerceIn(1, 3)
                }
            }
        }
    }

    override suspend fun setSystemCalendarEventMapJson(json: String) {
        dataStore.edit { it[Keys.SYSTEM_CALENDAR_EVENT_MAP] = json.ifBlank { "{}" } }
    }

    override suspend fun captureLocalClearSettings(): LocalClearSettingsSnapshot {
        val prefs = dataStore.data.first()
        val systemCalendarProjections = decodeSystemCalendarEventMap(
            prefs[Keys.SYSTEM_CALENDAR_EVENT_MAP] ?: "{}",
        )
        return LocalClearSettingsSnapshot(
            currentBabyId = prefs[Keys.CURRENT_BABY_ID],
            systemCalendarProjections = systemCalendarProjections,
        )
    }

    override suspend fun finishLocalClearSettings(
        snapshot: LocalClearSettingsSnapshot,
        clearCurrentBabyId: Boolean,
    ) {
        dataStore.edit { prefs ->
            if (
                clearCurrentBabyId &&
                prefs[Keys.CURRENT_BABY_ID] == snapshot.currentBabyId
            ) {
                prefs.remove(Keys.CURRENT_BABY_ID)
            }
            val currentMap = decodeSystemCalendarEventMap(
                prefs[Keys.SYSTEM_CALENDAR_EVENT_MAP] ?: "{}",
            )
            val retainedMap = currentMap.filter { (clientUuid, eventId) ->
                snapshot.systemCalendarProjections[clientUuid] != eventId
            }
            prefs[Keys.SYSTEM_CALENDAR_EVENT_MAP] = encodeSystemCalendarEventMap(retainedMap)
        }
    }

    private object Keys {
        val ITEM_ORDER = stringPreferencesKey("item_order_json")
        val CATEGORY_ORDER = stringPreferencesKey("category_order_json")
        val HIDDEN_ITEMS = stringPreferencesKey("hidden_items")
        /** Comma-separated catalog keys; empty segments keep empty slots. Absent → defaults. */
        val QUICK_RECORD_SLOTS = stringPreferencesKey("quick_record_slots")
        /** Authoritative versioned layout epoch; legacy fields above are atomic mirrors only. */
        val DEVICE_LAYOUT_SNAPSHOT = stringPreferencesKey("device_layout_snapshot_json")
        val TIMER_ENABLED = booleanPreferencesKey("timer_enabled")
        val RECORD_AT = stringPreferencesKey("record_at")
        val NURSING_INTERVAL = intPreferencesKey("nursing_interval_min")
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
        val LAYOUT_DRAG_GUIDANCE_COMPLETED =
            booleanPreferencesKey("layout_drag_guidance_completed")
    }

    private fun readDeviceLayoutSnapshot(prefs: Preferences): DeviceLayoutSnapshot {
        val legacy = DeviceLayoutSnapshot(
            quickRecordSlots = parseQuickRecordSlots(prefs[Keys.QUICK_RECORD_SLOTS]),
            hiddenItems = prefs[Keys.HIDDEN_ITEMS]
                ?.split(',')
                ?.filter(String::isNotBlank)
                ?.toSet()
                ?: emptySet(),
            itemOrderJson = prefs[Keys.ITEM_ORDER] ?: "[]",
            categoryOrderJson = prefs[Keys.CATEGORY_ORDER] ?: "[]",
        )
        return decodeDeviceLayoutSnapshot(prefs[Keys.DEVICE_LAYOUT_SNAPSHOT], legacy)
    }

    private fun writeDeviceLayoutSnapshot(
        prefs: MutablePreferences,
        snapshot: DeviceLayoutSnapshot,
    ) {
        val normalized = normalizeDeviceLayoutSnapshot(snapshot)
        requireCurrentDeviceLayoutVersion(normalized)
        prefs[Keys.DEVICE_LAYOUT_SNAPSHOT] = encodeDeviceLayoutSnapshot(normalized)
        // Keep a complete legacy mirror in this same atomic edit for downgrade compatibility.
        prefs[Keys.QUICK_RECORD_SLOTS] = encodeQuickRecordSlots(normalized.quickRecordSlots)
        prefs[Keys.HIDDEN_ITEMS] = normalized.hiddenItems.sorted().joinToString(",")
        prefs[Keys.ITEM_ORDER] = normalized.itemOrderJson
        prefs[Keys.CATEGORY_ORDER] = normalized.categoryOrderJson
    }
}

internal fun encodeDeviceLayoutSnapshot(snapshot: DeviceLayoutSnapshot): String {
    val normalized = normalizeDeviceLayoutSnapshot(snapshot)
    requireCurrentDeviceLayoutVersion(normalized)
    return buildJsonObject {
        put("version", JsonPrimitive(normalized.version))
        put(
            "quickRecordSlots",
            buildJsonArray { normalized.quickRecordSlots.forEach { add(JsonPrimitive(it)) } },
        )
        put(
            "hiddenItems",
            buildJsonArray { normalized.hiddenItems.sorted().forEach { add(JsonPrimitive(it)) } },
        )
        put("itemOrderJson", JsonPrimitive(normalized.itemOrderJson))
        put("categoryOrderJson", JsonPrimitive(normalized.categoryOrderJson))
    }.toString()
}

/**
 * Decode all fields shared with the current schema. Future versions remain readable but are
 * deliberately not writable by [SettingsDataSource]. Missing future fields fall back to the
 * intact legacy mirror rather than silently clearing user layout.
 */
internal fun decodeDeviceLayoutSnapshot(
    raw: String?,
    legacy: DeviceLayoutSnapshot,
): DeviceLayoutSnapshot {
    if (raw == null) return normalizeDeviceLayoutSnapshot(legacy)
    val parsed = Json.parseToJsonElement(raw) as? JsonObject
        ?: throw IllegalArgumentException("Device layout snapshot must be a JSON object")
    val version = (parsed["version"] as? JsonPrimitive)?.content?.toIntOrNull()
        ?: throw IllegalArgumentException("Device layout snapshot version is missing")
    require(version > 0) { "Device layout snapshot version must be positive" }
    val future = version > DEVICE_LAYOUT_SNAPSHOT_VERSION

    fun stringValue(name: String, fallback: String): String {
        val element = parsed[name] ?: return if (future) fallback else error("Missing $name")
        val primitive = element as? JsonPrimitive
            ?: throw IllegalArgumentException("Device layout snapshot $name must be a string")
        require(primitive.isString) { "Device layout snapshot $name must be a string" }
        return primitive.content
    }

    fun stringList(name: String, fallback: Collection<String>): List<String> {
        val element = parsed[name] ?: return if (future) fallback.toList() else error("Missing $name")
        val array = element as? JsonArray
            ?: throw IllegalArgumentException("Device layout snapshot $name must be an array")
        return array.map { value ->
            val primitive = value as? JsonPrimitive
                ?: throw IllegalArgumentException("Device layout snapshot $name values must be strings")
            require(primitive.isString) {
                "Device layout snapshot $name values must be strings"
            }
            primitive.content
        }
    }

    return normalizeDeviceLayoutSnapshot(
        DeviceLayoutSnapshot(
            version = version,
            quickRecordSlots = stringList("quickRecordSlots", legacy.quickRecordSlots),
            hiddenItems = stringList("hiddenItems", legacy.hiddenItems).toSet(),
            itemOrderJson = stringValue("itemOrderJson", legacy.itemOrderJson),
            categoryOrderJson = stringValue("categoryOrderJson", legacy.categoryOrderJson),
        ),
    )
}

internal fun decodeSystemCalendarEventMap(raw: String): Map<String, String> {
    val parsed = Json.parseToJsonElement(raw) as? JsonObject
        ?: throw IllegalArgumentException("System calendar event map must be a JSON object")
    return parsed.mapValues { (key, value) ->
        val primitive = value as? JsonPrimitive
        require(primitive != null && primitive.isString && primitive.content.isNotBlank()) {
            "Invalid system calendar event id for $key"
        }
        primitive.content
    }.filterKeys(String::isNotBlank)
}

internal fun encodeSystemCalendarEventMap(map: Map<String, String>): String =
    buildJsonObject {
        map.toSortedMap().forEach { (key, eventId) ->
            put(key, JsonPrimitive(eventId))
        }
    }.toString()

/**
 * Missing preference → first-run defaults (pee/sleep/nursing/formula).
 * Present value is padded/truncated to exactly [QUICK_RECORD_SLOT_COUNT] slots.
 */
internal fun parseQuickRecordSlots(raw: String?): List<String> {
    if (raw == null) return DEFAULT_QUICK_RECORD_SLOTS
    // Empty stored string means "user cleared all"; only null selects first-run defaults.
    val parts = raw.split(',')
    return normalizeQuickRecordSlots(parts)
}

// Canonical implementation lives on core.model so feature docks and store share one pad rule.
internal fun encodeQuickRecordSlots(slots: List<String>): String =
    normalizeQuickRecordSlots(slots).joinToString(",")
