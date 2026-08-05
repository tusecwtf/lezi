package com.lezi.gf.app.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.BabyChangingStation
import androidx.compose.material.icons.filled.Bathtub
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Height
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.LocalPharmacy
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Sick
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Vaccines
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lezi.gf.care.RecordType
import com.lezi.gf.settings.UiTemplate
import java.time.LocalDate
import java.time.ZoneId

/**
 * Product density + type glyph + motion tokens (PRD ui.md §2.1 / Spec 02).
 * Warm = open card language; Journal = compact grid language.
 */
object LeziSpacing {
    val Xs = 4.dp
    val Sm = 8.dp
    val Md = 16.dp
    val Lg = 24.dp
    val Touch = 48.dp
    val DockTouch = 56.dp
    val TimerButton = 112.dp
    val Page = 16.dp
}

data class LeziDensity(
    val cardPad: Dp,
    val topBarHorizontal: Dp,
    val sectionGap: Dp,
    val panelContent: Dp,
    val dockOuterHorizontal: Dp,
    val cardCorner: Dp,
    val useCards: Boolean,
) {
    companion object {
        val Warm = LeziDensity(
            cardPad = 16.dp,
            topBarHorizontal = 12.dp,
            sectionGap = 16.dp,
            panelContent = 16.dp,
            dockOuterHorizontal = 12.dp,
            cardCorner = 8.dp,
            useCards = true,
        )
        val Journal = LeziDensity(
            cardPad = 12.dp,
            topBarHorizontal = 8.dp,
            sectionGap = 8.dp,
            panelContent = 8.dp,
            dockOuterHorizontal = 0.dp,
            cardCorner = 0.dp,
            useCards = false,
        )

        fun forTemplate(template: UiTemplate): LeziDensity =
            if (template == UiTemplate.JOURNAL) Journal else Warm
    }
}

/** Surface / semantic colors shared across warm·journal light·dark chrome. */
object LeziColors {
    val WarmCream = Color(0xFFFFF8F0)
    val WarmCard = Color(0xFFFFFCF7)
    val JournalCoral = Color(0xFFE76F51)
    val SuccessEdit = Color(0xFF2A9D8F)
    val DangerDelete = Color(0xFFE63946)
    val RelativeTimeMuted = Color(0xFF8A8580)
    val DarkSurface = Color(0xFF1C1B1A)
    val DarkSurfaceVariant = Color(0xFF2C2A28)

    fun parseHex(hex: String, fallback: Color = Color(0xFFF4A261)): Color {
        val raw = hex.trim().removePrefix("#")
        return try {
            when (raw.length) {
                6 -> Color(android.graphics.Color.parseColor("#$raw"))
                8 -> Color(android.graphics.Color.parseColor("#$raw"))
                else -> fallback
            }
        } catch (_: IllegalArgumentException) {
            fallback
        }
    }

    fun babyAccent(themeColorHex: String?, journalFallback: Boolean = false): Color {
        if (!themeColorHex.isNullOrBlank()) return parseHex(themeColorHex)
        return if (journalFallback) JournalCoral else Color(0xFFF4A261)
    }
}

object LeziTypeGlyph {
    /**
     * Short Chinese glyph fallback (a11y / no-vector paths).
     * Prefer [vector] on dock, timeline, and more sheet for shared icon language.
     */
    fun glyph(typeKey: String?): String = when (typeKey) {
        RecordType.NURSING.key -> "乳"
        RecordType.FORMULA.key -> "奶"
        RecordType.PUMPED_FEED.key -> "瓶"
        RecordType.PUMP_EXPRESS.key -> "吸"
        RecordType.PEE.key -> "尿"
        RecordType.POOP.key -> "便"
        RecordType.BOTH_DIAPER.key -> "换"
        RecordType.SLEEP.key -> "睡"
        RecordType.TEMPERATURE.key -> "温"
        RecordType.DIARY.key -> "记"
        RecordType.BATH.key -> "浴"
        RecordType.WALK.key -> "走"
        RecordType.COUGH.key -> "咳"
        RecordType.RASH.key -> "疹"
        RecordType.VOMIT.key -> "吐"
        RecordType.INJURY.key -> "伤"
        RecordType.MEDICINE.key -> "药"
        RecordType.HOSPITAL.key -> "医"
        RecordType.HEIGHT.key -> "高"
        RecordType.WEIGHT.key -> "重"
        RecordType.BABY_FOOD.key -> "辅"
        RecordType.SNACK.key -> "零"
        RecordType.DRINK.key -> "水"
        RecordType.HEAD_SIZE.key -> "头"
        RecordType.CHEST_SIZE.key -> "胸"
        RecordType.FOOT_SIZE.key -> "脚"
        RecordType.VACCINE.key -> "苗"
        RecordType.CUSTOM.key -> "自"
        "__timer__" -> "计"
        "__more__" -> "多"
        null -> "·"
        else -> if (typeKey.startsWith("custom:")) "自" else "记"
    }

