package com.lezi.babylog.feature.widget

import android.content.Context
import com.lezi.babylog.core.model.RecordType
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

interface WidgetStateStore {
    fun configurations(): List<WidgetConfiguration>
    fun configuration(widgetId: Int): WidgetConfiguration?
    fun saveConfiguration(configuration: WidgetConfiguration)
    fun snapshot(widgetId: Int): WidgetSummarySnapshot?
    fun saveSnapshot(snapshot: WidgetSummarySnapshot)
    fun remove(widgetId: Int)
}

@Singleton
class SharedPreferencesWidgetStateStore @Inject constructor(
    @ApplicationContext context: Context,
) : WidgetStateStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun configurations(): List<WidgetConfiguration> =
        preferences.getStringSet(CONFIGURED_IDS_KEY, emptySet())
            .orEmpty()
            .mapNotNull(String::toIntOrNull)
            .sorted()
            .mapNotNull(::configuration)

    override fun configuration(widgetId: Int): WidgetConfiguration? =
        preferences.getString(configurationKey(widgetId), null)
            ?.let(WidgetStateCodec::decodeConfiguration)
            ?.takeIf { it.widgetId == widgetId }

    override fun saveConfiguration(configuration: WidgetConfiguration) {
        val configuredIds = preferences.getStringSet(CONFIGURED_IDS_KEY, emptySet())
            .orEmpty()
            .toMutableSet()
            .apply { add(configuration.widgetId.toString()) }
        preferences.edit()
            .putStringSet(CONFIGURED_IDS_KEY, configuredIds)
            .putString(
                configurationKey(configuration.widgetId),
                WidgetStateCodec.encodeConfiguration(configuration),
            )
            .apply()
    }

    override fun snapshot(widgetId: Int): WidgetSummarySnapshot? =
        preferences.getString(snapshotKey(widgetId), null)
            ?.let(WidgetStateCodec::decodeSnapshot)
            ?.takeIf { it.widgetId == widgetId }

    override fun saveSnapshot(snapshot: WidgetSummarySnapshot) {
        preferences.edit()
            .putString(snapshotKey(snapshot.widgetId), WidgetStateCodec.encodeSnapshot(snapshot))
            .apply()
    }

    override fun remove(widgetId: Int) {
        val configuredIds = preferences.getStringSet(CONFIGURED_IDS_KEY, emptySet())
            .orEmpty()
            .toMutableSet()
            .apply { remove(widgetId.toString()) }
        preferences.edit()
            .putStringSet(CONFIGURED_IDS_KEY, configuredIds)
            .remove(configurationKey(widgetId))
            .remove(snapshotKey(widgetId))
            .apply()
    }

    private fun configurationKey(widgetId: Int): String = "configuration.$widgetId"

    private fun snapshotKey(widgetId: Int): String = "snapshot.$widgetId"

    private companion object {
        const val PREFERENCES_NAME = "care_widget_state_v2"
        const val CONFIGURED_IDS_KEY = "configured_widget_ids"
    }
}

internal object WidgetStateCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    fun encodeConfiguration(configuration: WidgetConfiguration): String =
        buildJsonObject {
            put("version", 1)
            put("widgetId", configuration.widgetId)
            put("babyId", configuration.babyId)
            put(
                "quickTypes",
                buildJsonArray {
                    configuration.quickTypes.forEach { add(JsonPrimitive(it.key)) }
                },
            )
        }.toString()

    fun decodeConfiguration(encoded: String): WidgetConfiguration? = runCatching {
        val document = json.parseToJsonElement(encoded).jsonObject
        val widgetId = document.int("widgetId") ?: return null
        val babyId = document.long("babyId") ?: return null
        val quickTypes = document["quickTypes"]
            ?.jsonArrayOrNull()
            .orEmpty()
            .mapNotNull { element ->
                element.jsonPrimitive.contentOrNull?.let(RecordType::fromKey)
            }
            .distinct()
            .take(MAX_WIDGET_QUICK_TYPES)
        if (quickTypes.isEmpty()) return null
        WidgetConfiguration(widgetId, babyId, quickTypes)
    }.getOrNull()

    fun encodeSnapshot(snapshot: WidgetSummarySnapshot): String =
        buildJsonObject {
            put("version", 1)
            put("widgetId", snapshot.widgetId)
            put("babyId", snapshot.babyId)
            put("babyName", snapshot.babyName)
            put("feedMl", snapshot.feedMl)
            put("sleepMinutes", snapshot.sleepMinutes)
            put("peeCount", snapshot.peeCount)
            put("poopCount", snapshot.poopCount)
            snapshot.lastLabel?.let { put("lastLabel", it) }
            put("updatedAtEpochMillis", snapshot.updatedAtEpochMillis)
        }.toString()

    fun decodeSnapshot(encoded: String): WidgetSummarySnapshot? = runCatching {
        val document = json.parseToJsonElement(encoded).jsonObject
        WidgetSummarySnapshot(
            widgetId = document.int("widgetId") ?: return null,
            babyId = document.long("babyId") ?: return null,
            babyName = document.string("babyName") ?: return null,
            feedMl = document.int("feedMl") ?: return null,
            sleepMinutes = document.long("sleepMinutes") ?: return null,
            peeCount = document.int("peeCount") ?: return null,
            poopCount = document.int("poopCount") ?: return null,
            lastLabel = document.string("lastLabel"),
            updatedAtEpochMillis = document.long("updatedAtEpochMillis") ?: return null,
        )
    }.getOrNull()

    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

    private fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun kotlinx.serialization.json.JsonElement.jsonArrayOrNull(): JsonArray? =
        runCatching { jsonArray }.getOrNull()
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class WidgetStateModule {
    @Binds
    abstract fun bindWidgetStateStore(
        implementation: SharedPreferencesWidgetStateStore,
    ): WidgetStateStore
}
