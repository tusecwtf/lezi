package com.lezi.gf.care

/**
 * Offline WS/T 423—2022 style percentile band intent (ticket 14).
 * Simplified reference table for weight (grams) by age months — not diagnosis.
 */
object GrowthCurves {
    data class Band(val p3: Double, val p50: Double, val p97: Double)

    /** Very coarse offline reference — product shows band, never diagnostic conclusion. */
    fun weightBandKg(ageMonths: Int, male: Boolean): Band {
        // Illustrative anchors only
        val base = when {
            ageMonths <= 0 -> Triple(2.5, 3.3, 4.2)
            ageMonths <= 3 -> Triple(4.5, 6.0, 7.5)
            ageMonths <= 6 -> Triple(6.0, 7.8, 9.5)
            ageMonths <= 12 -> Triple(7.5, 9.5, 11.5)
            ageMonths <= 24 -> Triple(9.0, 11.5, 14.0)
            else -> Triple(11.0, 14.0, 18.0)
        }
        val adj = if (male) 0.1 else -0.05
        return Band(base.first + adj, base.second + adj, base.third + adj)
    }

    fun lengthOrHeightBandCm(ageMonths: Int, male: Boolean): Band {
        val base = when {
            ageMonths < 24 -> Triple(46.0, 50.0, 54.0) // length intent
            else -> Triple(80.0, 87.0, 95.0) // height intent
        }
        val adj = if (male) 0.5 else -0.3
        return Band(base.first + adj, base.second + adj, base.third + adj)
    }

    const val NON_DIAGNOSIS_DISCLAIMER: String =
        "参考百分位带仅供回顾，不构成医疗诊断。"
}
