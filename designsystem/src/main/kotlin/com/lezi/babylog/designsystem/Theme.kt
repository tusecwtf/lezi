package com.lezi.babylog.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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
        laneSleep = resolveLeziRecordColor(LeziRecordColorRole.Sleep, darkTheme = false),
        laneFeed = resolveLeziRecordColor(LeziRecordColorRole.Milk, darkTheme = false),
        laneCare = resolveLeziRecordColor(LeziRecordColorRole.Pee, darkTheme = false),
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

/**
 * Shared HSL lightness for baby theme accents so the header does not jump when
 * switching babies. Saturation is gently clamped so pastels stay soft.
 *
 * Pure Kotlin HSL (no android.graphics) so JVM unit tests can resolve colors.
 */
internal const val BabyThemeLightness = 0.48f

/** Normalize any stored baby theme ARGB to the shared lightness band. */
fun normalizeBabyThemeColor(color: Color): Color {
    val (h, s, _) = rgbToHsl(color.red, color.green, color.blue)
    val sat = s.coerceIn(0.32f, 0.72f)
    val (r, g, b) = hslToRgb(h, sat, BabyThemeLightness)
    return Color(red = r, green = g, blue = b, alpha = color.alpha)
}

fun normalizeBabyThemeArgb(argb: Int): Int {
    val c = normalizeBabyThemeColor(Color(argb))
    val a = (c.alpha * 255f + 0.5f).toInt().coerceIn(0, 255)
    val r = (c.red * 255f + 0.5f).toInt().coerceIn(0, 255)
    val g = (c.green * 255f + 0.5f).toInt().coerceIn(0, 255)
    val b = (c.blue * 255f + 0.5f).toInt().coerceIn(0, 255)
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

/** Returns H in [0,360), S/L in [0,1]. */
internal fun rgbToHsl(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
    val maxC = max(r, max(g, b))
    val minC = min(r, min(g, b))
    val l = (maxC + minC) / 2f
    if (abs(maxC - minC) < 1e-6f) {
        return Triple(0f, 0f, l)
    }
    val d = maxC - minC
    val s = if (l > 0.5f) d / (2f - maxC - minC) else d / (maxC + minC)
    val h = when (maxC) {
        r -> ((g - b) / d + if (g < b) 6f else 0f)
        g -> ((b - r) / d + 2f)
        else -> ((r - g) / d + 4f)
    } / 6f
    return Triple(h * 360f, s, l)
}

/** H in [0,360), S/L in [0,1] → RGB channels in [0,1]. */
internal fun hslToRgb(h: Float, s: Float, l: Float): Triple<Float, Float, Float> {
    if (s <= 1e-6f) {
        return Triple(l, l, l)
    }
    val hue = ((h % 360f) + 360f) % 360f / 360f
    fun hue2rgb(p: Float, q: Float, tIn: Float): Float {
        var t = tIn
        if (t < 0f) t += 1f
        if (t > 1f) t -= 1f
        return when {
            t < 1f / 6f -> p + (q - p) * 6f * t
            t < 1f / 2f -> q
            t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
            else -> p
        }
    }
    val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
    val p = 2f * l - q
    val r = hue2rgb(p, q, hue + 1f / 3f)
    val g = hue2rgb(p, q, hue)
    val b = hue2rgb(p, q, hue - 1f / 3f)
    return Triple(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
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
    // Both templates: baby theme drives primary (CTAs / selected), per PRD §2.1.
    // Journal still keeps its own surface/typography base from Journal*Scheme.
    if (babyThemeArgb == null) return base

    val primary = normalizeBabyThemeColor(Color(babyThemeArgb))
    return base.copy(
        primary = primary,
        onPrimary = readableContentColor(primary),
    )
}

/** Exposed for unit tests that assert Material shape mapping per template. */
internal fun leziShapes(style: LeziVisualStyle): Shapes = when (style) {
    LeziVisualStyle.Warm -> Shapes(
        extraSmall = LeziShapes.Sm,
        small = LeziShapes.Sm,
        medium = LeziShapes.Md,
        large = LeziShapes.Lg,
        extraLarge = LeziShapes.Lg,
    )
    LeziVisualStyle.Journal -> Shapes(
        extraSmall = LeziShapes.JournalSm,
        small = LeziShapes.JournalButton,
        medium = LeziShapes.JournalCard,
        large = LeziShapes.JournalLg,
        extraLarge = LeziShapes.JournalDialog,
    )
}

private val LightExt = LeziExtendedColors(
    fab = LeziColors.Fab,
    skySoft = LeziColors.SkySoft,
    sunSoft = LeziColors.SunSoft,
    creamDeep = LeziColors.CreamDeep,
    laneSleep = resolveLeziRecordColor(LeziRecordColorRole.Sleep, darkTheme = false),
    laneFeed = resolveLeziRecordColor(LeziRecordColorRole.Milk, darkTheme = false),
    laneCare = resolveLeziRecordColor(LeziRecordColorRole.Pee, darkTheme = false),
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
    laneSleep = resolveLeziRecordColor(LeziRecordColorRole.Sleep, darkTheme = true),
    laneFeed = resolveLeziRecordColor(LeziRecordColorRole.Milk, darkTheme = true),
    laneCare = resolveLeziRecordColor(LeziRecordColorRole.Pee, darkTheme = true),
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
    laneSleep = resolveLeziRecordColor(LeziRecordColorRole.Sleep, darkTheme = false),
    laneFeed = resolveLeziRecordColor(LeziRecordColorRole.Milk, darkTheme = false),
    laneCare = resolveLeziRecordColor(LeziRecordColorRole.Pee, darkTheme = false),
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
    laneSleep = resolveLeziRecordColor(LeziRecordColorRole.Sleep, darkTheme = true),
    laneFeed = resolveLeziRecordColor(LeziRecordColorRole.Milk, darkTheme = true),
    laneCare = resolveLeziRecordColor(LeziRecordColorRole.Pee, darkTheme = true),
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
            shapes = leziShapes(style),
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
    val baby = babyThemeArgb?.let { normalizeBabyThemeColor(Color(it)) } ?: fallbackAccent
    return base.copy(babyAccent = baby)
}

object LeziThemeExt {
    val colors: LeziExtendedColors
        @Composable get() = LocalLeziColors.current

    val visualStyle: LeziVisualStyle
        @Composable get() = LocalLeziVisualStyle.current

    val isJournal: Boolean
        @Composable get() = LocalLeziVisualStyle.current == LeziVisualStyle.Journal

    /** Warm cards use 8dp; journal list/panel shells are flat (0). Controls keep 8. */
    val cardShape: Shape
        @Composable get() = if (isJournal) LeziShapes.JournalFlat else LeziShapes.Md

    val controlShape: Shape
        @Composable get() = if (isJournal) LeziShapes.JournalButton else LeziShapes.Sm

    val buttonShape: Shape
        @Composable get() = if (isJournal) LeziShapes.JournalButton else LeziShapes.Button

    val dialogShape: Shape
        @Composable get() = if (isJournal) LeziShapes.JournalDialog else LeziShapes.Md

    val dockShape: Shape
        @Composable get() = if (isJournal) LeziShapes.JournalFlat else LeziShapes.Lg

    val dockElevation: Dp
        @Composable get() = if (isJournal) LeziElevation.DockJournal else LeziElevation.DockWarm

    val cardElevation: Dp
        @Composable get() = if (isJournal) LeziElevation.None else LeziElevation.CardWarm

    val modalElevation: Dp
        @Composable get() = if (isJournal) LeziElevation.ModalJournal else LeziElevation.ModalWarm

    /** Outer corner for swipe action strips (journal flat rows → 0). */
    val swipeActionCorner: Dp
        @Composable get() = if (isJournal) 0.dp else 8.dp
}
