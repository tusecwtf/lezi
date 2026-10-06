package com.lezi.babylog.domain.carelog

import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.businessLabel

/**
 * Presentation-only: same-window feeding or diaper subtypes stay visible and
 * do not write a source relation. Timeline can say 「附近还有…」.
 */
object NearbySubtypeHint {
    fun hints(
        records: List<Record>,
        hiddenClientUuids: Set<String> = emptySet(),
        windowMs: Long = SuspectedDuplicateGrouping.WINDOW_MS,
    ): Map<String, String> {
        val visible = records.filter {
            it.deletedAt == null && it.clientUuid !in hiddenClientUuids && familyOf(it.type) != null
        }
        if (visible.size < 2) return emptyMap()
        val result = linkedMapOf<String, String>()
        for (record in visible) {
            val family = familyOf(record.type) ?: continue
            val nearby = visible.filter { other ->
                other.clientUuid != record.clientUuid &&
                    other.babyId == record.babyId &&
                    familyOf(other.type) == family &&
                    other.type != record.type &&
                    kotlin.math.abs(other.timestamp - record.timestamp) <= windowMs
            }
            if (nearby.isEmpty()) continue
            val labels = nearby.map { it.type.businessLabel() }.distinct().sorted()
            result[record.clientUuid] = "附近还有${labels.joinToString("、")}"
        }
        return result
    }

    internal fun familyOf(type: RecordType): Family? = when (type) {
        RecordType.NURSING,
        RecordType.FORMULA,
        RecordType.PUMPED_FEED,
        RecordType.PUMP_EXPRESS,
        RecordType.BABY_FOOD,
        RecordType.SNACK,
        RecordType.DRINK,
        -> Family.FEEDING
        RecordType.PEE,
        RecordType.POOP,
        RecordType.BOTH_DIAPER,
        -> Family.DIAPER
        else -> null
    }

    internal enum class Family { FEEDING, DIAPER }
}
