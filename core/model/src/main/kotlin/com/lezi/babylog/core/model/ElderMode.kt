package com.lezi.babylog.core.model

/** Missing or unknown persisted value falls back to off. */
fun normalizeElderMode(raw: String?): String = when (raw) {
    "l1", "l2", "l3", "off" -> raw
    else -> "off"
}

fun requireElderMode(raw: String): String {
    require(raw == "off" || raw == "l1" || raw == "l2" || raw == "l3") {
        "Unknown elder mode: $raw"
    }
    return raw
}

fun elderModeFontScaleMultiplier(elderMode: String): Float = when (normalizeElderMode(elderMode)) {
    "l1" -> 1.5f
    "l2" -> 1.8f
    "l3" -> 2.1f
    else -> 1f
}

/** off / unknown keep [systemFontScale]. l1–l3 replace it; they do not multiply. */
fun elderModeFontScale(systemFontScale: Float, elderMode: String): Float =
    when (normalizeElderMode(elderMode)) {
        "l1", "l2", "l3" -> elderModeFontScaleMultiplier(elderMode)
        else -> systemFontScale
    }

fun elderModeEnabled(elderMode: String): Boolean = normalizeElderMode(elderMode) != "off"

/** Switch on from off lands on l2, the compliance baseline. */
fun elderModeAfterSwitch(current: String, enabled: Boolean): String = when {
    !enabled -> "off"
    normalizeElderMode(current) == "off" -> "l2"
    else -> normalizeElderMode(current)
}
