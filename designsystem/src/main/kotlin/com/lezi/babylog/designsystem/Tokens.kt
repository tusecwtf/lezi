package com.lezi.babylog.designsystem

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType

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
    /** Kept: feeds [LeziExtendedColors.warning] (LightExt / JournalLightExt). */
    val Warning = Color(0xFFD4A017)
    val SkySoft = Color(0xFFE5F2F8)
    val SunSoft = Color(0xFFF8F0D4)
    val CreamDeep = Color(0xFFF0E6D2)
    /** Neutral residual lane for the fourth 辅食 summary slot. */
    val FoodSummaryOther = Color(0xFF7C8995)

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

    // Dark semantic accents: hue-preserving brightened variants of Success/Warning/Sun,
    // lifted by the same strategy as the journal dark palette so they read on DarkBg.
    val DarkSuccess = Color(0xFF7CD6A1)
    val DarkWarning = Color(0xFFECCB68)
    val DarkSun = Color(0xFFF3DB86)
    /** Neutral residual lane for the fourth 辅食 summary slot on dark surfaces. */
    val DarkFoodSummaryOther = Color(0xFF9AA7B3)

    // Sleep-in-progress moon cap (header avatar + sleep composer accents)
    val SleepMoonCap = Color(0xFF7965BE)
    val SleepMoonCapEdge = Color(0xFF4B3E7A)
    val SleepMoon = Color(0xFFFFE59A)
    val SleepSun = Color(0xFFF3A93B)

    // Compact logbook colors.
    val JournalBg = Color(0xFFF4F3F5)
    val JournalSurface = Color(0xFFF9F7F8)
    val JournalFg = Color(0xFF36363A)
    val JournalBorder = Color(0xFFD7D4D6)
    /** Deep journal-light scheme primary (rose); distinct from [JournalAccent]. */
    val JournalPrimary = Color(0xFFB7445A)
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

    // Surface container gradients — the M3 tonal roles (`surfaceContainer*`,
    // `surfaceBright`/`surfaceDim`/`surfaceTint`) that ModalBottomSheet, menus and
    // the clock dial fall back to. resolveLeziColorScheme copies these into all four
    // schemes; hand-tuned per template around its own ground hue so dark sheets stay
    // in the brand blue-black (#15202C family) instead of the M3 purple-black
    // (#211F26 family). Ordered per mode: light runs lightest → deepest, dark runs
    // darkest → lightest. 0.5.4 ticket 13.
    // Warm light (cream ladder under Surface/Bg).
    val SurfaceContainerLowest = Color(0xFFFFFFFF)
    val SurfaceContainerLow = Color(0xFFF8F3E9)
    val SurfaceContainer = Color(0xFFF4EDDE)
    val SurfaceContainerHigh = Color(0xFFEFE7D4)
    val SurfaceContainerHighest = Color(0xFFE9DFC9)
    val SurfaceBright = Color(0xFFFFFDF8)
    val SurfaceDim = Color(0xFFE6DCC6)
    // Warm dark (blue-black ladder around DarkSurface).
    val DarkSurfaceContainerLowest = Color(0xFF0A121B)
    val DarkSurfaceContainerLow = Color(0xFF19242F)
    val DarkSurfaceContainer = Color(0xFF1D2A36)
    val DarkSurfaceContainerHigh = Color(0xFF232F3C)
    val DarkSurfaceContainerHighest = Color(0xFF293744)
    val DarkSurfaceBright = Color(0xFF31404F)
    val DarkSurfaceDim = Color(0xFF0E1721)
    // Journal light (cool paper-gray ladder under JournalSurface/JournalBg).
    val JournalSurfaceContainerLowest = Color(0xFFFFFFFF)
    val JournalSurfaceContainerLow = Color(0xFFF3F2F4)
    val JournalSurfaceContainer = Color(0xFFEDECEF)
    val JournalSurfaceContainerHigh = Color(0xFFE6E4E9)
    val JournalSurfaceContainerHighest = Color(0xFFDFDCE1)
    val JournalSurfaceBright = Color(0xFFFBFAFB)
    val JournalSurfaceDim = Color(0xFFD8D5DB)
    // Journal dark (neutral charcoal ladder around JournalDarkSurface).
    val JournalDarkSurfaceContainerLowest = Color(0xFF1B1C1C)
    val JournalDarkSurfaceContainerLow = Color(0xFF2A2B2B)
    val JournalDarkSurfaceContainer = Color(0xFF2E2F30)
    val JournalDarkSurfaceContainerHigh = Color(0xFF343638)
    val JournalDarkSurfaceContainerHighest = Color(0xFF3E4040)
    val JournalDarkSurfaceBright = Color(0xFF47494B)
    val JournalDarkSurfaceDim = Color(0xFF202121)
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
    val Page: Dp = 16.dp
    /**
     * Legacy single-template card pad (off-grid 14).
     * For structural density roles prefer [LeziDensity.forStyle] / [LeziThemeExt.density]
     * (`cardPad`); keep this value until migration tickets rewire call sites.
     */
    val CardPad: Dp = 14.dp
    /**
     * Legacy single-template section gap.
     * For structural density roles prefer [LeziDensity.forStyle] / [LeziThemeExt.density]
     * (`sectionGap`); keep this value until migration tickets rewire call sites.
     */
    val SectionGap: Dp = 12.dp
    val TopBarHeight: Dp = 68.dp
    /**
     * Legacy single-template top-bar horizontal inset (off-grid 10).
     * For structural density roles prefer [LeziDensity.forStyle] / [LeziThemeExt.density]
     * (`topBarHorizontal`); keep this value until migration tickets rewire call sites.
     */
    val TopBarHorizontal: Dp = 10.dp
    val TopBarAvatar: Dp = 34.dp
    val TopBarAction: Dp = 48.dp
    /** Unified max height for scrolling dialog content (was ad-hoc 420/480/520). */
    val DialogContentMax: Dp = 480.dp
}

