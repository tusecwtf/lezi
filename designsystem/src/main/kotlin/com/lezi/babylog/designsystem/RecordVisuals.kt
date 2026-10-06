package com.lezi.babylog.designsystem

import android.graphics.Path as AndroidPath
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.PathParser

enum class LeziRecordGlyph {
    Nursing,
    Bottle,
    Pump,
    Pee,
    Poop,
    Sleep,
    Temperature,
    Note,
    Bath,
    Walk,
    Health,
    Medicine,
    Hospital,
    Growth,
    Food,
    Vaccine,
    Other,
}

/** Semantic colors stay independent from a glyph so every surface uses the same visual language. */
enum class LeziRecordColorRole {
    Nursing,
    Milk,
    Sleep,
    Wake,
    Pee,
    Poop,
    Temperature,
    Care,
    Growth,
}

@Composable
fun leziRecordColor(role: LeziRecordColorRole): Color {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return resolveLeziRecordColor(role, darkTheme = dark)
}

internal fun resolveLeziRecordColor(
    role: LeziRecordColorRole,
    darkTheme: Boolean,
): Color {
    return when (role) {
        LeziRecordColorRole.Nursing -> if (darkTheme) Color(0xFFEF8798) else Color(0xFFDE6F83)
        LeziRecordColorRole.Milk -> if (darkTheme) Color(0xFFF3BD6B) else Color(0xFFD99534)
        LeziRecordColorRole.Sleep -> if (darkTheme) Color(0xFFAA9BE4) else Color(0xFF806FC4)
        LeziRecordColorRole.Wake -> if (darkTheme) Color(0xFF82C7E2) else Color(0xFF55A8C7)
        LeziRecordColorRole.Pee -> if (darkTheme) Color(0xFF79D4BA) else Color(0xFF45AD8F)
        LeziRecordColorRole.Poop -> if (darkTheme) Color(0xFFD9AF66) else Color(0xFFA87832)
        LeziRecordColorRole.Temperature -> if (darkTheme) Color(0xFFFF897E) else Color(0xFFC85A4A)
        LeziRecordColorRole.Care -> if (darkTheme) Color(0xFFA8B8CD) else Color(0xFF667C98)
        LeziRecordColorRole.Growth -> if (darkTheme) Color(0xFF7CD6A1) else Color(0xFF3F9B6A)
    }
}

private val LightFoodSummaryLaneColors = listOf(
    resolveLeziRecordColor(LeziRecordColorRole.Milk, darkTheme = false),
    resolveLeziRecordColor(LeziRecordColorRole.Sleep, darkTheme = false),
    resolveLeziRecordColor(LeziRecordColorRole.Pee, darkTheme = false),
    LeziColors.FoodSummaryOther,
)

private val DarkFoodSummaryLaneColors = listOf(
    resolveLeziRecordColor(LeziRecordColorRole.Milk, darkTheme = true),
    resolveLeziRecordColor(LeziRecordColorRole.Sleep, darkTheme = true),
    resolveLeziRecordColor(LeziRecordColorRole.Pee, darkTheme = true),
    LeziColors.DarkFoodSummaryOther,
)

/** Fixed rank colors for the 辅食 summary: top three semantic lanes plus neutral 其他. */
fun foodSummaryLaneColors(darkTheme: Boolean): List<Color> =
    if (darkTheme) DarkFoodSummaryLaneColors else LightFoodSummaryLaneColors

