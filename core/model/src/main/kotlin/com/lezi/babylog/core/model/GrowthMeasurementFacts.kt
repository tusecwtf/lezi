package com.lezi.babylog.core.model

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/**
 * Pure growth-measurement interface shared by Composer, Growth and tests.
 *
 * UI units, storage units, chronological age and reference interpolation live
 * together so callers cannot reinterpret a measurement independently.
 */
object GrowthMeasurementFacts {
    fun validationError(type: RecordType, displayValue: Double?): String? = when {
        displayValue == null || !displayValue.isFinite() || displayValue <= 0.0 ->
            "请填写有效数值"
        type == RecordType.WEIGHT && displayValue > 100.0 ->
            "体重需在 0–100 kg 之间"
        type != RecordType.WEIGHT && displayValue > 250.0 ->
            "测量值需在 0–250 cm 之间"
        else -> null
    }

    fun payload(type: RecordType, displayValue: Double): MeasurementPayload? {
        if (type !in MEASUREMENT_RECORD_TYPES || validationError(type, displayValue) != null) {
            return null
        }
        val storedValue = if (type == RecordType.WEIGHT) {
            (displayValue * GRAMS_PER_KILOGRAM).roundToInt().toDouble()
        } else {
            displayValue
        }
        if (storedValue <= 0.0) return null
        return MeasurementPayload(
            type = type,
            value = storedValue,
            unit = if (type == RecordType.WEIGHT) "g" else "cm",
        )
    }

    fun displayValue(payload: MeasurementPayload): Double =
        if (payload.type == RecordType.WEIGHT && payload.unit == "g") {
            payload.value / GRAMS_PER_KILOGRAM
        } else {
            payload.value
        }

    fun monthAge(
        birthday: LocalDate,
        measuredDate: LocalDate,
    ): Float {
        val chronological = ChronoUnit.DAYS.between(birthday, measuredDate) / DAYS_PER_MONTH
        return chronological.coerceAtLeast(0.0).toFloat()
    }

    fun referenceAt(
        monthAge: Float,
        bands: List<GrowthReferenceBand>,
    ): GrowthReferenceRange? {
        if (bands.isEmpty() || !monthAge.isFinite()) return null
        val sorted = bands.sortedBy(GrowthReferenceBand::month)
        if (monthAge < sorted.first().month || monthAge > sorted.last().month) return null
        val upperIndex = sorted.indexOfFirst { it.month >= monthAge }
        val lower: GrowthReferenceBand
        val upper: GrowthReferenceBand
        when {
            upperIndex < 0 -> {
                lower = sorted.last()
                upper = lower
            }
            upperIndex == 0 -> {
                lower = sorted.first()
                upper = lower
            }
            else -> {
                lower = sorted[upperIndex - 1]
                upper = sorted[upperIndex]
            }
        }
        val progress = if (upper.month == lower.month) {
            0f
        } else {
            ((monthAge - lower.month) / (upper.month - lower.month)).coerceIn(0f, 1f)
        }
        fun interpolate(start: Float, end: Float): Float = start + (end - start) * progress
        return GrowthReferenceRange(
            p3 = interpolate(lower.p3, upper.p3),
            p50 = interpolate(lower.p50, upper.p50),
            p97 = interpolate(lower.p97, upper.p97),
        )
    }

    private const val GRAMS_PER_KILOGRAM = 1_000.0
    private const val DAYS_PER_MONTH = 30.4375
}

data class GrowthReferenceBand(
    val month: Float,
    val p3: Float,
    val p50: Float,
    val p97: Float,
)

data class GrowthReferenceRange(
    val p3: Float,
    val p50: Float,
    val p97: Float,
) {
    fun warningFor(value: Float): String? = when {
        value > p97 -> "该数值高于同月龄参考范围，请确认单位和录入值。"
        value < p3 -> "该数值低于同月龄参考范围，请确认单位和录入值。"
        else -> null
    }
}

private val MEASUREMENT_RECORD_TYPES = setOf(
    RecordType.WEIGHT,
    RecordType.HEIGHT,
    RecordType.HEAD,
    RecordType.CHEST,
    RecordType.FOOT_SIZE,
)
