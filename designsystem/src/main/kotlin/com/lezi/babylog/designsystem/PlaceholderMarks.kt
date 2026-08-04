package com.lezi.babylog.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shared dot placeholder — the single visual language for "no icon here",
 * replacing the ad-hoc "·" / "•" text glyphs that rendered per system font.
 */
@Composable
fun LeziPlaceholderDot(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    size: Dp = 6.dp,
) {
    Canvas(modifier.size(size)) {
        drawCircle(color = color)
    }
}

/**
 * Vector status marks for [StateContainer] — ring / exclamation / filled dot / check,
 * replacing the "○ / ! / ● / ✓" text glyphs. Loading is a spinner at the call site.
 */
@Composable
internal fun LeziStateMark(
    kind: StateKind,
    color: Color,
    modifier: Modifier = Modifier,
    markSize: Dp = 30.dp,
) {
    Canvas(modifier.size(markSize)) {
        val strokeWidth = 2.4.dp.toPx()
        when (kind) {
            StateKind.Empty -> drawCircle(
                color = color,
                radius = (size.minDimension - strokeWidth) / 2f,
                style = Stroke(width = strokeWidth),
            )
            StateKind.Error -> {
                val w = size.width
                val h = size.height
                drawLine(
                    color = color,
                    start = Offset(w / 2f, h * 0.14f),
                    end = Offset(w / 2f, h * 0.58f),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
                drawCircle(
                    color = color,
                    radius = strokeWidth * 0.9f,
                    center = Offset(w / 2f, h * 0.82f),
                )
            }
            StateKind.Recording -> drawCircle(
                color = color,
                radius = size.minDimension * 0.34f,
            )
            StateKind.Success -> {
                val w = size.width
                val h = size.height
                drawLine(
                    color = color,
                    start = Offset(w * 0.18f, h * 0.54f),
                    end = Offset(w * 0.42f, h * 0.76f),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = color,
                    start = Offset(w * 0.42f, h * 0.76f),
                    end = Offset(w * 0.84f, h * 0.26f),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
            }
            StateKind.Loading -> Unit // Spinner is rendered by the caller.
        }
    }
}
