package com.lezi.babylog.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class LeziExtendedColors(
    val fab: Color,
    val skySoft: Color,
    val sunSoft: Color,
    val creamDeep: Color,
    val laneSleep: Color,
    val laneFeed: Color,
    val laneCare: Color,
    val danger: Color,
    val success: Color,
    val warning: Color,
    val sun: Color,
    val chartGrid: Color,
    val babyAccent: Color,
)

enum class LeziVisualStyle(val key: String) {
    Warm("warm"),
    Journal("journal");

    companion object {
        fun fromKey(key: String): LeziVisualStyle = entries.firstOrNull { it.key == key } ?: Warm
    }
}

val LocalLeziVisualStyle = staticCompositionLocalOf { LeziVisualStyle.Warm }

val LocalLeziColors = staticCompositionLocalOf {
    LeziExtendedColors(
        fab = LeziColors.Fab,
        skySoft = LeziColors.SkySoft,
        sunSoft = LeziColors.SunSoft,
        creamDeep = LeziColors.CreamDeep,
        laneSleep = LeziColors.LaneSleep,
        laneFeed = LeziColors.LaneFeed,
        laneCare = LeziColors.LaneCare,
        danger = LeziColors.Danger,
        success = LeziColors.Success,
        warning = LeziColors.Warning,
        sun = LeziColors.Sun,
        chartGrid = LeziColors.Border,
        babyAccent = LeziColors.Accent,
    )
}

private val LightScheme = lightColorScheme(
    primary = LeziColors.Accent,
    onPrimary = Color.White,
    secondary = LeziColors.Muted,
    onSecondary = Color.White,
    background = LeziColors.Bg,
    onBackground = LeziColors.Fg,
    surface = LeziColors.Surface,
    onSurface = LeziColors.Fg,
    onSurfaceVariant = LeziColors.Muted,
    outline = LeziColors.Border,
    tertiary = LeziColors.Fab,
    onTertiary = Color.White,
    error = LeziColors.Danger,
)

private val DarkScheme = darkColorScheme(
    primary = LeziColors.DarkAccent,
    onPrimary = LeziColors.DarkBg,
    secondary = LeziColors.DarkMuted,
    onSecondary = LeziColors.DarkFg,
    background = LeziColors.DarkBg,
    onBackground = LeziColors.DarkFg,
    surface = LeziColors.DarkSurface,
    onSurface = LeziColors.DarkFg,
    onSurfaceVariant = LeziColors.DarkMuted,
    outline = LeziColors.DarkBorder,
    tertiary = LeziColors.DarkFab,
    onTertiary = Color.White,
    error = LeziColors.Danger,
)

private val JournalLightScheme = lightColorScheme(
    primary = LeziColors.JournalAccent,
    onPrimary = Color.White,
    primaryContainer = LeziColors.JournalAccentSoft,
    onPrimaryContainer = LeziColors.JournalFg,
    secondary = LeziColors.JournalSleep,
    onSecondary = Color.White,
    secondaryContainer = LeziColors.JournalAccentSoft,
    onSecondaryContainer = LeziColors.JournalFg,
    background = LeziColors.JournalBg,
    onBackground = LeziColors.JournalFg,
    surface = LeziColors.JournalSurface,
    onSurface = LeziColors.JournalFg,
    onSurfaceVariant = LeziColors.JournalMuted,
    outline = LeziColors.JournalBorder,
    tertiary = LeziColors.JournalCare,
    onTertiary = Color.White,
    error = LeziColors.Danger,
)

private val JournalDarkScheme = darkColorScheme(
    primary = LeziColors.JournalDarkAccent,
    onPrimary = Color(0xFF271015),
    primaryContainer = LeziColors.JournalDarkAccentSoft,
    onPrimaryContainer = LeziColors.JournalDarkFg,
    secondary = Color(0xFFA99BE0),
    onSecondary = LeziColors.JournalDarkBg,
    secondaryContainer = LeziColors.JournalDarkAccentSoft,
    onSecondaryContainer = LeziColors.JournalDarkFg,
    background = LeziColors.JournalDarkBg,
    onBackground = LeziColors.JournalDarkFg,
    surface = LeziColors.JournalDarkSurface,
    onSurface = LeziColors.JournalDarkFg,
    onSurfaceVariant = LeziColors.JournalDarkMuted,
    outline = LeziColors.JournalDarkBorder,
    tertiary = Color(0xFF79D4BA),
    onTertiary = LeziColors.JournalDarkBg,
    error = Color(0xFFFF897E),
)

