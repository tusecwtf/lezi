package com.lezi.babylog.feature.timer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.feature.settings.NextFeedScheduler
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject

data class TimerState(
    val leftRunning: Boolean = false,
    val rightRunning: Boolean = false,
    val leftAccumMs: Long = 0L,
    val rightAccumMs: Long = 0L,
    val leftStartedElapsed: Long? = null,
    val rightStartedElapsed: Long? = null,
    val sessionStartedAt: Long? = null,
    val lastSide: String? = null,
    val order: String = "",
) {
    fun leftMs(nowElapsed: Long = SystemClock.elapsedRealtime()): Long =
        leftAccumMs + if (leftRunning && leftStartedElapsed != null) nowElapsed - leftStartedElapsed else 0L

    fun rightMs(nowElapsed: Long = SystemClock.elapsedRealtime()): Long =
        rightAccumMs + if (rightRunning && rightStartedElapsed != null) nowElapsed - rightStartedElapsed else 0L

    fun toJson(): String = JSONObject().apply {
        put("leftRunning", leftRunning)
        put("rightRunning", rightRunning)
        put("leftAccumMs", leftAccumMs)
        put("rightAccumMs", rightAccumMs)
        put("leftStartedElapsed", leftStartedElapsed ?: JSONObject.NULL)
        put("rightStartedElapsed", rightStartedElapsed ?: JSONObject.NULL)
        put("sessionStartedAt", sessionStartedAt ?: JSONObject.NULL)
        put("lastSide", lastSide ?: JSONObject.NULL)
        put("order", order)
        put("savedElapsed", SystemClock.elapsedRealtime())
        put("savedWall", System.currentTimeMillis())
    }.toString()

    companion object {
        fun fromJson(raw: String?): TimerState {
            if (raw.isNullOrBlank()) return TimerState()
            return runCatching {
                val o = JSONObject(raw)
                val savedElapsed = o.optLong("savedElapsed", SystemClock.elapsedRealtime())
                val now = SystemClock.elapsedRealtime()
                val drift = (now - savedElapsed).coerceAtLeast(0L)
                var leftAccum = o.optLong("leftAccumMs")
                var rightAccum = o.optLong("rightAccumMs")
                val leftRunning = o.optBoolean("leftRunning")
                val rightRunning = o.optBoolean("rightRunning")
                // Convert running sides into accumulated using wall drift (process may have died).
                if (leftRunning) leftAccum += drift
                if (rightRunning) rightAccum += drift
                TimerState(
                    leftRunning = false,
                    rightRunning = false,
                    leftAccumMs = leftAccum,
                    rightAccumMs = rightAccum,
                    leftStartedElapsed = null,
                    rightStartedElapsed = null,
                    sessionStartedAt = o.optLong("sessionStartedAt").takeIf { o.has("sessionStartedAt") && !o.isNull("sessionStartedAt") },
                    lastSide = o.optString("lastSide").takeIf { it.isNotBlank() && it != "null" },
                    order = o.optString("order"),
                )
            }.getOrDefault(TimerState())
        }
    }
}

