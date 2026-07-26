package com.lezi.babylog.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

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

fun readableContentColor(background: Color): Color {
    val blackContrast = contrastRatio(Color.Black, background)
    val whiteContrast = contrastRatio(Color.White, background)
    return if (blackContrast >= whiteContrast) Color.Black else Color.White
}

private fun contrastRatio(first: Color, second: Color): Float {
    val lighter = maxOf(first.luminance(), second.luminance())
    val darker = minOf(first.luminance(), second.luminance())
    return (lighter + 0.05f) / (darker + 0.05f)
}

private val LightScheme = lightColorScheme(
    primary = LeziColors.Accent,
    onPrimary = readableContentColor(LeziColors.Accent),
    primaryContainer = LeziColors.SkySoft,
    onPrimaryContainer = LeziColors.Fg,
    secondary = LeziColors.Muted,
    onSecondary = readableContentColor(LeziColors.Muted),
    secondaryContainer = Color(0xFFE7EDF2),
    onSecondaryContainer = LeziColors.Fg,
    tertiary = LeziColors.Fab,
    onTertiary = readableContentColor(LeziColors.Fab),
    tertiaryContainer = Color(0xFFF6DDD7),
    onTertiaryContainer = LeziColors.Fg,
    background = LeziColors.Bg,
    onBackground = LeziColors.Fg,
    surface = LeziColors.Surface,
    onSurface = LeziColors.Fg,
    surfaceVariant = Color(0xFFF0EAE0),
    onSurfaceVariant = Color(0xFF526171),
    outline = LeziColors.Border,
    outlineVariant = Color(0xFFB8C8D7),
    error = LeziColors.Danger,
    onError = readableContentColor(LeziColors.Danger),
    errorContainer = Color(0xFFFBDDD8),
    onErrorContainer = Color(0xFF6F1D16),
)

private val DarkScheme = darkColorScheme(
    primary = LeziColors.DarkAccent,
    onPrimary = readableContentColor(LeziColors.DarkAccent),
    primaryContainer = LeziColors.DarkSkySoft,
    onPrimaryContainer = LeziColors.DarkFg,
    secondary = LeziColors.DarkMuted,
    onSecondary = readableContentColor(LeziColors.DarkMuted),
    secondaryContainer = Color(0xFF293744),
    onSecondaryContainer = LeziColors.DarkFg,
    tertiary = LeziColors.DarkFab,
    onTertiary = readableContentColor(LeziColors.DarkFab),
    tertiaryContainer = Color(0xFF4C2A24),
    onTertiaryContainer = LeziColors.DarkFg,
    background = LeziColors.DarkBg,
    onBackground = LeziColors.DarkFg,
    surface = LeziColors.DarkSurface,
    onSurface = LeziColors.DarkFg,
    surfaceVariant = Color(0xFF1C2935),
    onSurfaceVariant = LeziColors.DarkMuted,
    outline = LeziColors.DarkBorder,
    outlineVariant = Color(0xFF2D3C4A),
    error = Color(0xFFFF897E),
    onError = readableContentColor(Color(0xFFFF897E)),
    errorContainer = Color(0xFF5D1F1A),
    onErrorContainer = Color(0xFFFFDAD4),
)

private val JournalLightScheme = lightColorScheme(
    primary = Color(0xFFB7445A),
    onPrimary = readableContentColor(Color(0xFFB7445A)),
    primaryContainer = LeziColors.JournalAccentSoft,
    onPrimaryContainer = LeziColors.JournalFg,
    secondary = LeziColors.JournalSleep,
    onSecondary = readableContentColor(LeziColors.JournalSleep),
    secondaryContainer = Color(0xFFEAE4FA),
    onSecondaryContainer = LeziColors.JournalFg,
    tertiary = LeziColors.JournalCare,
    onTertiary = readableContentColor(LeziColors.JournalCare),
    tertiaryContainer = Color(0xFFDAF3EB),
    onTertiaryContainer = LeziColors.JournalFg,
    background = LeziColors.JournalBg,
    onBackground = LeziColors.JournalFg,
    surface = LeziColors.JournalSurface,
    onSurface = LeziColors.JournalFg,
    surfaceVariant = Color(0xFFEEE9EC),
    onSurfaceVariant = Color(0xFF69666C),
    outline = LeziColors.JournalBorder,
    outlineVariant = Color(0xFFC5C0C3),
    error = LeziColors.Danger,
    onError = readableContentColor(LeziColors.Danger),
    errorContainer = Color(0xFFFBDDD8),
    onErrorContainer = Color(0xFF6F1D16),
)