/**
 * Shared icon geometry for both visual templates. Warm and journal restyle the
 * well (circle vs 8dp square) and density; they must not ship a second glyph set.
 * Record / custom items always draw [LeziRecordGlyphIcon] / [LeziCustomItemGlyphIcon].
 */
@Immutable
object LeziIconSize {
    /** Stroke glyph inside a disc (dock, catalog, timeline, default RecordTypeIcon). */
    val Glyph: Dp = 18.dp
    /** Tinted disc behind a record glyph (dock + catalog). */
    val Disc: Dp = 32.dp
    /** Tinted well on summary metric cards. */
    val Chip: Dp = 28.dp
    /** Empty / error / success state mark. */
    val State: Dp = 32.dp
    /** Circular well behind a menu / settings row glyph. */
    val MenuWell: Dp = 40.dp
    /** Menu / settings Material glyph (same as [LeziSpacing.Lg]). */
    val MenuGlyph: Dp = LeziSpacing.Lg
}

/**
 * Menu / settings row leading-icon treatment — aliases [LeziIconSize] so every
 * row paints the same optical weight (ticket 11 weak-surface polish).
 */
@Immutable
object LeziMenuIcon {
    /** Circular well behind the menu glyph. */
    val WellSize: Dp = LeziIconSize.MenuWell
    /** Fixed glyph box; apply via `Modifier.size(GlyphSize)` on Material Icon. */
    val GlyphSize: Dp = LeziIconSize.MenuGlyph
}