private val LightExt = LeziExtendedColors(
    fab = LeziColors.Fab,
    skySoft = LeziColors.SkySoft,
    sunSoft = LeziColors.SunSoft,
    creamDeep = LeziColors.CreamDeep,
    laneSleep = LeziColors.LaneSleep,
    laneFeed = LeziColors.LaneFeed,
    laneCare = LeziColors.LaneCare,
    danger = LeziColors.Danger,
    success = LeziColors.Success,
    warning = LeziColors.Warning,
    sun = LeziColors.Sun,
    chartGrid = LeziColors.Border,
    babyAccent = LeziColors.Accent,
)

private val DarkExt = LeziExtendedColors(
    fab = LeziColors.DarkFab,
    skySoft = LeziColors.DarkSkySoft,
    sunSoft = LeziColors.DarkSunSoft,
    creamDeep = LeziColors.DarkCreamDeep,
    laneSleep = LeziColors.LaneSleep,
    laneFeed = LeziColors.DarkAccent,
    laneCare = Color(0xFF8FB894),
    danger = LeziColors.Danger,
    success = LeziColors.Success,
    warning = LeziColors.Warning,
    sun = LeziColors.Sun,
    chartGrid = LeziColors.DarkBorder,
    babyAccent = LeziColors.DarkAccent,
)

private val JournalLightExt = LeziExtendedColors(
    fab = LeziColors.JournalAccent,
    skySoft = Color(0xFFE8F7FC),
    sunSoft = Color(0xFFFFF3D7),
    creamDeep = LeziColors.JournalAccentSoft,
    laneSleep = LeziColors.JournalSleep,
    laneFeed = LeziColors.JournalFeed,
    laneCare = LeziColors.JournalCare,
    danger = LeziColors.Danger,
    success = LeziColors.Success,
    warning = LeziColors.Warning,
    sun = LeziColors.JournalSun,
    chartGrid = LeziColors.JournalChartGrid,
    babyAccent = LeziColors.JournalAccent,
)

private val JournalDarkExt = LeziExtendedColors(
    fab = LeziColors.JournalDarkAccent,
    skySoft = Color(0xFF17363F),
    sunSoft = Color(0xFF40351E),
    creamDeep = LeziColors.JournalDarkAccentSoft,
    laneSleep = Color(0xFFA99BE0),
    laneFeed = LeziColors.JournalDarkAccent,
    laneCare = Color(0xFF79D4BA),
    danger = Color(0xFFFF897E),
    success = Color(0xFF7CD6A1),
    warning = Color(0xFFECCB68),
    sun = Color(0xFFECCB68),
    chartGrid = LeziColors.JournalDarkChartGrid,
    babyAccent = LeziColors.JournalDarkAccent,
)

@Composable
fun LeziTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    babyThemeArgb: Int? = null,
    visualStyle: String = "warm",
    content: @Composable () -> Unit,
) {
    val style = LeziVisualStyle.fromKey(visualStyle)
    val base = when (style) {
        LeziVisualStyle.Warm -> if (darkTheme) DarkScheme else LightScheme
        LeziVisualStyle.Journal -> if (darkTheme) JournalDarkScheme else JournalLightScheme
    }
    val baseExt = when (style) {
        LeziVisualStyle.Warm -> if (darkTheme) DarkExt else LightExt
        LeziVisualStyle.Journal -> if (darkTheme) JournalDarkExt else JournalLightExt
    }
    val babyAccent = babyThemeArgb?.let { Color(it) } ?: base.primary
    val primary = if (style == LeziVisualStyle.Warm) babyAccent else base.primary
    val scheme = base.copy(
        primary = primary,
        onPrimary = if (style == LeziVisualStyle.Warm && darkTheme) LeziColors.DarkBg else base.onPrimary,
    )
    val ext = baseExt.copy(babyAccent = babyAccent)
    CompositionLocalProvider(
        LocalLeziColors provides ext,
        LocalLeziVisualStyle provides style,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = LeziTypography.material(journal = style == LeziVisualStyle.Journal),
            content = content,
        )
    }
}

object LeziThemeExt {
    val colors: LeziExtendedColors
        @Composable get() = LocalLeziColors.current

    val visualStyle: LeziVisualStyle
        @Composable get() = LocalLeziVisualStyle.current

    val isJournal: Boolean
        @Composable get() = LocalLeziVisualStyle.current == LeziVisualStyle.Journal
}