@HiltViewModel
class TimerViewModel @Inject constructor(
    private val careLog: CareLog,
    private val settings: SettingsStore,
    private val nextFeed: NextFeedScheduler,
    @ApplicationContext private val app: Context,
 ) : ViewModel() {
    private val _state = MutableStateFlow(TimerState())
    val state: StateFlow<TimerState> = _state

    init {
        viewModelScope.launch {
            val raw = settings.nursingTimerJson.first()
            _state.value = TimerState.fromJson(raw)
        }
    }

    private fun persist(s: TimerState) {
        _state.value = s
        viewModelScope.launch {
            settings.setNursingTimerJson(if (s.leftAccumMs == 0L && s.rightAccumMs == 0L && !s.leftRunning && !s.rightRunning && s.sessionStartedAt == null) null else s.toJson())
        }
        updateService(s)
    }

    private fun updateService(s: TimerState) {
        val running = s.leftRunning || s.rightRunning
        val intent = Intent(app, NursingTimerService::class.java)
        if (running) {
            intent.action = NursingTimerService.ACTION_UPDATE
            intent.putExtra(NursingTimerService.EXTRA_LEFT_MS, s.leftMs())
            intent.putExtra(NursingTimerService.EXTRA_RIGHT_MS, s.rightMs())
            ContextCompat.startForegroundService(app, intent)
        } else {
            app.stopService(Intent(app, NursingTimerService::class.java))
        }
    }

    fun toggleLeft() {
        val now = SystemClock.elapsedRealtime()
        val cur = _state.value
        val next = if (cur.leftRunning) {
            cur.copy(
                leftRunning = false,
                leftAccumMs = cur.leftMs(now),
                leftStartedElapsed = null,
                lastSide = "L",
            )
        } else {
            val pausedRight = if (cur.rightRunning) {
                cur.copy(
                    rightRunning = false,
                    rightAccumMs = cur.rightMs(now),
                    rightStartedElapsed = null,
                    lastSide = "R",
                )
            } else cur
            pausedRight.copy(
                leftRunning = true,
                leftStartedElapsed = now,
                sessionStartedAt = pausedRight.sessionStartedAt ?: System.currentTimeMillis(),
                order = pausedRight.order.ifEmpty { "L" } + if (pausedRight.order.contains("L")) "" else "",
                lastSide = "L",
            ).let {
                val order = when {
                    it.order.isEmpty() -> "L"
                    it.order == "R" -> "RL"
                    it.order == "L" -> "L"
                    else -> it.order
                }
                it.copy(order = order)
            }
        }
        persist(next)
    }

    fun toggleRight() {
        val now = SystemClock.elapsedRealtime()
        val cur = _state.value
        val next = if (cur.rightRunning) {
            cur.copy(
                rightRunning = false,
                rightAccumMs = cur.rightMs(now),
                rightStartedElapsed = null,
                lastSide = "R",
            )
        } else {
            val pausedLeft = if (cur.leftRunning) {
                cur.copy(
                    leftRunning = false,
                    leftAccumMs = cur.leftMs(now),
                    leftStartedElapsed = null,
                    lastSide = "L",
                )
            } else cur
            pausedLeft.copy(
                rightRunning = true,
                rightStartedElapsed = now,
                sessionStartedAt = pausedLeft.sessionStartedAt ?: System.currentTimeMillis(),
                lastSide = "R",
                order = when {
                    pausedLeft.order.isEmpty() -> "R"
                    pausedLeft.order == "L" -> "LR"
                    pausedLeft.order == "R" -> "R"
                    else -> pausedLeft.order
                },
            )
        }
        persist(next)
    }

    fun complete(onDone: () -> Unit) {
        viewModelScope.launch {
            val now = SystemClock.elapsedRealtime()
            val cur = _state.value
            val leftMin = ((cur.leftMs(now) + 30_000) / 60_000L).toInt().coerceAtLeast(0)
            val rightMin = ((cur.rightMs(now) + 30_000) / 60_000L).toInt().coerceAtLeast(0)
            if (leftMin == 0 && rightMin == 0) {
                clear()
                onDone()
                return@launch
            }
            val baby = careLog.getCurrentBaby() ?: return@launch
            val started = cur.sessionStartedAt ?: System.currentTimeMillis()
            val ended = System.currentTimeMillis()
            val order = when {
                cur.order in setOf("LR", "RL") -> cur.order
                leftMin > 0 && rightMin > 0 -> if (cur.lastSide == "R") "LR" else "RL"
                leftMin > 0 -> "L"
                else -> "R"
            }
            careLog.completeNursing(
                babyId = baby.id,
                leftMin = leftMin,
                rightMin = rightMin,
                order = order,
                startedAt = started,
                endedAt = ended,
            )
            nextFeed.scheduleAfterFeed(app)
            clear()
            onDone()
        }
    }

    fun clear() {
        persist(TimerState())
        app.stopService(Intent(app, NursingTimerService::class.java))
    }
}

@AndroidEntryPoint
class NursingTimerService : Service() {
    @Inject lateinit var settings: SettingsStore

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        val left = intent?.getLongExtra(EXTRA_LEFT_MS, 0L) ?: 0L
        val right = intent?.getLongExtra(EXTRA_RIGHT_MS, 0L) ?: 0L
        val notification = buildNotification(left, right)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        return START_STICKY
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "喂奶计时", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildNotification(leftMs: Long, rightMs: Long): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val pi = PendingIntent.getActivity(
            this,
            0,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = "左 ${fmt(leftMs)} · 右 ${fmt(rightMs)}"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("乐记 · 喂奶计时中")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun fmt(ms: Long): String {
        val totalSec = ms / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return "%d:%02d".format(m, s)
    }

    companion object {
        const val CHANNEL_ID = "nursing_timer"
        const val NOTIF_ID = 42
        const val ACTION_UPDATE = "com.lezi.babylog.timer.UPDATE"
        const val EXTRA_LEFT_MS = "left_ms"
        const val EXTRA_RIGHT_MS = "right_ms"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimerRoute(
    onDone: () -> Unit,
    vm: TimerViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var tick by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.leftRunning, state.rightRunning) {
        while (true) {
            tick = SystemClock.elapsedRealtime()
            delay(200)
        }
    }
    val leftMs = state.leftMs(tick)
    val rightMs = state.rightMs(tick)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("喂奶计时") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    when (state.lastSide) {
                        "L" -> "上次停在左侧"
                        "R" -> "上次停在右侧"
                        else -> "点左右大圆开始"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "合计 ${fmtMs(leftMs + rightMs)}",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SideButton(
                    label = "左",
                    time = fmtMs(leftMs),
                    running = state.leftRunning,
                    color = MaterialTheme.colorScheme.primary,
                    onClick = vm::toggleLeft,
                )
                SideButton(
                    label = "右",
                    time = fmtMs(rightMs),
                    running = state.rightRunning,
                    color = MaterialTheme.colorScheme.tertiary,
                    onClick = vm::toggleRight,
                )
            }

            Column(Modifier.fillMaxWidth()) {
                Button(
                    onClick = { vm.complete(onDone) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                ) { Text("完成并记录") }
                TextButton(
                    onClick = {
                        vm.clear()
                        onDone()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("丢弃") }
            }
        }
    }
}

@Composable
private fun SideButton(
    label: String,
    time: String,
    running: Boolean,
    color: Color,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(148.dp)
                .clip(CircleShape)
                .background(if (running) color else color.copy(alpha = 0.18f))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    label,
                    color = if (running) Color.White else color,
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    time,
                    color = if (running) Color.White else MaterialTheme.colorScheme.onBackground,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    if (running) "暂停" else "开始",
                    color = if (running) Color.White.copy(alpha = 0.9f) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun fmtMs(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}