/**
 * Shared motion durations in **milliseconds** for [androidx.compose.animation.core.tween]
 * `durationMillis` (and equivalent APIs). Main-transition vocabulary for:
 * nav host fades, layout-edit, wizard steps, content crossfades, range-tab feedback,
 * primary timer control color feedback, and similar shell motion.
 * Shell and layout-edit paths should reference these tiers (not ad-hoc ms literals).
 *
 * - [Fast]: micro feedback and short exits
 * - [Base]: default enter/exit and content crossfades
 * - [Emphasized]: larger structural transitions (layout-edit, multi-step)
 *
 * Reduce-motion: [nonEssentialMillis] returns `0` when the system motion duration
 * scale is ≤ 0 so non-essential transitions are instant while state still swaps.
 * Partial scales are left to the Compose animation clock (do not pre-multiply).
 *
 * **Scale source of truth:** product policy reads
 * [Settings.Global.ANIMATOR_DURATION_SCALE] via [systemAnimatorDurationScale]
 * (live in Compose through [leziMotionDurationScale]). Compose
 * [androidx.compose.ui.MotionDurationScale] is the same system signal when present
 * on a coroutine context; both must feed [nonEssentialMillis] only — never a
 * parallel duration table. This BOM has no public `LocalMotionDurationScale`.
 */
@Immutable
object LeziMotion {
    /** Milliseconds. */
    const val Fast: Int = 150
    /** Milliseconds. */
    const val Base: Int = 200
    /** Milliseconds. */
    const val Emphasized: Int = 300

    /**
     * Resolve a token duration for a **non-essential** transition.
     *
     * @param tokenMs one of [Fast], [Base], or [Emphasized] (or a positive ms value)
     * @param motionDurationScale system animator duration scale (or Compose
     *   [androidx.compose.ui.MotionDurationScale.scaleFactor]); ≤ 0 means
     *   reduce-motion / animations disabled
     * @return `0` (instant) when scale ≤ 0; otherwise [tokenMs] unchanged
     */
    fun nonEssentialMillis(tokenMs: Int, motionDurationScale: Float): Int =
        if (motionDurationScale <= 0f) 0 else tokenMs

    /**
     * Product reduce-motion scale from [Settings.Global.ANIMATOR_DURATION_SCALE].
     * Default `1f` when the setting is absent (no [Settings.SettingNotFoundException]).
     * Prefer this (or [leziMotionDurationScale] in Compose) over a second parallel
     * scale table; coroutine [androidx.compose.ui.MotionDurationScale] is the same
     * system flag when injected by the platform.
     */
    fun systemAnimatorDurationScale(contentResolver: ContentResolver): Float =
        Settings.Global.getFloat(
            contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
}

/**
 * Shared enter/exit easings (motion-polish ticket 02). Values are the shipped
 * material-components-android 1.12.0 AAR literals for the emphasized pair —
 * the m3.material.io page once printed slightly different numbers; the AAR is
 * authoritative and matches Android M3 component behavior.
 *
 * - [EmphasizedDecelerate] — **enter / reveal**: fast start, long settle.
 * - [EmphasizedAccelerate] — **exit / leave**: slow start, fast leave.
 *
 * This object carries curves only. Durations keep coming from [LeziMotion]
 * (never a second duration table — `ui.md` §2.1.1); micro feedback keeps the
 * implicit tween default `FastOutSlowInEasing`, which is exactly the AAR
 * "legacy" curve, so no third constant is needed.
 */
@Immutable
object LeziEasing {
    /** Enter / reveal — cubic-bezier(0.1, 0.7, 0.1, 1) (material 1.12.0 AAR). */
    val EmphasizedDecelerate: Easing = CubicBezierEasing(0.1f, 0.7f, 0.1f, 1f)

    /** Exit / leave — cubic-bezier(0.3, 0.0, 0.8, 0.2) (material 1.12.0 AAR). */
    val EmphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.2f)
}

/**
 * Live system animator-duration scale for Compose call sites.
 *
 * Reads [LeziMotion.systemAnimatorDurationScale] and re-reads when
 * [Settings.Global.ANIMATOR_DURATION_SCALE] changes (ContentObserver), so
 * shell captures flip if the user toggles Remove animations mid-session.
 * Compose's animation clock still scales non-zero tweens independently.
 */
@Composable
fun leziMotionDurationScale(): Float {
    val context = LocalContext.current
    val resolver = context.contentResolver
    var scale by remember(resolver) {
        mutableFloatStateOf(LeziMotion.systemAnimatorDurationScale(resolver))
    }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                scale = LeziMotion.systemAnimatorDurationScale(resolver)
            }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        // Re-read after register in case the setting changed between remember and now.
        scale = LeziMotion.systemAnimatorDurationScale(resolver)
        onDispose {
            resolver.unregisterContentObserver(observer)
        }
    }
    return scale
}

