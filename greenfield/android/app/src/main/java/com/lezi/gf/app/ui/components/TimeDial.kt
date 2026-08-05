package com.lezi.gf.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lezi.gf.app.ui.theme.TimeDialModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Circular clock dial for composer timestamp adjustment (Spec 02 E1).
 * Primary path is drag on the ring; ±5 min buttons remain as a11y auxiliary.
 */
@Composable
fun TimeDial(
    timestampMs: Long,
    onTimestampChange: (Long) -> Unit,
    accent: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier,
    showA11ySteppers: Boolean = true,
) {
    val zone = remember { ZoneId.of("Asia/Shanghai") }
    val label = remember(timestampMs) {
        DateTimeFormatter.ofPattern("HH:mm")
            .withZone(zone)
            .format(Instant.ofEpochMilli(timestampMs))
    }
    var lastAngle by remember { mutableFloatStateOf(Float.NaN) }
    var liveMs by remember(timestampMs) { mutableLongStateOf(timestampMs) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "圆盘调时 $label" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("记录时刻", style = MaterialTheme.typography.labelMedium)
        Text(
            label,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = accent,
        )
        Box(
            Modifier
                .size(168.dp)
                .padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            val track = MaterialTheme.colorScheme.outlineVariant
            Canvas(
                Modifier
                    .size(152.dp)
                    .pointerInput(liveMs) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                val cx = size.width / 2f
                                val cy = size.height / 2f
                                lastAngle = atan2(offset.y - cy, offset.x - cx)
                            },
                            onDragEnd = {
                                lastAngle = Float.NaN
                                onTimestampChange(liveMs)
                            },
                            onDragCancel = { lastAngle = Float.NaN },
                            onDrag = { change, _ ->
                                change.consume()
                                val cx = size.width / 2f
                                val cy = size.height / 2f
                                val ang = atan2(change.position.y - cy, change.position.x - cx)
                                if (!lastAngle.isNaN()) {
                                    var delta = ang - lastAngle
                                    // unwrap to (-π, π]
                                    while (delta > PI) delta -= (2 * PI).toFloat()
                                    while (delta < -PI) delta += (2 * PI).toFloat()
                                    val minutes = ((delta / (2 * PI).toFloat()) * TimeDialModel.MINUTES_PER_TURN)
                                    val step = when {
                                        minutes >= 0.5f -> minutes.toInt().coerceAtLeast(1)
                                        minutes <= -0.5f -> minutes.toInt().coerceAtMost(-1)
                                        else -> 0
                                    }
                                    if (step != 0) {
                                        liveMs = TimeDialModel.applyMinutes(liveMs, step)
                                        onTimestampChange(liveMs)
                                        lastAngle = ang
                                    }
                                } else {
                                    lastAngle = ang
                                }
                            },
                        )
                    },
            ) {
                val stroke = 10.dp.toPx()
                val r = size.minDimension / 2f - stroke
                drawCircle(
                    color = track,
                    radius = r,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                // 12 ticks
                for (i in 0 until 12) {
                    val a = (i / 12f) * 2f * PI.toFloat() - PI.toFloat() / 2f
                    val inner = r - 8.dp.toPx()
                    val outer = r + 2.dp.toPx()
                    drawLine(
                        color = if (i % 3 == 0) accent else track,
                        start = Offset(center.x + cos(a) * inner, center.y + sin(a) * inner),
                        end = Offset(center.x + cos(a) * outer, center.y + sin(a) * outer),
                        strokeWidth = if (i % 3 == 0) 3f else 2f,
                        cap = StrokeCap.Round,
                    )
                }
                // hand points to minute-of-hour of liveMs
                val minute = ((liveMs / 60_000L) % 60).toInt()
                val handAngle = (minute / 60f) * 2f * PI.toFloat() - PI.toFloat() / 2f
                drawLine(
                    color = accent,
                    start = center,
                    end = Offset(
                        center.x + cos(handAngle) * (r - 16.dp.toPx()),
                        center.y + sin(handAngle) * (r - 16.dp.toPx()),
                    ),
                    strokeWidth = 5f,
                    cap = StrokeCap.Round,
                )
                drawCircle(color = accent, radius = 6.dp.toPx(), center = center)
            }
        }
        if (showA11ySteppers) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = {
                        val next = TimeDialModel.applyMinutes(liveMs, -5)
                        liveMs = next
                        onTimestampChange(next)
                    },
                    modifier = Modifier.semantics { contentDescription = "时刻减五分钟" },
                ) { Text("−5 分") }
                Text(
                    "拖环调时",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = {
                        val next = TimeDialModel.applyMinutes(liveMs, 5)
                        liveMs = next
                        onTimestampChange(next)
                    },
                    modifier = Modifier.semantics { contentDescription = "时刻加五分钟" },
                ) { Text("+5 分") }
            }
        }
    }
}
