package com.lezi.babylog.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Compose stroke glyphs used where emoji would break the visual language. */
@Composable
fun LeziGlyphIcon(
    kind: LeziGlyph,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    size: Dp = 15.dp,
) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        val stroke = (s * 0.11f).coerceAtLeast(1.6f)
        val style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun sx(x: Float) = x / 24f * s
        fun sy(y: Float) = y / 24f * s
        when (kind) {
            LeziGlyph.Bottle -> {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(sx(9f), sy(3f))
                    lineTo(sx(15f), sy(3f))
                    lineTo(sx(15f), sy(7f))
                    lineTo(sx(17f), sy(10f))
                    lineTo(sx(17f), sy(19f))
                    cubicTo(sx(17f), sy(20.1f), sx(16.1f), sy(21f), sx(15f), sy(21f))
                    lineTo(sx(9f), sy(21f))
                    cubicTo(sx(7.9f), sy(21f), sx(7f), sy(20.1f), sx(7f), sy(19f))
                    lineTo(sx(7f), sy(10f))
                    lineTo(sx(9f), sy(7f))
                    lineTo(sx(9f), sy(3f))
                    close()
                }
                drawPath(path, tint, style = style)
                drawLine(
                    tint,
                    Offset(sx(9f), sy(12f)),
                    Offset(sx(17f), sy(12f)),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
            LeziGlyph.Drop -> {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(sx(12f), sy(3f))
                    cubicTo(sx(15.4f), sy(7.1f), sx(17.2f), sy(10.2f), sx(17.2f), sy(13f))
                    cubicTo(sx(17.2f), sy(15.87f), sx(14.87f), sy(18.2f), sx(12f), sy(18.2f))
                    cubicTo(sx(9.13f), sy(18.2f), sx(6.8f), sy(15.87f), sx(6.8f), sy(13f))
                    cubicTo(sx(6.8f), sy(10.2f), sx(8.6f), sy(7.1f), sx(12f), sy(3f))
                    close()
                }
                drawPath(path, tint, style = style)
            }
            LeziGlyph.Moon -> {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(sx(20f), sy(15.5f))
                    cubicTo(sx(18.5f), sy(19.2f), sx(14.8f), sy(21.8f), sx(10.7f), sy(21.2f))
                    cubicTo(sx(6.6f), sy(20.6f), sx(3.4f), sy(17.4f), sx(2.8f), sy(13.3f))
                    cubicTo(sx(2.2f), sy(9.2f), sx(4.8f), sy(5.5f), sx(8.5f), sy(4f))
                    cubicTo(sx(7.2f), sy(7.8f), sx(8.1f), sy(12.2f), sx(11.3f), sy(14.7f))
                    cubicTo(sx(14.5f), sy(17.2f), sx(18.9f), sy(17.4f), sx(20f), sy(15.5f))
                    close()
                }
                drawPath(path, tint, style = style)
            }
            LeziGlyph.Toilet -> {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(sx(7f), sy(4f))
                    lineTo(sx(7f), sy(8.5f))
                    cubicTo(sx(7f), sy(11.26f), sx(9.24f), sy(13.5f), sx(12f), sy(13.5f))
                    cubicTo(sx(14.76f), sy(13.5f), sx(17f), sy(11.26f), sx(17f), sy(8.5f))
                    lineTo(sx(17f), sy(4f))
                    moveTo(sx(5f), sy(7f))
                    lineTo(sx(19f), sy(7f))
                    moveTo(sx(7f), sy(17f))
                    cubicTo(sx(9.7f), sy(18.3f), sx(14.3f), sy(18.3f), sx(17f), sy(17f))
                }
                drawPath(path, tint, style = style)
            }
            LeziGlyph.Pin -> {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(sx(7f), sy(4f))
                    lineTo(sx(7f), sy(8.5f))
                    cubicTo(sx(7f), sy(11.26f), sx(9.24f), sy(13.5f), sx(12f), sy(13.5f))
                    cubicTo(sx(14.76f), sy(13.5f), sx(17f), sy(11.26f), sx(17f), sy(8.5f))
                    lineTo(sx(17f), sy(4f))
                    moveTo(sx(5f), sy(7f))
                    lineTo(sx(19f), sy(7f))
                    moveTo(sx(7f), sy(17f))
                    cubicTo(sx(9.7f), sy(18.3f), sx(14.3f), sy(18.3f), sx(17f), sy(17f))
                    moveTo(sx(10f), sy(10f))
                    lineTo(sx(14f), sy(10f))
                }
                drawPath(path, tint, style = style)
            }
            LeziGlyph.Plus -> {
                drawLine(
                    tint,
                    Offset(sx(12f), sy(5f)),
                    Offset(sx(12f), sy(19f)),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    tint,
                    Offset(sx(5f), sy(12f)),
                    Offset(sx(19f), sy(12f)),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
            LeziGlyph.Dot -> {
                val radius = s * 0.14f
                drawCircle(
                    color = tint,
                    radius = radius,
                    center = Offset(s / 2f, s / 2f),
                    style = style,
                )
            }
        }
    }
}
