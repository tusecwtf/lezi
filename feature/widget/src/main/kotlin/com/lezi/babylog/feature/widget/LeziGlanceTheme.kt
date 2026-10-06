package com.lezi.babylog.feature.widget

import androidx.glance.color.ColorProviders
import androidx.glance.color.ColorProvider
import androidx.glance.color.colorProviders
import com.lezi.babylog.designsystem.LeziColors
import com.lezi.babylog.designsystem.readableContentColor

/**
 * Widget palette mapped from [LeziColors] — the single color source (0.5.4
 * ticket 17, spec M5). The default `GlanceTheme` resolves dynamic/baseline system
 * colors with no tie to the brand palette.
 *
 * Every slot is a [ColorProvider] picked by the system UI mode at render
 * time, so the widget follows the system dark theme. The widget is a system-level
 * surface: the in-app journal/warm style switch and the baby theme accent
 * deliberately do not reach it, and the warm base tokens are used (no baby
 * accent). The slots the widget renders (background, onBackground,
 * onSurfaceVariant, primaryContainer, onPrimaryContainer) mirror the app's warm
 * scheme; `onSurfaceVariant` maps to [LeziColors.Muted] instead of the scheme's
 * non-token inline value, which is the same brand role and keeps the whole object
 * inside the LeziColors single source. Remaining slots — never rendered by this
 * widget — are pinned to the same brand families so no unbranded value can leak
 * in if a future edit starts consuming them.
 */
internal fun leziGlanceColors(): ColorProviders = colorProviders(
    primary = ColorProvider(LeziColors.Accent, LeziColors.DarkAccent),
    onPrimary = ColorProvider(
        readableContentColor(LeziColors.Accent),
        readableContentColor(LeziColors.DarkAccent),
    ),
    primaryContainer = ColorProvider(LeziColors.SkySoft, LeziColors.DarkSkySoft),
    onPrimaryContainer = ColorProvider(LeziColors.Fg, LeziColors.DarkFg),
    secondary = ColorProvider(LeziColors.Muted, LeziColors.DarkMuted),
    onSecondary = ColorProvider(
        readableContentColor(LeziColors.Muted),
        readableContentColor(LeziColors.DarkMuted),
    ),
    secondaryContainer = ColorProvider(LeziColors.SkySoft, LeziColors.DarkSkySoft),
    onSecondaryContainer = ColorProvider(LeziColors.Fg, LeziColors.DarkFg),
    tertiary = ColorProvider(LeziColors.Fab, LeziColors.DarkFab),
    onTertiary = ColorProvider(
        readableContentColor(LeziColors.Fab),
        readableContentColor(LeziColors.DarkFab),
    ),
    tertiaryContainer = ColorProvider(LeziColors.SunSoft, LeziColors.DarkSunSoft),
    onTertiaryContainer = ColorProvider(LeziColors.Fg, LeziColors.DarkFg),
    // No dark error token exists in LeziColors; the widget surface renders no
    // error role, so the light Danger hue is kept in both modes.
    error = ColorProvider(LeziColors.Danger, LeziColors.Danger),
    onError = ColorProvider(
        readableContentColor(LeziColors.Danger),
        readableContentColor(LeziColors.Danger),
    ),
    errorContainer = ColorProvider(LeziColors.Bg, LeziColors.DarkBg),
    onErrorContainer = ColorProvider(LeziColors.Fg, LeziColors.DarkFg),
    background = ColorProvider(LeziColors.Bg, LeziColors.DarkBg),
    onBackground = ColorProvider(LeziColors.Fg, LeziColors.DarkFg),
    surface = ColorProvider(LeziColors.Surface, LeziColors.DarkSurface),
    onSurface = ColorProvider(LeziColors.Fg, LeziColors.DarkFg),
    surfaceVariant = ColorProvider(LeziColors.SkySoft, LeziColors.DarkSkySoft),
    onSurfaceVariant = ColorProvider(LeziColors.Muted, LeziColors.DarkMuted),
    outline = ColorProvider(LeziColors.Border, LeziColors.DarkBorder),
    inverseOnSurface = ColorProvider(LeziColors.Bg, LeziColors.DarkBg),
    inverseSurface = ColorProvider(LeziColors.Fg, LeziColors.DarkFg),
    inversePrimary = ColorProvider(LeziColors.DarkAccent, LeziColors.Accent),
    widgetBackground = ColorProvider(LeziColors.Bg, LeziColors.DarkBg),
)