private val JournalDarkScheme = darkColorScheme(
    primary = LeziColors.JournalDarkAccent,
    onPrimary = readableContentColor(LeziColors.JournalDarkAccent),
    primaryContainer = LeziColors.JournalDarkAccentSoft,
    onPrimaryContainer = LeziColors.JournalDarkFg,
    secondary = Color(0xFFA99BE0),
    onSecondary = readableContentColor(Color(0xFFA99BE0)),
    secondaryContainer = Color(0xFF39304F),
    onSecondaryContainer = LeziColors.JournalDarkFg,
    tertiary = Color(0xFF79D4BA),
    onTertiary = readableContentColor(Color(0xFF79D4BA)),
    tertiaryContainer = Color(0xFF23483D),
    onTertiaryContainer = LeziColors.JournalDarkFg,
    background = LeziColors.JournalDarkBg,
    onBackground = LeziColors.JournalDarkFg,
    surface = LeziColors.JournalDarkSurface,
    onSurface = LeziColors.JournalDarkFg,
    surfaceVariant = Color(0xFF303131),
    onSurfaceVariant = LeziColors.JournalDarkMuted,
    outline = LeziColors.JournalDarkBorder,
    outlineVariant = Color(0xFF3E4040),
    error = Color(0xFFFF897E),
    onError = readableContentColor(Color(0xFFFF897E)),
    errorContainer = Color(0xFF5D1F27),
    onErrorContainer = Color(0xFFFFD9DD),
)

internal fun resolveLeziColorScheme(
    darkTheme: Boolean,
    style: LeziVisualStyle,
    babyThemeArgb: Int?,
): ColorScheme {
    val base = when (style) {
        LeziVisualStyle.Warm -> if (darkTheme) DarkScheme else LightScheme
        LeziVisualStyle.Journal -> if (darkTheme) JournalDarkScheme else JournalLightScheme
    }
    if (style != LeziVisualStyle.Warm || babyThemeArgb == null) return base

    val primary = Color(babyThemeArgb)
    return base.copy(
        primary = primary,
        onPrimary = readableContentColor(primary),
    )
}

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
    danger = Color(0xFFFF897E),
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
    val scheme = resolveLeziColorScheme(darkTheme, style, babyThemeArgb)
    val ext = resolveLeziExtendedColors(darkTheme, style, babyThemeArgb, scheme.primary)
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

internal fun resolveLeziExtendedColors(
    darkTheme: Boolean,
    style: LeziVisualStyle,
    babyThemeArgb: Int?,
    fallbackAccent: Color,
): LeziExtendedColors {
    val base = when (style) {
        LeziVisualStyle.Warm -> if (darkTheme) DarkExt else LightExt
        LeziVisualStyle.Journal -> if (darkTheme) JournalDarkExt else JournalLightExt
    }
    return base.copy(babyAccent = babyThemeArgb?.let(::Color) ?: fallbackAccent)
}

object LeziThemeExt {
    val colors: LeziExtendedColors
        @Composable get() = LocalLeziColors.current

    val visualStyle: LeziVisualStyle
        @Composable get() = LocalLeziVisualStyle.current

    val isJournal: Boolean
        @Composable get() = LocalLeziVisualStyle.current == LeziVisualStyle.Journal
}