/**
 * Compose-side read of [LeziMotion.nonEssentialMillis] for non-essential shell
 * transitions. Uses live [leziMotionDurationScale] (Settings.Global product
 * signal; no public `LocalMotionDurationScale` on this BOM). Capture the result
 * per composition and close over it in non-@Composable `transitionSpec` /
 * `enterTransition` lambdas so mid-session accessibility toggles update the
 * next transition.
 */
@Composable
fun leziMotionMillis(tokenMs: Int): Int {
    val scale = leziMotionDurationScale()
    return LeziMotion.nonEssentialMillis(tokenMs = tokenMs, motionDurationScale = scale)
}

/**
 * Template-specific structural density on the 4/8 grid ([LeziSpacing] steps).
 * Warm is more open; journal is more compact for the same roles.
 * Elder (l1–l3) replaces the template table with a more open five-field scale.
 * Authority for card pad, top-bar horizontal inset, section gap, and panel content
 * pad once call sites migrate; [LeziSpacing.CardPad] / [LeziSpacing.TopBarHorizontal] /
 * [LeziSpacing.SectionGap] remain as legacy single-template values until then.
 * Compose consumers should prefer [LeziThemeExt.density]. Touch minimum stays
 * [LeziSpacing.Touch]; elder primary/secondary/gap resolve via [LeziThemeExt.touchTarget].
 */
@Immutable
data class LeziDensityScale(
    val cardPad: Dp,
    val topBarHorizontal: Dp,
    val sectionGap: Dp,
    val panelContent: Dp,
    /**
     * Outer horizontal inset for the fixed quick dock shell.
     * Journal is product full-bleed (`0.dp`); warm matches [topBarHorizontal];
     * elder is 12dp on both templates.
     */
    val dockOuterHorizontal: Dp,
)

@Immutable
object LeziDensity {
    /** Open warm scale — structural pads use [LeziSpacing] Md/Sm steps. */
    val Warm = LeziDensityScale(
        cardPad = LeziSpacing.Md,
        topBarHorizontal = LeziSpacing.Sm,
        sectionGap = LeziSpacing.Md,
        panelContent = LeziSpacing.Md,
        dockOuterHorizontal = LeziSpacing.Sm,
    )
    /** Compact journal scale — structural pads use [LeziSpacing] Sm/Xs steps. */
    val Journal = LeziDensityScale(
        cardPad = LeziSpacing.Sm,
        topBarHorizontal = LeziSpacing.Xs,
        sectionGap = LeziSpacing.Xs,
        panelContent = LeziSpacing.Xs,
        // Full-bleed dock shell; top bar still uses [topBarHorizontal] = Xs.
        dockOuterHorizontal = 0.dp,
    )
    /**
     * Elder overlay — larger card/section/panel pads; dock inset stays warm-open 12.
     * Replaces the template table while elder_mode is l1–l3.
     */
    val Elder = LeziDensityScale(
        cardPad = LeziSpacing.Lg,
        topBarHorizontal = LeziSpacing.Md,
        sectionGap = LeziSpacing.Lg,
        panelContent = LeziSpacing.Lg,
        dockOuterHorizontal = LeziSpacing.Sm,
    )

    fun forStyle(style: LeziVisualStyle): LeziDensityScale = when (style) {
        LeziVisualStyle.Warm -> Warm
        LeziVisualStyle.Journal -> Journal
    }
}

fun resolveLeziDensity(style: LeziVisualStyle, elder: Boolean): LeziDensityScale =
    if (elder) LeziDensity.Elder else LeziDensity.forStyle(style)

/**
 * Resolved tap-target floors. [LeziSpacing.Touch] stays 48; elder primary lifts to 60.
 * Dialog close is the MIIT 44dp floor — widgets may be larger.
 */
