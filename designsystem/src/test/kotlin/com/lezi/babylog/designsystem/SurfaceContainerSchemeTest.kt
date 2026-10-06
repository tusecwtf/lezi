package com.lezi.babylog.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for the M3 tonal surface roles (0.5.4 ticket 13): every
 * `surfaceContainer*` / `surfaceBright` / `surfaceDim` / `surfaceTint` slot of all
 * four schemes resolves from [LeziColors] (single source) instead of the M3
 * baseline palette, so dark bottom sheets and the clock dial stay in the brand
 * blue-black (#15202C) family rather than the M3 purple-black (#211F26) family,
 * while light containers keep WCAG-grade contrast against onSurface.
 */
class SurfaceContainerSchemeTest {

    private data class ResolvedSlots(
        val lowest: Color,
        val low: Color,
        val container: Color,
        val high: Color,
        val highest: Color,
        val bright: Color,
        val dim: Color,
        val tint: Color,
    )

    private fun ResolvedSlots.family(): List<Color> = listOf(
        lowest, low, container, high, highest, bright, dim,
    )

    private fun expectedSlots(darkTheme: Boolean, style: LeziVisualStyle): ResolvedSlots =
        when (style) {
            LeziVisualStyle.Warm ->
                if (darkTheme) {
                    ResolvedSlots(
                        lowest = LeziColors.DarkSurfaceContainerLowest,
                        low = LeziColors.DarkSurfaceContainerLow,
                        container = LeziColors.DarkSurfaceContainer,
                        high = LeziColors.DarkSurfaceContainerHigh,
                        highest = LeziColors.DarkSurfaceContainerHighest,
                        bright = LeziColors.DarkSurfaceBright,
                        dim = LeziColors.DarkSurfaceDim,
                        tint = LeziColors.DarkAccent,
                    )
                } else {
                    ResolvedSlots(
                        lowest = LeziColors.SurfaceContainerLowest,
                        low = LeziColors.SurfaceContainerLow,
                        container = LeziColors.SurfaceContainer,
                        high = LeziColors.SurfaceContainerHigh,
                        highest = LeziColors.SurfaceContainerHighest,
                        bright = LeziColors.SurfaceBright,
                        dim = LeziColors.SurfaceDim,
                        tint = LeziColors.Accent,
                    )
                }
            LeziVisualStyle.Journal ->
                if (darkTheme) {
                    ResolvedSlots(
                        lowest = LeziColors.JournalDarkSurfaceContainerLowest,
                        low = LeziColors.JournalDarkSurfaceContainerLow,
                        container = LeziColors.JournalDarkSurfaceContainer,
                        high = LeziColors.JournalDarkSurfaceContainerHigh,
                        highest = LeziColors.JournalDarkSurfaceContainerHighest,
                        bright = LeziColors.JournalDarkSurfaceBright,
                        dim = LeziColors.JournalDarkSurfaceDim,
                        tint = LeziColors.JournalDarkAccent,
                    )
                } else {
                    ResolvedSlots(
                        lowest = LeziColors.JournalSurfaceContainerLowest,
                        low = LeziColors.JournalSurfaceContainerLow,
                        container = LeziColors.JournalSurfaceContainer,
                        high = LeziColors.JournalSurfaceContainerHigh,
                        highest = LeziColors.JournalSurfaceContainerHighest,
                        bright = LeziColors.JournalSurfaceBright,
                        dim = LeziColors.JournalSurfaceDim,
                        tint = LeziColors.JournalPrimary,
                    )
                }
        }

    private fun actualSlots(scheme: ColorScheme): ResolvedSlots = ResolvedSlots(
        lowest = scheme.surfaceContainerLowest,
        low = scheme.surfaceContainerLow,
        container = scheme.surfaceContainer,
        high = scheme.surfaceContainerHigh,
        highest = scheme.surfaceContainerHighest,
        bright = scheme.surfaceBright,
        dim = scheme.surfaceDim,
        tint = scheme.surfaceTint,
    )

    @Test
    fun `gradient tokens pin the hand-tuned ticket literals`() {
        // Warm light — cream ladder under Surface FEFcf9 / Bg FBF7EE.
        assertEquals(Color(0xFFFFFFFF), LeziColors.SurfaceContainerLowest)
        assertEquals(Color(0xFFF8F3E9), LeziColors.SurfaceContainerLow)
        assertEquals(Color(0xFFF4EDDE), LeziColors.SurfaceContainer)
        assertEquals(Color(0xFFEFE7D4), LeziColors.SurfaceContainerHigh)
        assertEquals(Color(0xFFE9DFC9), LeziColors.SurfaceContainerHighest)
        assertEquals(Color(0xFFFFFDF8), LeziColors.SurfaceBright)
        assertEquals(Color(0xFFE6DCC6), LeziColors.SurfaceDim)
        // Warm dark — brand blue-black ladder around DarkSurface 15202C.
        assertEquals(Color(0xFF0A121B), LeziColors.DarkSurfaceContainerLowest)
        assertEquals(Color(0xFF19242F), LeziColors.DarkSurfaceContainerLow)
        assertEquals(Color(0xFF1D2A36), LeziColors.DarkSurfaceContainer)
        assertEquals(Color(0xFF232F3C), LeziColors.DarkSurfaceContainerHigh)
        assertEquals(Color(0xFF293744), LeziColors.DarkSurfaceContainerHighest)
        assertEquals(Color(0xFF31404F), LeziColors.DarkSurfaceBright)
        assertEquals(Color(0xFF0E1721), LeziColors.DarkSurfaceDim)
        // Journal light — cool paper-gray ladder.
        assertEquals(Color(0xFFFFFFFF), LeziColors.JournalSurfaceContainerLowest)
        assertEquals(Color(0xFFF3F2F4), LeziColors.JournalSurfaceContainerLow)
        assertEquals(Color(0xFFEDECEF), LeziColors.JournalSurfaceContainer)
        assertEquals(Color(0xFFE6E4E9), LeziColors.JournalSurfaceContainerHigh)
        assertEquals(Color(0xFFDFDCE1), LeziColors.JournalSurfaceContainerHighest)
        assertEquals(Color(0xFFFBFAFB), LeziColors.JournalSurfaceBright)
        assertEquals(Color(0xFFD8D5DB), LeziColors.JournalSurfaceDim)
        // Journal dark — neutral charcoal ladder.
        assertEquals(Color(0xFF1B1C1C), LeziColors.JournalDarkSurfaceContainerLowest)
        assertEquals(Color(0xFF2A2B2B), LeziColors.JournalDarkSurfaceContainerLow)
        assertEquals(Color(0xFF2E2F30), LeziColors.JournalDarkSurfaceContainer)
        assertEquals(Color(0xFF343638), LeziColors.JournalDarkSurfaceContainerHigh)
        assertEquals(Color(0xFF3E4040), LeziColors.JournalDarkSurfaceContainerHighest)
        assertEquals(Color(0xFF47494B), LeziColors.JournalDarkSurfaceBright)
        assertEquals(Color(0xFF202121), LeziColors.JournalDarkSurfaceDim)
        // Hoisted journal-light scheme primary (was inline in Theme.kt).
        assertEquals(Color(0xFFB7445A), LeziColors.JournalPrimary)
    }

    @Test
    fun `all four schemes resolve every surface slot from LeziColors`() {
        LeziVisualStyle.entries.forEach { style ->
            listOf(false, true).forEach { darkTheme ->
                val scheme = resolveLeziColorScheme(darkTheme, style, babyThemeArgb = null)
                val label = "$style dark=$darkTheme"
                assertEquals("$label slots", expectedSlots(darkTheme, style), actualSlots(scheme))
                // surfaceTint keeps the M3 "tint follows primary" semantics per scheme.
                assertEquals("$label tint follows primary", scheme.primary, scheme.surfaceTint)
            }
        }
    }

    @Test
    fun `surfaceTint explicit values preserve the material default tie to primary`() {
        // Parity guard: material3 light/darkColorScheme default surfaceTint to the
        // primary parameter, so the explicit LeziColors assignments are
        // behavior-preserving. If this probe fails on a dependency upgrade, the
        // explicit assignment is a deliberate override — re-pin deliberately.
        val probePrimary = Color(0xFF112233)
        assertEquals(probePrimary, lightColorScheme(primary = probePrimary).surfaceTint)
        val probeDarkPrimary = Color(0xFF445566)
        assertEquals(probeDarkPrimary, darkColorScheme(primary = probeDarkPrimary).surfaceTint)
    }

    @Test
    fun `dark surface container family leaves the M3 purple-black baseline`() {
        // Self-documenting baseline anchors: material3 1.3.x dark defaults.
        val baseline = darkColorScheme()
        assertEquals(Color(0xFF211F26), baseline.surfaceContainer)
        assertEquals(Color(0xFFD0BCFF), baseline.surfaceTint)

        LeziVisualStyle.entries.forEach { style ->
            val scheme = resolveLeziColorScheme(darkTheme = true, style = style, babyThemeArgb = null)
            val slots = actualSlots(scheme)
            val expected = expectedSlots(darkTheme = true, style = style)
            slots.family().zip(expected.family()).forEach { (actual, wanted) ->
                assertEquals("$style slot", wanted, actual)
                assertNotEquals("$style must leave the M3 baseline", baseline.surfaceContainerLowest, actual)
                assertNotEquals("$style must leave the M3 baseline", baseline.surfaceContainerLow, actual)
                assertNotEquals("$style must leave the M3 baseline", baseline.surfaceContainer, actual)
                assertNotEquals("$style must leave the M3 baseline", baseline.surfaceContainerHigh, actual)
                assertNotEquals("$style must leave the M3 baseline", baseline.surfaceContainerHighest, actual)
                assertNotEquals("$style must leave the M3 baseline", baseline.surfaceBright, actual)
                assertNotEquals("$style must leave the M3 baseline", baseline.surfaceDim, actual)
                assertNotEquals("$style must leave the M3 baseline", baseline.surfaceTint, actual)
            }
        }
    }

    @Test
    fun `light surface container family leaves the M3 baseline except deliberate white lowest`() {
        val baseline = lightColorScheme()
        assertEquals(Color(0xFFF3EDF7), baseline.surfaceContainer)

        LeziVisualStyle.entries.forEach { style ->
            val scheme = resolveLeziColorScheme(darkTheme = false, style = style, babyThemeArgb = null)
            val slots = actualSlots(scheme)
            val expected = expectedSlots(darkTheme = false, style = style)
            slots.family().zip(expected.family()).forEach { (actual, wanted) ->
                assertEquals("$style slot", wanted, actual)
                assertNotEquals("$style must leave the M3 baseline", baseline.surfaceContainerLow, actual)
                assertNotEquals("$style must leave the M3 baseline", baseline.surfaceContainer, actual)
                assertNotEquals("$style must leave the M3 baseline", baseline.surfaceContainerHigh, actual)
                assertNotEquals("$style must leave the M3 baseline", baseline.surfaceContainerHighest, actual)
                assertNotEquals("$style must leave the M3 baseline", baseline.surfaceBright, actual)
                assertNotEquals("$style must leave the M3 baseline", baseline.surfaceDim, actual)
                assertNotEquals("$style must leave the M3 baseline", baseline.surfaceTint, actual)
            }
            // Pure-white lowest matches the M3 light default on purpose (deepest well).
            assertEquals(baseline.surfaceContainerLowest, slots.lowest)
        }
    }

    @Test
    fun `warm dark family stays blue-black and journal dark stays cool charcoal`() {
        fun blueShifted(c: Color, strict: Boolean) =
            if (strict) c.blue > c.green && c.green > c.red else c.blue >= c.green && c.green >= c.red

        listOf(
            LeziColors.DarkSurfaceContainerLowest,
            LeziColors.DarkSurfaceContainerLow,
            LeziColors.DarkSurfaceContainer,
            LeziColors.DarkSurfaceContainerHigh,
            LeziColors.DarkSurfaceContainerHighest,
            LeziColors.DarkSurfaceBright,
            LeziColors.DarkSurfaceDim,
        ).forEach { c ->
            assertTrue("warm dark $c must stay in the blue-black hue", blueShifted(c, strict = true))
        }
        listOf(
            LeziColors.JournalDarkSurfaceContainerLowest,
            LeziColors.JournalDarkSurfaceContainerLow,
            LeziColors.JournalDarkSurfaceContainer,
            LeziColors.JournalDarkSurfaceContainerHigh,
            LeziColors.JournalDarkSurfaceContainerHighest,
            LeziColors.JournalDarkSurfaceBright,
            LeziColors.JournalDarkSurfaceDim,
        ).forEach { c ->
            assertTrue("journal dark $c must stay cool-neutral", blueShifted(c, strict = false))
        }
    }

    @Test
    fun `container gradients step monotonically per mode around the scheme surface`() {
        LeziVisualStyle.entries.forEach { style ->
            listOf(false, true).forEach { darkTheme ->
                val scheme = resolveLeziColorScheme(darkTheme, style, babyThemeArgb = null)
                val s = actualSlots(scheme)
                val surface = scheme.surface.luminance()
                val label = "$style dark=$darkTheme"
                if (darkTheme) {
                    // darkest → lightest: lowest < dim < surface < low < container < high < highest < bright
                    assertTrue("$label lowest<dim", s.lowest.luminance() < s.dim.luminance())
                    assertTrue("$label dim<surface", s.dim.luminance() < surface)
                    assertTrue("$label surface<low", surface < s.low.luminance())
                    assertTrue("$label low<container", s.low.luminance() < s.container.luminance())
                    assertTrue("$label container<high", s.container.luminance() < s.high.luminance())
                    assertTrue("$label high<highest", s.high.luminance() < s.highest.luminance())
                    assertTrue("$label highest<bright", s.highest.luminance() < s.bright.luminance())
                } else {
                    // lightest → deepest: lowest > bright > surface > low > container > high > highest > dim
                    assertTrue("$label lowest>bright", s.lowest.luminance() > s.bright.luminance())
                    assertTrue("$label bright>surface", s.bright.luminance() > surface)
                    assertTrue("$label surface>low", surface > s.low.luminance())
                    assertTrue("$label low>container", s.low.luminance() > s.container.luminance())
                    assertTrue("$label container>high", s.container.luminance() > s.high.luminance())
                    assertTrue("$label high>highest", s.high.luminance() > s.highest.luminance())
                    assertTrue("$label highest>dim", s.highest.luminance() > s.dim.luminance())
                }
            }
        }
    }

    @Test
    fun `containers keep four point five contrast with onSurface in both modes`() {
        LeziVisualStyle.entries.forEach { style ->
            listOf(false, true).forEach { darkTheme ->
                val scheme = resolveLeziColorScheme(darkTheme, style, babyThemeArgb = null)
                val slots = actualSlots(scheme)
                val label = "$style dark=$darkTheme"
                // Sheets, menus and the clock dial (surfaceContainerHighest + onSurface)
                // all render text on these grounds.
                (slots.family() + scheme.surface).forEach { ground ->
                    assertTrue(
                        "$label onSurface vs $ground",
                        contrastRatio(scheme.onSurface, ground) + 1e-4f >= 4.5f,
                    )
                }
            }
        }
    }

    @Test
    fun `design tokens json mirrors the warm surface container snapshot`() {
        // Dual-write contract (design/README): warm light/dark gradients live in both
        // Tokens.kt (Compose authority) and design/tokens.json (OD/agent snapshot).
        val json = repositoryRoot().resolve("design/tokens.json").readText()
        val block = json.substringAfter("\"surfaceContainer\"").substringBefore("\"space\"")
        fun assertHex(field: String, hex: String) {
            assertTrue(
                "surfaceContainer.$field must be $hex",
                Regex(""""$field"\s*:\s*"$hex"""" ).containsMatchIn(block),
            )
        }
        // Warm light.
        assertHex("lightLowest", "#FFFFFF")
        assertHex("lightLow", "#F8F3E9")
        assertHex("lightContainer", "#F4EDDE")
        assertHex("lightHigh", "#EFE7D4")
        assertHex("lightHighest", "#E9DFC9")
        assertHex("lightBright", "#FFFDF8")
        assertHex("lightDim", "#E6DCC6")
        assertHex("lightTint", "#007BAE")
        // Warm dark.
        assertHex("darkLowest", "#0A121B")
        assertHex("darkLow", "#19242F")
        assertHex("darkContainer", "#1D2A36")
        assertHex("darkHigh", "#232F3C")
        assertHex("darkHighest", "#293744")
        assertHex("darkBright", "#31404F")
        assertHex("darkDim", "#0E1721")
        assertHex("darkTint", "#60B3DC")
    }

    private fun repositoryRoot(): File = generateSequence(
        seed = File(requireNotNull(System.getProperty("user.dir"))),
        nextFunction = { it.parentFile },
    ).first { candidate -> candidate.resolve("settings.gradle.kts").isFile }
}