@Composable
fun LeziRecordGlyphIcon(
    glyph: LeziRecordGlyph,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    size: Dp = LeziIconSize.Glyph,
) {
    val shape = remember(glyph) { glyphShape(glyph) }
    Canvas(modifier.size(size)) {
        val factor = this.size.minDimension / 24f
        val xOffset = (this.size.width - 24f * factor) / 2f
        val yOffset = (this.size.height - 24f * factor) / 2f
        withTransform({
            translate(xOffset, yOffset)
            scale(factor, factor, pivot = Offset.Zero)
        }) {
            shape.stroke?.let {
                drawPath(
                    path = it,
                    color = tint,
                    style = Stroke(
                        width = 1.8f,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
            }
            shape.fill?.let { drawPath(path = it, color = tint) }
        }
    }
}

@Composable
fun LeziPeeAmountMark(
    level: Int,
    modifier: Modifier = Modifier,
    color: Color = leziRecordColor(LeziRecordColorRole.Pee),
) {
    Canvas(modifier) {
        val fraction = when (level.coerceIn(1, 3)) {
            1 -> 0.25f
            2 -> 0.34f
            else -> 0.43f
        }
        drawCircle(
            color = color,
            radius = size.minDimension * fraction,
            center = center,
        )
    }
}

@Composable
fun LeziStoolAmountMark(
    level: Int,
    modifier: Modifier = Modifier,
    color: Color = leziRecordColor(LeziRecordColorRole.Poop),
) {
    Canvas(modifier) {
        drawStoolBlob(
            color = color,
            scale = when (level.coerceIn(1, 4)) {
                1 -> 0.45f
                2 -> 0.62f
                3 -> 0.79f
                else -> 0.96f
            },
        )
    }
}

@Composable
fun LeziStoolConsistencyMark(
    level: Int,
    modifier: Modifier = Modifier,
    color: Color = leziRecordColor(LeziRecordColorRole.Poop),
) {
    Canvas(modifier) {
        when (level.coerceIn(1, 4)) {
            1 -> {
                val path = Path().apply {
                    moveTo(center.x, size.height * 0.12f)
                    cubicTo(
                        size.width * 0.76f,
                        size.height * 0.42f,
                        size.width * 0.82f,
                        size.height * 0.64f,
                        center.x,
                        size.height * 0.86f,
                    )
                    cubicTo(
                        size.width * 0.18f,
                        size.height * 0.64f,
                        size.width * 0.24f,
                        size.height * 0.42f,
                        center.x,
                        size.height * 0.12f,
                    )
                    close()
                }
                drawPath(path, color)
            }
            2 -> drawStoolBlob(color = color, scale = 0.94f, heightRatio = 0.55f)
            3 -> drawStoolBlob(color = color, scale = 0.72f, heightRatio = 0.9f)
            else -> {
                val r = size.minDimension * 0.13f
                drawCircle(color, r, Offset(size.width * 0.28f, size.height * 0.58f))
                drawCircle(color, r * 1.1f, Offset(size.width * 0.52f, size.height * 0.42f))
                drawCircle(color, r * 0.92f, Offset(size.width * 0.73f, size.height * 0.62f))
            }
        }
    }
}

@Composable
fun LeziStoolColorMark(
    colorIndex: Int,
    modifier: Modifier = Modifier,
) {
    val fill = leziStoolColor(colorIndex)
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val outline = when (colorIndex.coerceIn(0, 7)) {
        0 -> MaterialTheme.colorScheme.outline
        // Slot 7 (black swatch): the outline was historically picked by background
        // luminance at this call site; LeziStoolPalette.outline encodes that same
        // dark/light pair, so the luminance rule now lives in the token.
        else -> LeziStoolPalette.outline(colorIndex, darkTheme)
    }
    Canvas(modifier) {
        drawStoolBlob(
            color = if (colorIndex == 0) Color.Transparent else fill,
            scale = 0.88f,
            outline = outline,
        )
    }
}

@Composable
fun leziStoolColor(index: Int): Color = when (index.coerceIn(0, 7)) {
    0 -> MaterialTheme.colorScheme.surface
    else -> LeziStoolPalette.fill(
        slot = index,
        darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f,
    )
}

private data class GlyphShape(
    val stroke: Path?,
    val fill: Path? = null,
)

/** Parsed once per process. Paths are density-independent and only read after parse. */
private val glyphShapes = java.util.concurrent.ConcurrentHashMap<LeziRecordGlyph, GlyphShape>()

private fun glyphShape(glyph: LeziRecordGlyph): GlyphShape =
    glyphShapes.getOrPut(glyph) { parseGlyphShape(glyph) }

private fun parseGlyphShape(glyph: LeziRecordGlyph): GlyphShape {
    val strokeData = when (glyph) {
        LeziRecordGlyph.Nursing ->
            "M12 8a3 3 0 1 1-6 0 3 3 0 0 1 6 0 " +
                "M18 10.5a2.5 2.5 0 1 1-5 0 2.5 2.5 0 0 1 5 0 " +
                "M4 20c.2-4.2 2-6.4 5.2-6.4 2.6 0 4.3 1.4 5 4.2 " +
                "M11.5 20c.4-3.2 1.8-4.7 4.2-4.7 2.5 0 3.8 1.6 4.3 4.7"
        LeziRecordGlyph.Bottle ->
            "M9 3h6v4l2 3v9a2 2 0 0 1-2 2H9a2 2 0 0 1-2-2v-9l2-3V3Z M8 13h8 M10 6h4"
        LeziRecordGlyph.Pump ->
            "M7 4h7v5H7z M10.5 9v3 M7 12h7v8H7z M16 7h3v11h-3 M14 15h2"
        LeziRecordGlyph.Pee ->
            "M8 4c2.3 3.2 3.4 5.5 3.4 7.3A3.4 3.4 0 0 1 4.6 11C4.6 9.4 5.7 7 8 4Z " +
                "M16 7c2.2 3 3.2 5.1 3.2 6.8a3.2 3.2 0 0 1-6.4 0C12.8 12.1 13.8 10 16 7Z"
        LeziRecordGlyph.Poop ->
            "M9.2 8.1c.1-2.1 1.5-3.5 3.6-3.8.2 1.5.9 2.4 2.1 2.9 2.2.1 3.6 1.3 3.9 3.3 " +
                "1.8.4 2.7 1.7 2.5 3.7-.3 2.5-2.1 3.9-5.3 3.9H8.1c-2.9 0-4.5-1.3-4.5-3.7 " +
                "0-2 .9-3.2 2.9-3.6.2-1.4.8-2.5 1.7-3.3.3-.3.6-.5 1-.6Z M11 15.6c.4.45 1.6.45 2 0"
        LeziRecordGlyph.Sleep ->
            "M20 15.2A8.2 8.2 0 0 1 8.8 4 8.2 8.2 0 1 0 20 15.2Z"
        LeziRecordGlyph.Temperature ->
            "M14 14.8V5a3 3 0 0 0-6 0v9.8a5 5 0 1 0 6 0Z M11 7v9"
        LeziRecordGlyph.Note ->
            "M5 4h14v16H5z M8 8h8 M8 12h8 M8 16h5"
        LeziRecordGlyph.Bath ->
            "M3 12h18 M5 12v5a3 3 0 0 0 3 3h8a3 3 0 0 0 3-3v-5 M8 9V6a3 3 0 0 1 6 0"
        LeziRecordGlyph.Walk ->
            "M14 5a2 2 0 1 1-4 0 2 2 0 0 1 4 0 M9 21l2-7 2-4 3 4 M13 14l2 7 M8 12l3-3 3 2"
        LeziRecordGlyph.Health ->
            "M12 8v5 M12 17h.01 M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0"
        LeziRecordGlyph.Medicine ->
            "M9 7h6a5 5 0 0 1 0 10H9A5 5 0 0 1 9 7Z M9 8l6 8"
        LeziRecordGlyph.Hospital ->
            "M5 4h14v16H5z M12 8v8 M8 12h8"
        LeziRecordGlyph.Growth ->
            "M4 18l5-5 3 3 7-8 M15 8h4v4"
        LeziRecordGlyph.Food ->
            "M7 3v8 M4 3v5c0 2 1 3 3 3s3-1 3-3V3 M7 11v10 M16 3v18 M16 3c3 2 4 5 4 8h-4"
        LeziRecordGlyph.Vaccine ->
            "M5 19l9-9 M12 5l7 7 M15 2l7 7 M4 20l-2 2 M8 16l-2-2"
        LeziRecordGlyph.Other -> null
    }
    val fillData = when (glyph) {
        LeziRecordGlyph.Poop ->
            "M10.75 14.2a.55.55 0 1 1-1.1 0 .55.55 0 0 1 1.1 0 " +
                "M14.35 14.2a.55.55 0 1 1-1.1 0 .55.55 0 0 1 1.1 0"
        LeziRecordGlyph.Other ->
            "M6 12a1 1 0 1 1-2 0 1 1 0 0 1 2 0 " +
                "M13 12a1 1 0 1 1-2 0 1 1 0 0 1 2 0 " +
                "M20 12a1 1 0 1 1-2 0 1 1 0 0 1 2 0"
        else -> null
    }
    return GlyphShape(
        stroke = strokeData?.toComposePath(),
        fill = fillData?.toComposePath(),
    )
}

private fun String.toComposePath(): Path =
    (PathParser.createPathFromPathData(this) ?: AndroidPath()).asComposePath()

private fun DrawScope.drawStoolBlob(
    color: Color,
    scale: Float,
    heightRatio: Float = 0.72f,
    outline: Color? = null,
) {
    val width = size.width * scale
    val height = size.height * scale * heightRatio
    val left = (size.width - width) / 2f
    val top = (size.height - height) / 2f
    val path = Path().apply {
        moveTo(left + width * 0.22f, top + height * 0.35f)
        cubicTo(
            left + width * 0.22f,
            top + height * 0.08f,
            left + width * 0.48f,
            top,
            left + width * 0.60f,
            top + height * 0.20f,
        )
        cubicTo(
            left + width * 0.88f,
            top + height * 0.18f,
            left + width,
            top + height * 0.48f,
            left + width * 0.87f,
            top + height * 0.66f,
        )
        cubicTo(
            left + width * 0.94f,
            top + height,
            left + width * 0.66f,
            top + height,
            left + width * 0.50f,
            top + height * 0.91f,
        )
        cubicTo(
            left + width * 0.17f,
            top + height,
            left,
            top + height * 0.78f,
            left + width * 0.10f,
            top + height * 0.55f,
        )
        cubicTo(
            left,
            top + height * 0.46f,
            left + width * 0.08f,
            top + height * 0.37f,
            left + width * 0.22f,
            top + height * 0.35f,
        )
        close()
    }
    if (color.alpha > 0f) drawPath(path, color)
    if (outline != null) {
        drawPath(
            path,
            outline,
            style = Stroke(
                width = 1.4.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}
