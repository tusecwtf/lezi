package com.lezi.babylog.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Reference canvas size used by component previews. */
object LeziCanvas {
    val Width = 390.dp
    val Height = 844.dp
}

object LeziColors {
    // Light
    val Bg = Color(0xFFFBF7EE)
    val Surface = Color(0xFFFEFCF9)
    val Fg = Color(0xFF1E2C3C)
    val Muted = Color(0xFF657383)
    val Border = Color(0xFFCCDBE9)
    val Accent = Color(0xFF007BAE)
    val Fab = Color(0xFFAA442B)
    val Sun = Color(0xFFF0D36A)
    val Success = Color(0xFF3F9B6A)
    val Danger = Color(0xFFC0392B)
    val Warning = Color(0xFFD4A017)
    val SkySoft = Color(0xFFE5F2F8)
    val SunSoft = Color(0xFFF8F0D4)
    val CreamDeep = Color(0xFFF0E6D2)

    // Dark
    val DarkBg = Color(0xFF0E1721)
    val DarkSurface = Color(0xFF15202C)
    val DarkFg = Color(0xFFEDE7DB)
    val DarkMuted = Color(0xFFA8B3BF)
    val DarkBorder = Color(0xFF3A4A5A)
    val DarkAccent = Color(0xFF60B3DC)
    val DarkFab = Color(0xFFC8664E)
    val DarkSkySoft = Color(0xFF1A3344)
    val DarkSunSoft = Color(0xFF3A3420)
    val DarkCreamDeep = Color(0xFF2A2418)

    // Sleep-in-progress moon cap (header avatar + sleep composer accents)
    val SleepMoonCap = Color(0xFF7965BE)
    val SleepMoonCapEdge = Color(0xFF4B3E7A)
    val SleepMoon = Color(0xFFFFE59A)
    val SleepSun = Color(0xFFF3A93B)

    // Compact logbook colors.
    val JournalBg = Color(0xFFF4F3F5)
    val JournalSurface = Color(0xFFF9F7F8)
    val JournalFg = Color(0xFF36363A)
    val JournalMuted = Color(0xFF77747A)
    val JournalBorder = Color(0xFFD7D4D6)
    val JournalAccent = Color(0xFFEA7C8F)
    val JournalAccentSoft = Color(0xFFFFE6EB)
    val JournalSleep = Color(0xFF8B78D1)
    val JournalCare = Color(0xFF59C6A5)
    val JournalSun = Color(0xFFF3B84B)
    val JournalChartGrid = Color(0xFFC9CBD2)

    val JournalDarkBg = Color(0xFF202121)
    val JournalDarkSurface = Color(0xFF262727)
    val JournalDarkFg = Color(0xFFF1EFF0)
    val JournalDarkMuted = Color(0xFFAAA5A7)
    val JournalDarkBorder = Color(0xFF4C4D4D)
    val JournalDarkAccent = Color(0xFFEC7887)
    val JournalDarkAccentSoft = Color(0xFF4B252D)
    val JournalDarkChartGrid = Color(0xFF505058)
}

@Immutable
object LeziSpacing {
    val Xxs: Dp = 4.dp
    val Xs: Dp = 8.dp
    val Sm: Dp = 12.dp
    val Md: Dp = 16.dp
    val Lg: Dp = 20.dp
    val Xl: Dp = 24.dp
    val Xxl: Dp = 32.dp
    val Touch: Dp = 48.dp
    val Nav: Dp = 76.dp
    val Page: Dp = 16.dp
    val CardPad: Dp = 14.dp
    val SectionGap: Dp = 12.dp
    val TopBarHeight: Dp = 68.dp
    val TopBarHorizontal: Dp = 10.dp
    val TopBarAvatar: Dp = 34.dp
    val TopBarAction: Dp = 48.dp
}