    /**
     * Shared vector icon language (closer to 0.3.x Material marks) for dock / row / more.
     * Type color still comes from [accent]; glyph remains for TalkBack fallbacks.
     */
    fun vector(typeKey: String?): ImageVector = when (typeKey) {
        RecordType.NURSING.key -> Icons.Default.ChildCare
        RecordType.FORMULA.key -> Icons.Default.LocalDrink
        RecordType.PUMPED_FEED.key -> Icons.Default.LocalDrink
        RecordType.PUMP_EXPRESS.key -> Icons.Default.Science
        RecordType.PEE.key -> Icons.Default.WaterDrop
        RecordType.POOP.key -> Icons.Default.BabyChangingStation
        RecordType.BOTH_DIAPER.key -> Icons.Default.BabyChangingStation
        RecordType.SLEEP.key -> Icons.Default.Bedtime
        RecordType.TEMPERATURE.key -> Icons.Default.Thermostat
        RecordType.DIARY.key -> Icons.AutoMirrored.Filled.Notes
        RecordType.BATH.key -> Icons.Default.Bathtub
        RecordType.WALK.key -> Icons.AutoMirrored.Filled.DirectionsWalk
        RecordType.COUGH.key -> Icons.Default.Sick
        RecordType.RASH.key -> Icons.Default.Sick
        RecordType.VOMIT.key -> Icons.Default.Sick
        RecordType.INJURY.key -> Icons.Default.LocalHospital
        RecordType.MEDICINE.key -> Icons.Default.LocalPharmacy
        RecordType.HOSPITAL.key -> Icons.Default.LocalHospital
        RecordType.HEIGHT.key -> Icons.Default.Height
        RecordType.WEIGHT.key -> Icons.Default.MonitorWeight
        RecordType.BABY_FOOD.key -> Icons.Default.Restaurant
        RecordType.SNACK.key -> Icons.Default.Restaurant
        RecordType.DRINK.key -> Icons.Default.LocalDrink
        RecordType.HEAD_SIZE.key -> Icons.Default.ChildCare
        RecordType.CHEST_SIZE.key -> Icons.Default.Height
        RecordType.FOOT_SIZE.key -> Icons.Default.Height
        RecordType.VACCINE.key -> Icons.Default.Vaccines
        RecordType.CUSTOM.key -> Icons.Default.EditNote
        "__timer__" -> Icons.Default.Timer
        "__more__" -> Icons.Default.MoreHoriz
        null -> Icons.Default.MoreHoriz
        else -> if (typeKey.startsWith("custom:")) Icons.Default.EditNote else Icons.Default.EditNote
    }

    fun accent(typeKey: String?): Color = when (typeKey) {
        RecordType.NURSING.key -> Color(0xFFE76F51)
        RecordType.FORMULA.key, RecordType.PUMPED_FEED.key -> Color(0xFF2A9D8F)
        RecordType.PEE.key -> Color(0xFFE9C46A)
        RecordType.POOP.key, RecordType.BOTH_DIAPER.key -> Color(0xFFB08968)
        RecordType.SLEEP.key -> Color(0xFF457B9D)
        RecordType.TEMPERATURE.key -> Color(0xFFE63946)
        RecordType.WEIGHT.key, RecordType.HEIGHT.key -> Color(0xFF6A4C93)
        else -> Color(0xFF6D6875)
    }
}

/**
 * Day-summary chip policy (spec-02-gf-legacy-align ticket 05 / E4).
 *
 * **Policy (locked):** always-five structure for 奶 / 母乳 / 睡眠 / 尿 / 便
 * (0.3.x everyday chrome). Values may be zero. Filter selection only when
 * the chip has data (PRD A2: 0-count classes do not enter empty filter).
 */
object DaySummaryChipPolicy {
    data class Chip(
        val typeKey: String,
        val label: String,
        val value: Int,
        val selectable: Boolean,
    )

    fun chips(summary: com.lezi.gf.care.DaySummary): List<Chip> = listOf(
        Chip(RecordType.FORMULA.key, "奶 ${summary.milkMl}ml", summary.milkMl, summary.milkMl > 0),
        Chip(RecordType.NURSING.key, "母乳 ${summary.nursingCount}", summary.nursingCount, summary.nursingCount > 0),
        Chip(RecordType.SLEEP.key, "睡 ${summary.sleepMinutes}分", summary.sleepMinutes, summary.sleepMinutes > 0),
        Chip(RecordType.PEE.key, "尿 ${summary.peeCount}", summary.peeCount, summary.peeCount > 0),
        Chip(RecordType.POOP.key, "便 ${summary.poopCount}", summary.poopCount, summary.poopCount > 0),
    )
}

