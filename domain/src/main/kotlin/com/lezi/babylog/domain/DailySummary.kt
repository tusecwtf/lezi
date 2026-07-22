package com.lezi.babylog.domain

import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.model.RecordType

data class DailySummary(
    val sleepMinutes: Long = 0,
    val peeCount: Int = 0,
    val poopCount: Int = 0,
    val formulaMl: Int = 0,
    val nursingMinutes: Long = 0,
    val pumpedFeedMl: Int = 0,
)

/** Pure function — unit-test target. */
fun aggregateDaily(records: List<RecordEntity>): DailySummary {
    var sleepMin = 0L
    var pee = 0
    var poop = 0
    var formula = 0
    var nursing = 0L
    var pumped = 0
    for (r in records) {
        if (r.deletedAt != null) continue
        when (RecordType.fromKey(r.type)) {
            RecordType.SLEEP -> {
                val end = r.endTimestamp
                if (end != null && end >= r.timestamp) {
                    sleepMin += (end - r.timestamp) / 60_000L
                }
            }
            RecordType.PEE -> pee += 1
            RecordType.POOP -> poop += 1
            RecordType.BOTH_DIAPER -> {
                pee += 1
                poop += 1
            }
            RecordType.FORMULA -> formula += payloadInt(r.payloadJson, "amount_ml")
            RecordType.PUMPED_FEED -> pumped += payloadInt(r.payloadJson, "amount_ml")
            RecordType.NURSING -> {
                nursing += payloadInt(r.payloadJson, "left_min").toLong()
                nursing += payloadInt(r.payloadJson, "right_min").toLong()
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
    )
}

internal fun payloadInt(json: String, key: String): Int {
    // Minimal JSON number extractor: "key": 123 or "key":123
    val pattern = Regex("\"${Regex.escape(key)}\"\\s*:\\s*(-?\\d+)")
    return pattern.find(json)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
}
