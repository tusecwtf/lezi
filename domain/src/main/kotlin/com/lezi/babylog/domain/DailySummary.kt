package com.lezi.babylog.domain

import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType

data class DailySummary(
    val sleepMinutes: Long = 0,
    val peeCount: Int = 0,
    val poopCount: Int = 0,
    val formulaMl: Int = 0,
    val nursingMinutes: Long = 0,
    val pumpedFeedMl: Int = 0,
    /** formulaMl + pumpedFeedMl + nursing.amount_ml (pump_express never counts). */
    val feedMl: Int = 0,
)

/**
 * Aggregate records inside a half-open time window.
 *
 * Sleep intervals are clipped to the requested window. An open interval ends
 * at [now], which keeps today's summary live without assigning a cross-midnight
 * sleep wholly to its start date.
 */
fun aggregateDaily(
    records: List<Record>,
    windowStartInclusive: Long = Long.MIN_VALUE,
    windowEndExclusive: Long = Long.MAX_VALUE,
    now: Long = System.currentTimeMillis(),
): DailySummary {
    require(windowStartInclusive < windowEndExclusive) {
        "windowStartInclusive must be before windowEndExclusive"
    }
    var sleepMin = 0L
    var pee = 0
    var poop = 0
    var formula = 0
    var nursing = 0L
    var pumped = 0
    var feed = 0
    for (r in records) {
        if (r.deletedAt != null) continue
        if (
            r.type != RecordType.SLEEP &&
            (r.timestamp < windowStartInclusive || r.timestamp >= windowEndExclusive)
        ) {
            continue
        }
        when (r.type) {
            RecordType.SLEEP -> {
                val intervalEnd = r.endTimestamp ?: now
                val clippedStart = maxOf(r.timestamp, windowStartInclusive)
                val clippedEnd = minOf(intervalEnd, windowEndExclusive)
                if (clippedEnd > clippedStart) {
                    sleepMin += (clippedEnd - clippedStart) / 60_000L
                }
            }
            RecordType.PEE -> pee += 1
            RecordType.POOP -> poop += 1
            RecordType.BOTH_DIAPER -> {
                pee += 1
                poop += 1
            }
            RecordType.FORMULA -> {
                val ml = payloadInt(r.payloadJson, "amount_ml")
                formula += ml
                feed += ml
            }
            RecordType.PUMPED_FEED -> {
                val ml = payloadInt(r.payloadJson, "amount_ml")
                pumped += ml
                feed += ml
            }
            RecordType.NURSING -> {
                nursing += payloadInt(r.payloadJson, "left_min").toLong()
                nursing += payloadInt(r.payloadJson, "right_min").toLong()
                // Optional nursing amount_ml counts toward feedMl.
                feed += payloadInt(r.payloadJson, "amount_ml")
            }
            RecordType.PUMP_EXPRESS -> {
                // Explicitly excluded from feedMl.
            }
            else -> Unit
        }
    }
    return DailySummary(
        sleepMinutes = sleepMin,
        peeCount = pee,
        poopCount = poop,
        formulaMl = formula,
        nursingMinutes = nursing,
        pumpedFeedMl = pumped,
        feedMl = feed,
    )
}

fun payloadInt(json: String, key: String): Int {
    val pattern = Regex("\"${Regex.escape(key)}\"\\s*:\\s*(-?\\d+)")
    return pattern.find(json)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
}

fun payloadDouble(json: String, key: String): Double? {
    val pattern = Regex("\"${Regex.escape(key)}\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)")
    return pattern.find(json)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
}

fun payloadBool(json: String, key: String): Boolean {
    val pattern = Regex("\"${Regex.escape(key)}\"\\s*:\\s*(true|false)")
    return pattern.find(json)?.groupValues?.getOrNull(1) == "true"
}
