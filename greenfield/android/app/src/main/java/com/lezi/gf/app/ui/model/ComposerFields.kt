package com.lezi.gf.app.ui.model

import com.lezi.gf.care.DEFAULT_AMOUNT_STEP_ML
import com.lezi.gf.care.DEFAULT_MILK_AMOUNT_ML
import com.lezi.gf.care.RecordType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Type-specific composer field parse/build — shipped pure logic used by UI + tests.
 * Aligns with CareService defaultPayload / validatePayload keys.
 */
object ComposerFields {
    private val json = Json { ignoreUnknownKeys = true }

    data class PeeFields(val amount: Int = 2) // 1小 2中 3大
    data class PoopFields(
        val amount: Int? = null, // 1–4
        val consistency: Int? = null,
        val color: Int? = null,
    )
    /** Sleep session: fall-asleep (open), wake (close open), or backfill start+end. */
    enum class SleepMode { FALL_ASLEEP, WAKE, BACKFILL }

    data class SleepFields(
        val mode: SleepMode = SleepMode.FALL_ASLEEP,
        val startMs: Long = 0,
        val endMs: Long? = null,
        val durationMinutes: Int = 0,
        val open: Boolean = true,
        val anomaly: Boolean = false,
        val isNap: Boolean = false,
    )
    data class MilkFields(
        val amountMl: Int = DEFAULT_MILK_AMOUNT_ML,
        val stepMl: Int = DEFAULT_AMOUNT_STEP_ML,
        /** Optional prep volume (冲调量), formula only — product optional field. */
        val preparedMl: Int? = null,
        /** Optional feed duration minutes (耗时), formula only. */
        val durationMin: Int? = null,
    )

    /** Quick chips around the current/default milk amount (legacy-style shortcuts). */
    fun milkQuickAmountsMl(currentMl: Int = DEFAULT_MILK_AMOUNT_ML): List<Int> {
        val center = if (currentMl > 0) currentMl else DEFAULT_MILK_AMOUNT_ML
        val base = (center / 5) * 5
        return listOf(base - 5, base, base + 5, base + 10)
            .map { it.coerceIn(5, 999) }
            .distinct()
    }
    data class NursingFields(
        val leftMs: Long = 0,
        val rightMs: Long = 0,
        val order: String = "L",
    )
    data class TempFields(val celsius: Double = 36.5)
    data class WeightFields(val grams: Int = 0)
    data class HeightFields(val mm: Int = 0)

    val peeLabels = listOf(1 to "小", 2 to "中", 3 to "大")
    val poopAmountLabels = listOf(1 to "少", 2 to "中", 3 to "多", 4 to "很多")
    val poopConsistencyLabels = listOf(1 to "稀", 2 to "软", 3 to "成形", 4 to "硬")
    val poopColorLabels = listOf(
        0 to "黄", 1 to "绿", 2 to "棕", 3 to "黑", 4 to "红", 5 to "白", 6 to "灰", 7 to "其它",
    )

    fun parsePee(payload: String): PeeFields {
        val o = obj(payload)
        return PeeFields(amount = (o["pee_amount"]?.jsonPrimitive?.intOrNull ?: 2).coerceIn(1, 3))
    }

    fun buildPee(fields: PeeFields): String =
        buildJsonObject { put("pee_amount", fields.amount.coerceIn(1, 3)) }.toString()

    fun parsePoop(payload: String): PoopFields {
        val o = obj(payload)
        return PoopFields(
            amount = intOrNull(o, "poop_amount") ?: intOrNull(o, "stool_amount"),
            consistency = intOrNull(o, "poop_consistency") ?: intOrNull(o, "stool_consistency"),
            color = intOrNull(o, "poop_color") ?: intOrNull(o, "stool_color"),
        )
    }

    fun buildPoop(fields: PoopFields): String =
        buildJsonObject {
            fields.amount?.let { put("poop_amount", it) }
            fields.consistency?.let { put("poop_consistency", it) }
            fields.color?.let { put("poop_color", it) }
        }.toString()

