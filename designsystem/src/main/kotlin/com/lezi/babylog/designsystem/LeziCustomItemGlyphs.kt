package com.lezi.babylog.designsystem

import android.graphics.Path as AndroidPath
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.PathParser

/**
 * Number of custom item icon slots (0..7). The slot order — star, heart, sun,
 * moon, music, circle, triangle, diamond — is the wire contract shared by the
 * record catalog.
 */
const val CUSTOM_ITEM_GLYPH_COUNT = 8

/**
 * Vector paths for custom record item icons, drawn in the same 24dp stroke
 * language as [LeziRecordGlyphIcon]. They replace the "★♥☀☾♪●▲◆" text glyphs,
 * which rendered per system font and mismatched the record glyph stroke weight.
 */
private val CustomItemGlyphPaths = listOf(
    // 0 ★ star
    "M12 3.5l2.6 5.4 5.9.8-4.3 4.1 1 5.9L12 16.9l-5.2 2.8 1-5.9-4.3-4.1 5.9-.8Z",
    // 1 ♥ heart
    "M12 20.5S4.5 15.6 4.5 9.8c0-2.9 2.2-4.8 4.6-4.8 1.5 0 2.4.7 2.9 1.5.5-.8 1.4-1.5 2.9-1.5 " +
        "c2.4 0 4.6 1.9 4.6 4.8 0 5.8-7.5 10.7-7.5 10.7Z",
    // 2 ☀ sun
    "M16 12a4 4 0 1 1-8 0 4 4 0 0 1 8 0 M12 2.5v2.5 M12 19v2.5 M2.5 12H5 M19 12h2.5 " +
        "M5.3 5.3l1.8 1.8 M16.9 16.9l1.8 1.8 M18.7 5.3l-1.8 1.8 M7.1 16.9l-1.8 1.8",
    // 3 ☾ moon
    "M20 15.2A8.2 8.2 0 0 1 8.8 4 8.2 8.2 0 1 0 20 15.2Z",
    // 4 ♪ music note
    "M9 18V6l10-2v11.5 M9 18a2.5 2.5 0 1 1-5 0 2.5 2.5 0 0 1 5 0 " +
        "M19 15.5a2.5 2.5 0 1 1-5 0 2.5 2.5 0 0 1 5 0",
    // 5 ● circle
    "M19 12a7 7 0 1 1-14 0 7 7 0 0 1 14 0",
    // 6 ▲ triangle
    "M12 4.5l8.5 15h-17Z",
    // 7 ◆ diamond
    "M12 3l7.5 9L12 21l-7.5-9Z",
)

/** Vector icon for a custom item slot, drawn in the [LeziRecordGlyphIcon] stroke language. */
@Composable
fun LeziCustomItemGlyphIcon(
    slot: Int,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    size: Dp = LeziIconSize.Glyph,
) {
    val safeSlot = slot.coerceIn(0, CustomItemGlyphPaths.lastIndex)
    val path = remember(safeSlot) {
        (PathParser.createPathFromPathData(CustomItemGlyphPaths[safeSlot]) ?: AndroidPath())
            .asComposePath()
    }
    Canvas(modifier.size(size)) {
        val factor = this.size.minDimension / 24f
        val xOffset = (this.size.width - 24f * factor) / 2f
        val yOffset = (this.size.height - 24f * factor) / 2f
        withTransform({
            translate(xOffset, yOffset)
            scale(factor, factor, pivot = Offset.Zero)
        }) {
            drawPath(
                path = path,
                color = tint,
                style = Stroke(
                    width = 1.8f,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
        }
    }
}
