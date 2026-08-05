package com.lezi.gf.care

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Day / week aggregation — pure rules shared by day view and summary charts.
 * Point facts with timestamp > now are visible on timeline but excluded from totals.
 */
object CareAggregation {
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val json = Json { ignoreUnknownKeys = true }

    fun dayStartMs(timestampMs: Long): Long {
        val date = Instant.ofEpochMilli(timestampMs).atZone(zone).toLocalDate()
        return date.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    fun summarizeDay(
        records: List<CareRecord>,
        dayStartMs: Long,
        nowMs: Long,
    ): DaySummary {
        val dayEnd = dayStartMs + 24L * 60 * 60 * 1000
        val live = records.filter {
            it.deletedAtMs == null &&
                !it.isNonAdoptedFulfill &&
                it.timestampMs >= dayStartMs &&
                it.timestampMs < dayEnd &&
                it.timestampMs <= nowMs
        }
        var milk = 0
        var nursing = 0
        var sleepMin = 0
        var pee = 0
        var poop = 0
        for (r in live) {
            when (r.typeKey) {
                RecordType.FORMULA.key, RecordType.PUMPED_FEED.key -> {
                    milk += payloadInt(r, "amount_ml")
                }
                RecordType.NURSING.key -> nursing += 1
                RecordType.SLEEP.key -> sleepMin += sleepDurationMinutes(r)
                RecordType.PEE.key -> pee += 1
                RecordType.POOP.key -> poop += 1
                RecordType.BOTH_DIAPER.key -> {
                    pee += 1
                    poop += 1
                }
            }
        }
        return DaySummary(
            dayStartMs = dayStartMs,
            milkMl = milk,
            nursingCount = nursing,
            sleepMinutes = sleepMin,
            peeCount = pee,
            poopCount = poop,
        )
    }

    fun weekModule(
        records: List<CareRecord>,
        weekStartMs: Long,
        nowMs: Long,
    ): List<DaySummary> {
        return (0 until 7).map { offset ->
            val day = weekStartMs + offset * 24L * 60 * 60 * 1000
            summarizeDay(records, day, nowMs)
        }
    }

    fun timelineForDay(
        records: List<CareRecord>,
        dayStartMs: Long,
        newestFirst: Boolean = true,
    ): List<CareRecord> {
        val dayEnd = dayStartMs + 24L * 60 * 60 * 1000
        val list = records.filter {
            it.deletedAtMs == null &&
                !it.isNonAdoptedFulfill &&
                it.timestampMs >= dayStartMs &&
                it.timestampMs < dayEnd
        }
        return if (newestFirst) list.sortedByDescending { it.timestampMs }
        else list.sortedBy { it.timestampMs }
    }

    private fun payloadInt(r: CareRecord, key: String): Int {
        return try {
            val obj = json.parseToJsonElement(r.payloadJson.ifBlank { "{}" }).jsonObject
            obj[key]?.jsonPrimitive?.intOrNull ?: 0
        } catch (_: Exception) {
            0
        }
    }

    /** Prefer duration_minutes; else derive from start_ms/end_ms. Open sleep contributes 0. */
    fun sleepDurationMinutes(r: CareRecord): Int {
        return try {
            val obj = json.parseToJsonElement(r.payloadJson.ifBlank { "{}" }).jsonObject
            val open = obj["open"]?.jsonPrimitive?.booleanOrNull
            if (open == true) return 0
            val explicit = obj["duration_minutes"]?.jsonPrimitive?.intOrNull
            if (explicit != null && explicit > 0) return explicit
            val start = obj["start_ms"]?.jsonPrimitive?.longOrNull ?: r.timestampMs
            val end = obj["end_ms"]?.jsonPrimitive?.longOrNull ?: return 0
            ((end - start) / 60_000L).toInt().coerceAtLeast(0)
        } catch (_: Exception) {
            0
        }
    }

    fun localDateOf(ms: Long): LocalDate =
        Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
}
