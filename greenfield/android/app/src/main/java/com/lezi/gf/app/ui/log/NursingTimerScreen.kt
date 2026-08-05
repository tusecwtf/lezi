package com.lezi.gf.app.ui.log

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lezi.gf.app.AppContainer
import com.lezi.gf.app.ui.model.ComposerFields
import com.lezi.gf.app.ui.theme.LeziSpacing
import com.lezi.gf.care.ComposerDraft
import com.lezi.gf.care.NursingTimerState
import com.lezi.gf.kernel.GfResult
import com.lezi.gf.kernel.SystemClock
import kotlinx.coroutines.delay

/**
 * Fullscreen L/R nursing timer with explicit confirm sheet before write.
 * Spec 02 E9: large dual circles. Domain: startTimer → completeTimer → confirmCreate.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NursingTimerScreen(
    container: AppContainer,
    babyUuid: String,
    onDismiss: () -> Unit,
    onConfirmed: () -> Unit,
) {
    var state by remember {
        mutableStateOf(
            container.care.recoverTimer()
                ?: NursingTimerState(babyClientUuid = babyUuid),
        )
    }
    var liveLeft by remember { mutableStateOf(state.leftMs) }
    var liveRight by remember { mutableStateOf(state.rightMs) }
    var confirmDraft by remember { mutableStateOf<ComposerDraft?>(null) }

    LaunchedEffect(state.activeSide, state.startedAtMs) {
        while (state.activeSide != null && state.startedAtMs != null && !state.frozen) {
            val base = state
            val elapsed = SystemClock.nowEpochMs() - (base.startedAtMs ?: 0L)
            when (base.activeSide) {
                "L" -> {
                    liveLeft = base.leftMs + elapsed
                    liveRight = base.rightMs
                }
                "R" -> {
                    liveRight = base.rightMs + elapsed
                    liveLeft = base.leftMs
                }
            }
            delay(200)
        }
        liveLeft = state.leftMs
        liveRight = state.rightMs
    }

    Surface(
        Modifier
            .fillMaxSize()
            .semantics { contentDescription = "喂奶计时全屏" },
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("喂奶计时", style = MaterialTheme.typography.headlineMedium)
                Text("停止后需确认才会写入记录", style = MaterialTheme.typography.bodyMedium)
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SideButton(
                    label = "左",
                    durationMs = liveLeft,
                    active = state.activeSide == "L",
                    color = Color(0xFFE76F51),
                    onClick = {
                        state = container.care.startTimer(babyUuid, "L")
                        liveLeft = state.leftMs
                        liveRight = state.rightMs
                    },
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("上次侧", style = MaterialTheme.typography.labelMedium)
                    Text(
                        when {
                            liveLeft >= liveRight && liveLeft > 0 -> "L"
                            liveRight > liveLeft -> "R"
                            else -> "—"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
                SideButton(
                    label = "右",
                    durationMs = liveRight,
                    active = state.activeSide == "R",
                    color = Color(0xFF2A9D8F),
                    onClick = {
                        state = container.care.startTimer(babyUuid, "R")
                        liveLeft = state.leftMs
                        liveRight = state.rightMs
                    },
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Button(
                    onClick = {
                        when (val d = container.care.completeTimer()) {
                            is GfResult.Ok -> {
                                confirmDraft = d.value
                                state = container.care.recoverTimer() ?: state.copy(frozen = true)
                            }
                            is GfResult.Err -> Unit
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(LeziSpacing.Touch),
                ) { Text("完成 → 确认写入") }
                TextButton(onClick = onDismiss) { Text("关闭（不写入）") }
            }
        }
    }

    confirmDraft?.let { draft ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { confirmDraft = null },
            sheetState = sheetState,
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text("确认喂奶记录", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                val n = ComposerFields.parseNursing(draft.payloadJson)
                Text("左 ${ComposerFields.formatDurationMs(n.leftMs)} · 右 ${ComposerFields.formatDurationMs(n.rightMs)}")
                Text("顺序 ${n.order}")
                Text(
                    "点确认后才会写入护理记录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        when (container.care.confirmCreate(draft)) {
                            is GfResult.Ok -> {
                                container.care.clearTimer()
                                // Next-feed is offered by host (legacy-style dialog), not silent write.
                                confirmDraft = null
                                onConfirmed()
                            }
                            is GfResult.Err -> Unit
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(LeziSpacing.Touch),
                ) { Text("确认写入") }
                TextButton(
                    onClick = { confirmDraft = null },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("返回计时") }
            }
        }
    }
}

@Composable
private fun SideButton(
    label: String,
    durationMs: Long,
    active: Boolean,
    color: Color,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(LeziSpacing.TimerButton)
                .clip(CircleShape)
                .background(if (active) color else color.copy(alpha = 0.22f))
                .clickable(onClick = onClick)
                .semantics { contentDescription = "计时侧 $label" },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = if (active) Color.White else color,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            ComposerFields.formatDurationMs(durationMs),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