/**
 * Motion token table (PRD ui.md §2.1.1). Shell transitions must reference these;
 * leaf surfaces may still use literals until migrated.
 */
object LeziMotion {
    const val Fast = 150
    const val Base = 200
    const val Emphasized = 300

    fun nonEssentialMillis(reduceMotion: Boolean, base: Int): Int =
        if (reduceMotion) 0 else base

    /** Alias for shell call-sites. */
    fun millis(reduceMotion: Boolean, tier: Int = Base): Int =
        nonEssentialMillis(reduceMotion, tier)
}

/** Relative time copy for timeline rows (PRD: 「N 分钟前」). */
object LeziRelativeTime {
    fun format(eventMs: Long, nowMs: Long): String {
        val delta = nowMs - eventMs
        if (delta < 0) {
            val ahead = -delta
            return when {
                ahead < 60_000L -> "即将"
                ahead < 3_600_000L -> "${ahead / 60_000L} 分钟后"
                ahead < 86_400_000L -> "${ahead / 3_600_000L} 小时后"
                else -> "${ahead / 86_400_000L} 天后"
            }
        }
        return when {
            delta < 60_000L -> "刚刚"
            delta < 3_600_000L -> "${delta / 60_000L} 分钟前"
            delta < 86_400_000L -> "${delta / 3_600_000L} 小时前"
            delta < 7 * 86_400_000L -> "${delta / 86_400_000L} 天前"
            else -> "${delta / (7 * 86_400_000L)} 周前"
        }
    }
}

/** Day-age line under baby nickname (满日龄). */
object LeziDayAge {
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    fun todayEpochDay(nowMs: Long = System.currentTimeMillis()): Long =
        LocalDate.ofInstant(java.time.Instant.ofEpochMilli(nowMs), zone).toEpochDay()

    /**
     * @param birthdayEpochDay 0 means unset → null (caller may hide line)
     * @param useDayAgeMode true = 生后 N 日; false = N 个月 N 天 style when possible
     */
    fun format(
        birthdayEpochDay: Long,
        todayEpochDay: Long = todayEpochDay(),
        useDayAgeMode: Boolean = true,
    ): String? {
        if (birthdayEpochDay <= 0L) return null
        val days = (todayEpochDay - birthdayEpochDay).toInt()
        if (days < 0) return "未出生"
        if (useDayAgeMode) return "生后 $days 日"
        val months = days / 30
        val rem = days % 30
        return if (months <= 0) "生后 $days 天" else "$months 个月 $rem 天"
    }
}

/**
 * Swipe timeline pure model (design 2026-07-29).
 * Signed offsetFraction: positive = finger right (delete), negative = finger left (edit).
 * Card does not translate; fill fraction = abs(offset).
 */
object SwipeGestureModel {
    const val REVEAL_FRACTION = 0.28f
    const val COMMIT_FRACTION = 0.55f

    enum class Action { EDIT, DELETE }

    enum class Settle {
        CLOSED,
        REVEAL_EDIT,
        REVEAL_DELETE,
        COMMIT_EDIT,
        COMMIT_DELETE,
    }

    fun settle(offsetFraction: Float): Settle {
        val abs = kotlin.math.abs(offsetFraction)
        if (abs < REVEAL_FRACTION / 2f) return Settle.CLOSED
        val isDelete = offsetFraction > 0f
        return when {
            abs >= COMMIT_FRACTION -> if (isDelete) Settle.COMMIT_DELETE else Settle.COMMIT_EDIT
            abs >= REVEAL_FRACTION -> if (isDelete) Settle.REVEAL_DELETE else Settle.REVEAL_EDIT
            else -> Settle.CLOSED
        }
    }

    fun clampedOffset(raw: Float): Float =
        raw.coerceIn(-1f, 1f)

    fun settleTarget(settle: Settle): Float = when (settle) {
        Settle.CLOSED -> 0f
        Settle.REVEAL_EDIT -> -REVEAL_FRACTION
        Settle.REVEAL_DELETE -> REVEAL_FRACTION
        Settle.COMMIT_EDIT -> -COMMIT_FRACTION
        Settle.COMMIT_DELETE -> COMMIT_FRACTION
    }
}

/** Clock dial pure helpers: drag delta maps to minute steps. */
object TimeDialModel {
    /** Minutes per full circle. */
    const val MINUTES_PER_TURN = 60

    fun minutesFromDragPx(totalDragPx: Float, circumferencePx: Float): Int {
        if (circumferencePx <= 0f) return 0
        val turns = totalDragPx / circumferencePx
        return (turns * MINUTES_PER_TURN).toInt()
    }

    fun applyMinutes(baseMs: Long, deltaMinutes: Int): Long =
        baseMs + deltaMinutes * 60_000L
}