@Immutable
data class LeziTouchTargetScale(
    val primary: Dp,
    val secondary: Dp,
    val gap: Dp,
    val dialogClose: Dp,
)

fun resolveLeziTouchTarget(elder: Boolean): LeziTouchTargetScale = if (elder) {
    LeziTouchTargetScale(
        primary = 60.dp,
        secondary = LeziSpacing.Touch,
        gap = LeziSpacing.Xs,
        dialogClose = 44.dp,
    )
} else {
    LeziTouchTargetScale(
        primary = LeziSpacing.Touch,
        secondary = LeziSpacing.Touch,
        gap = LeziSpacing.Xs,
        dialogClose = LeziSpacing.Touch,
    )
}

/**
 * Record-home structure overrides that are not density pads: top bar, dock cells,
 * time-bar hour-label chrome, and day-summary columns.
 */
@Immutable
data class LeziStructureScale(
    val topBarMinHeight: Dp,
    val dockCellMinHeight: Dp,
    val dockCellSpacing: Dp,
    val timeBarTrackMinHeight: Dp,
    val summaryColumnCount: Int,
    val chipMetricMaxLines: Int,
    val chipMetricSoftWrap: Boolean,
)

fun resolveLeziStructure(elder: Boolean): LeziStructureScale = if (elder) {
    LeziStructureScale(
        topBarMinHeight = 76.dp,
        dockCellMinHeight = 84.dp,
        dockCellSpacing = LeziSpacing.Xs,
        timeBarTrackMinHeight = 22.dp,
        summaryColumnCount = 2,
        chipMetricMaxLines = 2,
        chipMetricSoftWrap = true,
    )
} else {
    LeziStructureScale(
        topBarMinHeight = LeziSpacing.TopBarHeight,
        dockCellMinHeight = 64.dp,
        dockCellSpacing = LeziSpacing.Xxs,
        timeBarTrackMinHeight = 14.dp,
        summaryColumnCount = 4,
        chipMetricMaxLines = 1,
        chipMetricSoftWrap = false,
    )
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

/**
 * Soft float (warm cards/dock/modal) vs flat/thin (journal panels).
 * Primary CTAs ([LeziPrimaryButton]) are flat: [None] only — no bottom hard-edge
 * strip and no button float elevation.
 */
@Immutable
object LeziElevation {
    val None = 0.dp
    val CardWarm = 1.dp
    val DockWarm = 8.dp
    val ModalWarm = 12.dp
    val DockJournal = 2.dp
    val ModalJournal = 8.dp
}

/**
 * Shared alpha steps for tinted overlays, hairlines, and state feedback,
 * distilled from existing call sites — prefer these over ad-hoc literals.
 */
@Immutable
object LeziAlphas {
    /** De-emphasized content sitting on a tinted fill (e.g. sheet surface veil). */
    val Emphasis = 0.72f
    /** Quiet decorative marks (focus rings, inactive hints). */
    val Muted = 0.45f
    /** Disabled foreground, aligned with the Material disabled alpha. */
    val Disabled = 0.38f
    /** Hairline dividers drawn with the outline color. */
    val Hairline = 0.85f
}

/**
 * Single source for journal 1dp outline hairlines (panel footer + record row
 * separators). Prefer this over ad-hoc `outline.copy(alpha = 0.85f)`.
 */
@Composable
fun leziHairlineColor(): Color =
    MaterialTheme.colorScheme.outline.copy(alpha = LeziAlphas.Hairline)

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

/**
 * Stool color swatch palette (clinical reference scale), slots 1..7.
 * Slot 0 stays theme-driven at the call site (surface fill + outline stroke).
 * Dark variants keep the hue and lift lightness so swatches read on dark surfaces,
 * mirroring the record-color dark strategy in RecordVisuals.
 */
object LeziStoolPalette {
    private val FillLight = listOf(
        Color(0xFFF4F1EA), // 1 白陶土
        Color(0xFFE6C04A), // 2 金黄
        Color(0xFFE08A3A), // 3 橙
        Color(0xFF8B5E34), // 4 棕
        Color(0xFF5FA86A), // 5 绿
        Color(0xFFC85A4A), // 6 红
        Color(0xFF2A2A2A), // 7 黑
    )
    private val FillDark = listOf(
        Color(0xFFF4F1EA),
        Color(0xFFF0D073),
        Color(0xFFF0A35C),
        Color(0xFFC08A56),
        Color(0xFF82C496),
        Color(0xFFE07868),
        Color(0xFF454548),
    )
    private val OutlineLight = listOf(
        Color(0xFF9AA3AD),
        Color(0xFF8A7020),
        Color(0xFF8A4E18),
        Color(0xFF4A3420),
        Color(0xFF2F5C38),
        Color(0xFF6E2C24),
        Color(0xFF111111),
    )
    private val OutlineDark = listOf(
        Color(0xFF9AA3AD),
        Color(0xFFB08A34),
        Color(0xFFB06A2C),
        Color(0xFF7A5636),
        Color(0xFF3F7A50),
        Color(0xFF8E3A30),
        Color(0xFFBFC2C5),
    )
    fun fill(slot: Int, darkTheme: Boolean): Color {
        val i = (slot.coerceIn(1, 7)) - 1
        return (if (darkTheme) FillDark else FillLight)[i]
    }


    /**
     * Outline for slot 1..7 (coerced). Slot 7 (black swatch) outline was historically
     * computed from background luminance at the call site; the dark/light pair here
     * encodes exactly that result (dark bg → light grey ring, light bg → near-black ring).
     */
    fun outline(slot: Int, darkTheme: Boolean): Color {
        val i = (slot.coerceIn(1, 7)) - 1
        return (if (darkTheme) OutlineDark else OutlineLight)[i]
    }
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
    /** Page-hero headline — Display face and weight at 34/40. */
    val Hero = Display.copy(fontSize = 34.sp, lineHeight = 40.sp)
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
    /** Catalog / dock labels — one step above [Label], still below [Body]. */
    val LabelLg = TextStyle(
        fontFamily = BodyFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    )
    val Meta = TextStyle(
        fontFamily = BodyFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    )
    /** Smallest meta text (10sp) — dense axis ticks and footnote labels. */
    val Micro = Meta.copy(fontSize = 10.sp)
    val Metric = TextStyle(
        fontFamily = MonoFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    )
    /** Journal KPI numerals — same mono face as [Metric] at Title size. */
    val MetricSm = Metric.copy(fontSize = 20.sp, lineHeight = 26.sp)
    /**
     * Timer numerals (0.5.4 ticket 16) — the [Metric] face at [Display] size so
     * the nursing timer's side-button readout keeps its 28sp grade from the
     * type scale instead of an inline `28.sp` override. Tight leading matches
     * [Display]; elder still swaps family/line height via [elderTextStyle].
     */
    val MetricLg = Metric.copy(fontSize = 28.sp, lineHeight = 32.sp)
    /**
     * Four-column glance numerals (e.g. `12h20m`). 13sp is the largest mono
     * that still fits a 1/4-width phone cell; do not replace with [Mono] 14sp.
     * Elder two-column layout lifts that width constraint and allows wrap.
     */

    val ChipMetric = TextStyle(
        fontFamily = MonoFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 16.sp,
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

    /**
     * Material 3 slot mapping — all 15 slots resolved onto the Lezi scale
     * (0.5.4 ticket 16 filled displayMedium/displaySmall/headlineLarge, which
     * previously leaked the M3 baseline styles). The existing band style holds:
     * each M3 role band collapses onto one Lezi token, so the new display slots
     * join displayLarge → Display and headlineLarge joins the headline band →
     * Title (the table has never been raw size-nearest — cf. titleLarge →
     * TitleSm over Title). The twelve previously shipped slots keep their
     * exact values.
     */
    fun material(journal: Boolean = false, elder: Boolean = false): Typography {
        val scale = resolveLeziTypeScale(elder)
        val display = if (journal && !elder) {
            scale.Display.copy(fontFamily = BodyFamily)
        } else {
            scale.Display
        }
        val title = if (journal && !elder) {
            scale.Title.copy(fontFamily = BodyFamily)
        } else {
            scale.Title
        }
        return Typography(
            displayLarge = display,
            displayMedium = display,
            displaySmall = display,
            headlineLarge = title,
            headlineMedium = title,
            headlineSmall = title,
            titleLarge = scale.TitleSm,
            titleMedium = scale.TitleSm,
            titleSmall = scale.Label,
            bodyLarge = scale.Body,
            bodyMedium = scale.Body,
            bodySmall = scale.Meta,
            labelLarge = scale.Label,
            labelMedium = scale.Label,
            labelSmall = scale.Meta,
        )
    }
}

/**
 * Resolved typography ramp for the current display mode.
 * Off is the declared [LeziTypography] tokens; elder is sans-serif,
 * lineHeight ≥ 1.5×, and Display/Title/Hero negative tracking reset to 0.
 */
@Immutable
data class LeziTypeScale(
    val Display: TextStyle,
    val Hero: TextStyle,
    val Title: TextStyle,
    val TitleSm: TextStyle,
    val Body: TextStyle,
    val BodyStrong: TextStyle,
    val Label: TextStyle,
    val LabelLg: TextStyle,
    val Meta: TextStyle,
    val Micro: TextStyle,
    val Metric: TextStyle,
    val MetricSm: TextStyle,
    val MetricLg: TextStyle,
    val ChipMetric: TextStyle,
    val Mono: TextStyle,
    val Eyebrow: TextStyle,
)

internal fun resolveLeziTypeScale(elder: Boolean): LeziTypeScale {
    val base = LeziTypeScale(
        Display = LeziTypography.Display,
        Hero = LeziTypography.Hero,
        Title = LeziTypography.Title,
        TitleSm = LeziTypography.TitleSm,
        Body = LeziTypography.Body,
        BodyStrong = LeziTypography.BodyStrong,
        Label = LeziTypography.Label,
        LabelLg = LeziTypography.LabelLg,
        Meta = LeziTypography.Meta,
        Micro = LeziTypography.Micro,
        Metric = LeziTypography.Metric,
        MetricSm = LeziTypography.MetricSm,
        MetricLg = LeziTypography.MetricLg,
        ChipMetric = LeziTypography.ChipMetric,
        Mono = LeziTypography.Mono,
        Eyebrow = LeziTypography.Eyebrow,
    )
    if (!elder) return base
    return LeziTypeScale(
        Display = elderTextStyle(base.Display),
        Hero = elderTextStyle(base.Hero),
        Title = elderTextStyle(base.Title),
        TitleSm = elderTextStyle(base.TitleSm),
        Body = elderTextStyle(base.Body),
        BodyStrong = elderTextStyle(base.BodyStrong),
        Label = elderTextStyle(base.Label),
        LabelLg = elderTextStyle(base.LabelLg),
        Meta = elderTextStyle(base.Meta),
        Micro = elderTextStyle(base.Micro),
        Metric = elderTextStyle(base.Metric),
        MetricSm = elderTextStyle(base.MetricSm),
        MetricLg = elderTextStyle(base.MetricLg),
        ChipMetric = elderTextStyle(base.ChipMetric),
        Mono = elderTextStyle(base.Mono),
        Eyebrow = elderTextStyle(base.Eyebrow),
    )
}

private fun elderTextStyle(style: TextStyle): TextStyle {
    val minLine = style.fontSize * 1.5f
    val lineHeight = if (spValue(style.lineHeight) + 0.001f >= spValue(minLine)) {
        style.lineHeight
    } else {
        minLine
    }
    val letterSpacing = if (spValue(style.letterSpacing) < 0f) 0.sp else style.letterSpacing
    return style.copy(
        fontFamily = FontFamily.SansSerif,
        lineHeight = lineHeight,
        letterSpacing = letterSpacing,
    )
}

private fun spValue(unit: TextUnit): Float =
    if (unit.type == TextUnitType.Sp) unit.value else Float.NEGATIVE_INFINITY