/**
 * Corner radii for the two visual templates.
 *
 * Warm: soft cards with a single 8dp radius (aligned with journal controls).
 * Journal: compact logbook — list/panels use 0; controls 4 / 8; dialog 18;
 * circles only for record icons on the dock, timer mains, and avatars.
 */
@Immutable
object LeziShapes {
    // Warm scale: one 8dp radius for cards, buttons, dock, dialogs.
    val Sm = RoundedCornerShape(8.dp)
    val Md = Sm
    val Lg = Sm
    val Pill = RoundedCornerShape(999.dp)
    /** Warm primary/secondary buttons — same 8dp as cards. */
    val Button = Sm

    // Journal scale.
    val JournalSm = RoundedCornerShape(4.dp)
    val JournalCard = RoundedCornerShape(8.dp)
    val JournalButton = RoundedCornerShape(8.dp)
    val JournalLg = RoundedCornerShape(12.dp)
    val JournalDialog = RoundedCornerShape(18.dp)
    /** Flat list / panel shells (journal de-card). */
    val JournalFlat = RoundedCornerShape(0.dp)
    /** Chart marks / hairline chips shared by both templates. */
    val Micro = RoundedCornerShape(2.dp)
}

/** Soft float (warm) vs thin/hard (journal) elevation steps. */
@Immutable
object LeziElevation {
    val None = 0.dp
    val CardWarm = 1.dp
    val ButtonWarm = 4.dp
    val DockWarm = 8.dp
    val ModalWarm = 12.dp
    val DockJournal = 2.dp
    val ModalJournal = 8.dp
    /** Journal primary hard-edge shadow height (CSS `0 3px 0`). */
    val JournalHardEdge = 3.dp
}

/**
 * Shared baby theme swatches. Display and theme resolution always normalize
 * these to a common HSL lightness so header chrome does not jump when switching babies.
 */
object LeziBabyTheme {
    val PaletteArgb: List<Int> = listOf(
        0xFF007BAE.toInt(),
        0xFFAA442B.toInt(),
        0xFF2F8F6B.toInt(),
        0xFF7A5CFF.toInt(),
        0xFFE09F3E.toInt(),
        0xFFD4578C.toInt(),
        0xFF4C6A92.toInt(),
        0xFF5B8C5A.toInt(),
    )

    val Labels: List<String> = listOf(
        "湖蓝",
        "砖红",
        "青绿",
        "紫罗兰",
        "琥珀",
        "玫红",
        "灰蓝",
        "草绿",
    )
}

object LeziTypography {
    private val BodyFamily = FontFamily.SansSerif
    private val DisplayFamily = FontFamily.Serif
    private val MonoFamily = FontFamily.Monospace

    val Display = TextStyle(
        fontFamily = DisplayFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.4).sp,
    )
    val Title = TextStyle(
        fontFamily = DisplayFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.2).sp,
    )
    val TitleSm = TextStyle(
        fontFamily = BodyFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
    )
    val Body = TextStyle(
        fontFamily = BodyFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    )
    val BodyStrong = Body.copy(fontWeight = FontWeight.SemiBold)
    val Label = TextStyle(
        fontFamily = BodyFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.sp,
    )
    val Meta = TextStyle(
        fontFamily = BodyFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    )
    val Metric = TextStyle(
        fontFamily = MonoFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    )
    val Mono = TextStyle(
        fontFamily = MonoFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    )
    val Eyebrow = TextStyle(
        fontFamily = BodyFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.6.sp,
    )

    fun material(journal: Boolean = false): Typography = Typography(
        displayLarge = if (journal) Display.copy(fontFamily = BodyFamily) else Display,
        headlineMedium = if (journal) Title.copy(fontFamily = BodyFamily) else Title,
        titleLarge = TitleSm,
        titleMedium = TitleSm,
        bodyLarge = Body,
        bodyMedium = Body,
        bodySmall = Meta,
        labelLarge = Label,
        labelMedium = Label,
        labelSmall = Meta,
    )
}