    fun parseSleep(payload: String): SleepFields {
        val o = obj(payload)
        val open = o["open"]?.jsonPrimitive?.booleanOrNull
        val start = o["start_ms"]?.jsonPrimitive?.longOrNull ?: 0L
        val end = o["end_ms"]?.jsonPrimitive?.longOrNull
        val duration = (o["duration_minutes"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0)
        val isOpen = open == true || (open == null && end == null && duration == 0)
        val mode = when {
            isOpen -> SleepMode.FALL_ASLEEP
            else -> SleepMode.BACKFILL
        }
        return SleepFields(
            mode = mode,
            startMs = start,
            endMs = end,
            durationMinutes = if (!isOpen && duration == 0 && end != null && start > 0) {
                ((end - start) / 60_000L).toInt().coerceAtLeast(0)
            } else {
                duration
            },
            open = isOpen,
            anomaly = o["anomaly"]?.jsonPrimitive?.booleanOrNull ?: false,
            isNap = o["is_nap"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }

    fun buildSleep(fields: SleepFields): String {
        val resolved = when (fields.mode) {
            SleepMode.FALL_ASLEEP -> fields.copy(
                open = true,
                endMs = null,
                durationMinutes = 0,
            )
            SleepMode.WAKE, SleepMode.BACKFILL -> {
                val start = fields.startMs
                val end = (fields.endMs ?: start).coerceAtLeast(start)
                val mins = ((end - start) / 60_000L).toInt().coerceAtLeast(0)
                fields.copy(
                    open = false,
                    endMs = end,
                    durationMinutes = mins,
                )
            }
        }
        return buildJsonObject {
            put("start_ms", resolved.startMs)
            resolved.endMs?.let { put("end_ms", it) }
            put("duration_minutes", resolved.durationMinutes.coerceAtLeast(0))
            put("open", resolved.open)
            put("anomaly", resolved.anomaly)
            put("is_nap", resolved.isNap)
        }.toString()
    }

    fun adjustSleepStartMinutes(fields: SleepFields, deltaMinutes: Int): SleepFields {
        val next = (fields.startMs + deltaMinutes * 60_000L).coerceAtLeast(0)
        return recomputeSleepDuration(fields.copy(startMs = next))
    }

    fun adjustSleepEndMinutes(fields: SleepFields, deltaMinutes: Int): SleepFields {
        val baseEnd = fields.endMs ?: fields.startMs
        val next = (baseEnd + deltaMinutes * 60_000L).coerceAtLeast(fields.startMs)
        return recomputeSleepDuration(fields.copy(endMs = next, mode = SleepMode.BACKFILL, open = false))
    }

    fun recomputeSleepDuration(fields: SleepFields): SleepFields {
        if (fields.open || fields.mode == SleepMode.FALL_ASLEEP) {
            return fields.copy(durationMinutes = 0, endMs = null, open = true)
        }
        val end = (fields.endMs ?: fields.startMs).coerceAtLeast(fields.startMs)
        val mins = ((end - fields.startMs) / 60_000L).toInt().coerceAtLeast(0)
        return fields.copy(endMs = end, durationMinutes = mins, open = false)
    }

    fun formatClockMs(ms: Long): String {
        if (ms <= 0) return "--:--"
        val totalSec = (ms / 1000) % 86_400
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        // Use wall-clock from epoch via simple Asia offset not needed for relative labels —
        // UI formats with zone; this is HH:mm of epoch day remainder for tests only.
        return "%02d:%02d".format(h, m)
    }

    /** Caregiver-facing local wall clock — never show raw epoch ms. */
    fun formatWallClockMs(ms: Long): String {
        if (ms <= 0L) return "--:--"
        return try {
            java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA)
                .format(java.util.Date(ms))
        } catch (_: Exception) {
            formatClockMs(ms)
        }
    }

    fun parseMilk(payload: String): MilkFields {
        val o = obj(payload)
        val step = o["amount_step_ml"]?.jsonPrimitive?.intOrNull ?: DEFAULT_AMOUNT_STEP_ML
        return MilkFields(
            amountMl = (o["amount_ml"]?.jsonPrimitive?.intOrNull ?: DEFAULT_MILK_AMOUNT_ML)
                .coerceAtLeast(0),
            stepMl = if (step == 10) 10 else DEFAULT_AMOUNT_STEP_ML,
            preparedMl = o["prepared_ml"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(0),
            durationMin = o["duration_min"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(0),
        )
    }

    fun buildMilk(fields: MilkFields): String =
        buildJsonObject {
            put("amount_ml", fields.amountMl.coerceAtLeast(0))
            put("amount_step_ml", fields.stepMl)
            fields.preparedMl?.let { put("prepared_ml", it.coerceAtLeast(0)) }
            fields.durationMin?.let { put("duration_min", it.coerceAtLeast(0)) }
        }.toString()

    fun stepMilk(fields: MilkFields, deltaSteps: Int): MilkFields {
        val next = (fields.amountMl + deltaSteps * fields.stepMl).coerceAtLeast(0)
        return fields.copy(amountMl = next)
    }

    fun parseNursing(payload: String): NursingFields {
        val o = obj(payload)
        return NursingFields(
            leftMs = (o["left_ms"]?.jsonPrimitive?.longOrNull ?: 0L).coerceAtLeast(0),
            rightMs = (o["right_ms"]?.jsonPrimitive?.longOrNull ?: 0L).coerceAtLeast(0),
            order = o["order"]?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: "L",
        )
    }

    fun buildNursing(fields: NursingFields): String =
        buildJsonObject {
            put("left_ms", fields.leftMs.coerceAtLeast(0))
            put("right_ms", fields.rightMs.coerceAtLeast(0))
            put("order", fields.order)
        }.toString()

    fun parseTemp(payload: String): TempFields {
        val o = obj(payload)
        return TempFields(celsius = o["celsius"]?.jsonPrimitive?.doubleOrNull ?: 36.5)
    }

    fun buildTemp(fields: TempFields): String =
        buildJsonObject { put("celsius", fields.celsius) }.toString()

    fun parseWeight(payload: String): WeightFields {
        val o = obj(payload)
        return WeightFields(grams = (o["grams"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0))
    }

    fun buildWeight(fields: WeightFields): String =
        buildJsonObject { put("grams", fields.grams.coerceAtLeast(0)) }.toString()

    fun parseHeight(payload: String): HeightFields {
        val o = obj(payload)
        return HeightFields(mm = (o["mm"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0))
    }

    fun buildHeight(fields: HeightFields): String =
        buildJsonObject { put("mm", fields.mm.coerceAtLeast(0)) }.toString()

    fun secondaryFieldKeys(type: RecordType): List<String> = when (type) {
        RecordType.PEE -> listOf("pee_amount")
        RecordType.POOP, RecordType.BOTH_DIAPER -> listOf("poop_amount", "poop_consistency", "poop_color")
        RecordType.SLEEP -> listOf("start_ms", "end_ms", "duration_minutes", "open", "anomaly", "is_nap")
        RecordType.NURSING -> listOf("left_ms", "right_ms", "order")
        RecordType.FORMULA ->
            listOf("amount_ml", "amount_step_ml", "prepared_ml", "duration_min")
        RecordType.PUMPED_FEED, RecordType.PUMP_EXPRESS ->
            listOf("amount_ml", "amount_step_ml")
        RecordType.TEMPERATURE -> listOf("celsius")
        RecordType.WEIGHT -> listOf("grams")
        RecordType.HEIGHT -> listOf("mm")
        else -> emptyList()
    }

    fun validateSecondary(type: RecordType, payload: String): String? = when (type) {
        RecordType.PEE -> {
            val a = parsePee(payload).amount
            if (a !in 1..3) "尿量档位须为 1–3" else null
        }
        RecordType.TEMPERATURE -> {
            val c = parseTemp(payload).celsius
            if (c < 30 || c > 45) "体温超出合理范围" else null
        }
        else -> null
    }

    fun formatDurationMs(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val m = totalSec / 60
        val s = totalSec % 60
        return "%d:%02d".format(m, s)
    }

    private fun obj(payload: String): JsonObject =
        try {
            json.parseToJsonElement(payload.ifBlank { "{}" }).jsonObject
        } catch (_: Exception) {
            buildJsonObject { }
        }

    private fun intOrNull(o: JsonObject, key: String): Int? =
        o[key]?.jsonPrimitive?.intOrNull
}
