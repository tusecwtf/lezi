package com.lezi.babylog.designsystem

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordSemanticColorPolicyTest {
    @Test
    fun `record roles resolve through one light and dark palette`() {
        val light = mapOf(
            LeziRecordColorRole.Nursing to Color(0xFFDE6F83),
            LeziRecordColorRole.Milk to Color(0xFFD99534),
            LeziRecordColorRole.Sleep to Color(0xFF806FC4),
            LeziRecordColorRole.Wake to Color(0xFF55A8C7),
            LeziRecordColorRole.Pee to Color(0xFF45AD8F),
            LeziRecordColorRole.Poop to Color(0xFFA87832),
            LeziRecordColorRole.Temperature to Color(0xFFC85A4A),
            LeziRecordColorRole.Care to Color(0xFF667C98),
            LeziRecordColorRole.Growth to Color(0xFF3F9B6A),
        )
        val dark = mapOf(
            LeziRecordColorRole.Nursing to Color(0xFFEF8798),
            LeziRecordColorRole.Milk to Color(0xFFF3BD6B),
            LeziRecordColorRole.Sleep to Color(0xFFAA9BE4),
            LeziRecordColorRole.Wake to Color(0xFF82C7E2),
            LeziRecordColorRole.Pee to Color(0xFF79D4BA),
            LeziRecordColorRole.Poop to Color(0xFFD9AF66),
            LeziRecordColorRole.Temperature to Color(0xFFFF897E),
            LeziRecordColorRole.Care to Color(0xFFA8B8CD),
            LeziRecordColorRole.Growth to Color(0xFF7CD6A1),
        )

        light.forEach { (role, expected) ->
            assertEquals("light $role", expected, resolveLeziRecordColor(role, darkTheme = false))
        }
        dark.forEach { (role, expected) ->
            assertEquals("dark $role", expected, resolveLeziRecordColor(role, darkTheme = true))
        }
    }

    @Test
    fun `timeline aliases use record roles in every theme`() {
        LeziVisualStyle.entries.forEach { style ->
            listOf(false, true).forEach { darkTheme ->
                val colors = resolveLeziExtendedColors(
                    darkTheme = darkTheme,
                    style = style,
                    babyThemeArgb = null,
                    fallbackAccent = Color.Magenta,
                )

                assertEquals(
                    "$style dark=$darkTheme sleep",
                    resolveLeziRecordColor(LeziRecordColorRole.Sleep, darkTheme),
                    colors.laneSleep,
                )
                assertEquals(
                    "$style dark=$darkTheme feed",
                    resolveLeziRecordColor(LeziRecordColorRole.Milk, darkTheme),
                    colors.laneFeed,
                )
                assertEquals(
                    "$style dark=$darkTheme care",
                    resolveLeziRecordColor(LeziRecordColorRole.Pee, darkTheme),
                    colors.laneCare,
                )
            }
        }
    }
}
