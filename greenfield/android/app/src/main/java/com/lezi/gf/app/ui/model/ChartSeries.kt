package com.lezi.gf.app.ui.model

import com.lezi.gf.care.CareRecord
import com.lezi.gf.care.DaySummary
import com.lezi.gf.care.GrowthCurves
import com.lezi.gf.care.RecordType

/**
 * Drawable series for summary week charts and growth curves — pure mapping.
 */
object ChartSeries {
    enum class WeekMetric { MILK_ML, SLEEP_MIN, PEE, POOP, NURSING }

    data class BarPoint(val dayIndex: Int, val value: Float, val label: String)
    data class CurvePoint(val x: Float, val y: Float)
    data class GrowthDrawable(
        val p3: List<CurvePoint>,
        val p50: List<CurvePoint>,
        val p97: List<CurvePoint>,
        val measurements: List<CurvePoint>,
        val yMin: Float,
        val yMax: Float,
        val nonEmpty: Boolean,
    )

    fun weekBars(days: List<DaySummary>, metric: WeekMetric): List<BarPoint> {
        return days.mapIndexed { i, d ->
            val v = when (metric) {
                WeekMetric.MILK_ML -> d.milkMl.toFloat()
                WeekMetric.SLEEP_MIN -> d.sleepMinutes.toFloat()
                WeekMetric.PEE -> d.peeCount.toFloat()
                WeekMetric.POOP -> d.poopCount.toFloat()
                WeekMetric.NURSING -> d.nursingCount.toFloat()
            }
            BarPoint(i, v, "D$i")
        }
    }

    fun isDrawable(bars: List<BarPoint>): Boolean =
        bars.isNotEmpty() && bars.any { it.value > 0f }

    fun maxBar(bars: List<BarPoint>): Float =
        bars.maxOfOrNull { it.value }?.coerceAtLeast(1f) ?: 1f

    fun growthWeightDrawable(
        ageMonthsNow: Int,
        male: Boolean,
        weightRecords: List<CareRecord>,
        birthdayEpochDay: Long,
        spanMonths: Int = 12,
    ): GrowthDrawable {
        val startMonth = (ageMonthsNow - spanMonths / 2).coerceAtLeast(0)
        val endMonth = startMonth + spanMonths
        val p3 = mutableListOf<CurvePoint>()
        val p50 = mutableListOf<CurvePoint>()
        val p97 = mutableListOf<CurvePoint>()
        for (m in startMonth..endMonth) {
            val band = GrowthCurves.weightBandKg(m, male)
            val x = (m - startMonth).toFloat()
            p3 += CurvePoint(x, band.p3.toFloat())
            p50 += CurvePoint(x, band.p50.toFloat())
            p97 += CurvePoint(x, band.p97.toFloat())
        }
        val measurements = weightRecords
            .filter { it.typeKey == RecordType.WEIGHT.key && it.deletedAtMs == null }
            .mapNotNull { r ->
                val grams = Regex("\"grams\"\\s*:\\s*(\\d+)")
                    .find(r.payloadJson)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
                val day = r.timestampMs / 86_400_000L
                val months = ((day - birthdayEpochDay) / 30.0).toFloat().coerceAtLeast(0f)
                val x = months - startMonth
                if (x < -0.5f || x > spanMonths + 0.5f) return@mapNotNull null
                CurvePoint(x, grams / 1000f)
            }
        val allY = p3.map { it.y } + p50.map { it.y } + p97.map { it.y } + measurements.map { it.y }
        val yMin = (allY.minOrNull() ?: 0f) * 0.9f
        val yMax = (allY.maxOrNull() ?: 10f) * 1.1f
        return GrowthDrawable(
            p3 = p3,
            p50 = p50,
            p97 = p97,
            measurements = measurements,
            yMin = yMin,
            yMax = yMax.coerceAtLeast(yMin + 1f),
            nonEmpty = p3.isNotEmpty(),
        )
    }

    fun growthLengthDrawable(
        ageMonthsNow: Int,
        male: Boolean,
        spanMonths: Int = 12,
    ): GrowthDrawable {
        val startMonth = (ageMonthsNow - spanMonths / 2).coerceAtLeast(0)
        val endMonth = startMonth + spanMonths
        val p3 = mutableListOf<CurvePoint>()
        val p50 = mutableListOf<CurvePoint>()
        val p97 = mutableListOf<CurvePoint>()
        for (m in startMonth..endMonth) {
            val band = GrowthCurves.lengthOrHeightBandCm(m, male)
            val x = (m - startMonth).toFloat()
            p3 += CurvePoint(x, band.p3.toFloat())
            p50 += CurvePoint(x, band.p50.toFloat())
            p97 += CurvePoint(x, band.p97.toFloat())
        }
        val allY = p3.map { it.y } + p50.map { it.y } + p97.map { it.y }
        return GrowthDrawable(
            p3 = p3,
            p50 = p50,
            p97 = p97,
            measurements = emptyList(),
            yMin = (allY.minOrNull() ?: 40f) * 0.95f,
            yMax = (allY.maxOrNull() ?: 100f) * 1.05f,
            nonEmpty = p3.isNotEmpty(),
        )
    }
}
