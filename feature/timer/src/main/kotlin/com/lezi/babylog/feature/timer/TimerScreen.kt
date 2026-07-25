package com.lezi.babylog.feature.timer

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.lezi.babylog.core.common.productUiError
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.FeedReminderPort
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class TimerState(
    val babyId: Long? = null,
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

    fun toJson(
        savedElapsed: Long = SystemClock.elapsedRealtime(),
        savedWall: Long = System.currentTimeMillis(),
        savedBootCount: Long? = null,
    ): String = buildJsonObject {
        putNullableLong("babyId", babyId)
        put("leftRunning", leftRunning)
        put("rightRunning", rightRunning)
        put("leftAccumMs", leftAccumMs)
        put("rightAccumMs", rightAccumMs)
        putNullableLong("leftStartedElapsed", leftStartedElapsed)
        putNullableLong("rightStartedElapsed", rightStartedElapsed)
        putNullableLong("sessionStartedAt", sessionStartedAt)
        if (lastSide == null) put("lastSide", JsonNull) else put("lastSide", lastSide)
        put("order", order)
        put("savedElapsed", savedElapsed)
        put("savedWall", savedWall)
        savedBootCount?.let { put("savedBootCount", it) }
    }.toString()

    companion object {
        fun fromJson(
            raw: String?,
            nowElapsed: Long = SystemClock.elapsedRealtime(),
            nowWall: Long = System.currentTimeMillis(),
            nowBootCount: Long? = null,
        ): TimerState {
            if (raw.isNullOrBlank()) return TimerState()
            return runCatching {
                val o = Json.parseToJsonElement(raw).jsonObject
                val savedElapsed = o.optionalLong("savedElapsed")
                val savedWall = o.optionalLong("savedWall")
                val savedBootCount = o.optionalLong("savedBootCount")?.takeIf { it >= 0L }
                val drift = restoredRunningDelta(
                    savedElapsed = savedElapsed,
                    savedWall = savedWall,
                    nowElapsed = nowElapsed,
                    nowWall = nowWall,
                    savedBootCount = savedBootCount,
                    nowBootCount = nowBootCount,
                )
                var leftAccum = o.optionalLong("leftAccumMs") ?: 0L
                var rightAccum = o.optionalLong("rightAccumMs") ?: 0L
                val leftRunning = o.optionalBoolean("leftRunning")
                val rightRunning = o.optionalBoolean("rightRunning")
                // Freeze restored running sides into accumulated time.
                if (leftRunning) leftAccum += drift
                if (rightRunning) rightAccum += drift
                TimerState(
                    babyId = o.optionalLong("babyId"),
                    leftRunning = false,
                    rightRunning = false,
                    leftAccumMs = leftAccum,
                    rightAccumMs = rightAccum,
                    leftStartedElapsed = null,
                    rightStartedElapsed = null,
                    sessionStartedAt = o.optionalLong("sessionStartedAt"),
                    lastSide = o.optionalString("lastSide")?.takeIf { it.isNotBlank() },
                    order = o.optionalString("order").orEmpty(),
                )
            }.getOrDefault(TimerState())
        }
    }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullableLong(
    key: String,
    value: Long?,
) {
    if (value == null) put(key, JsonNull) else put(key, value)
}

private fun JsonObject.optionalLong(key: String): Long? =
    get(key)?.jsonPrimitive?.longOrNull

private fun JsonObject.optionalBoolean(key: String): Boolean =
    get(key)?.jsonPrimitive?.booleanOrNull ?: false

private fun JsonObject.optionalString(key: String): String? =
    get(key)?.jsonPrimitive?.contentOrNull

/**
 * Calculates time accrued after the last persisted timer snapshot.
 *
 * elapsedRealtime is immune to wall-clock edits while the device remains
 * booted. Android's boot count identifies a reboot even when the new uptime is
 * already greater than the persisted uptime. Legacy snapshots without a boot
 * count retain the previous conservative uptime-rewind fallback.
 */
internal fun restoredRunningDelta(
    savedElapsed: Long?,
    savedWall: Long?,
    nowElapsed: Long,
    nowWall: Long,
    savedBootCount: Long? = null,
    nowBootCount: Long? = null,
): Long {
    if (savedBootCount == null && nowBootCount != null) {
        // A pre-boot-count snapshot may already span a reboot even when the
        // new uptime is larger. Its wall pair is the only cross-boot clock
        // available, so migrate it conservatively on this first restore.
        return savedWall?.let { (nowWall - it).coerceAtLeast(0L) } ?: 0L
    }
    if (savedBootCount != null && nowBootCount != null) {
        if (savedBootCount != nowBootCount) {
            return savedWall?.let { (nowWall - it).coerceAtLeast(0L) } ?: 0L
        }
        return savedElapsed?.let { (nowElapsed - it).coerceAtLeast(0L) } ?: 0L
    }

    val elapsedDelta = savedElapsed?.let { nowElapsed - it }
    if (elapsedDelta != null && elapsedDelta >= 0L) return elapsedDelta
    return savedWall?.let { (nowWall - it).coerceAtLeast(0L) } ?: 0L
}

internal fun safeBootCount(readBootCount: () -> Int): Long? =
    try {
        readBootCount().toLong().takeIf { it >= 0L }
    } catch (_: Exception) {
        null
    }

private fun currentBootCount(context: Context): Long? =
    safeBootCount {
        Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.BOOT_COUNT,
        )
    }

/**
 * Pure left-side toggle transition used by [TimerViewModel.toggleLeft].
 *
 * When starting the left side while [TimerState.babyId] is null, [babyIdForStart]
 * must be supplied; otherwise this returns null so the caller can abort.
 * Callers that serialize concurrent toggles (mutex / single queue) must re-read
 * the latest state before each application so neither side's accum is lost.
 */
internal fun TimerState.withToggleLeft(
    nowElapsed: Long,
    nowWall: Long,
    babyIdForStart: Long? = null,
): TimerState? {
    var cur = this
    if (!cur.leftRunning && cur.babyId == null) {
        val babyId = babyIdForStart ?: return null
        cur = cur.copy(babyId = babyId)
    }
    return if (cur.leftRunning) {
        cur.copy(
            leftRunning = false,
            leftAccumMs = cur.leftMs(nowElapsed),
            leftStartedElapsed = null,
            lastSide = "L",
        )
    } else {
        val pausedRight = if (cur.rightRunning) {
            cur.copy(
                rightRunning = false,
                rightAccumMs = cur.rightMs(nowElapsed),
                rightStartedElapsed = null,
                lastSide = "R",
            )
        } else {
            cur
        }
        pausedRight.copy(
            leftRunning = true,
            leftStartedElapsed = nowElapsed,
            sessionStartedAt = pausedRight.sessionStartedAt ?: nowWall,
            order = when {
                pausedRight.order.isEmpty() -> "L"
                pausedRight.order == "R" -> "RL"
                else -> pausedRight.order
            },
            lastSide = "L",
        )
    }
}

/**
 * Pure right-side toggle transition used by [TimerViewModel.toggleRight].
 *
 * See [withToggleLeft] for baby-id and concurrency notes.
 */
internal fun TimerState.withToggleRight(
    nowElapsed: Long,
    nowWall: Long,
    babyIdForStart: Long? = null,
): TimerState? {
    var cur = this
    if (!cur.rightRunning && cur.babyId == null) {
        val babyId = babyIdForStart ?: return null
        cur = cur.copy(babyId = babyId)
    }
    return if (cur.rightRunning) {
        cur.copy(
            rightRunning = false,
            rightAccumMs = cur.rightMs(nowElapsed),
            rightStartedElapsed = null,
            lastSide = "R",
        )
    } else {
        val pausedLeft = if (cur.leftRunning) {
            cur.copy(
                leftRunning = false,
                leftAccumMs = cur.leftMs(nowElapsed),
                leftStartedElapsed = null,
                lastSide = "L",
            )
        } else {
            cur
        }
        pausedLeft.copy(
            rightRunning = true,
            rightStartedElapsed = nowElapsed,
            sessionStartedAt = pausedLeft.sessionStartedAt ?: nowWall,
            lastSide = "R",
            order = when {
                pausedLeft.order.isEmpty() -> "R"
                pausedLeft.order == "L" -> "LR"
                else -> pausedLeft.order
            },
        )
    }
}

@HiltViewModel
class TimerViewModel @Inject constructor(
    private val careLog: CareLog,
    private val settings: SettingsStore,
    private val nextFeed: FeedReminderPort,
    @ApplicationContext private val app: Context,
) : ViewModel() {
    private val _state = MutableStateFlow(TimerState())
    val state: StateFlow<TimerState> = _state
    private val completionInFlight = AtomicBoolean(false)
    /** Serializes L/R toggles so concurrent launches cannot clobber either side. */
    private val toggleMutex = Mutex()
    val timeStepMin = settings.settings
        .map { it.timeStepMin }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 1)
    val timePickerStyle = settings.settings
        .map { it.timePickerStyle }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "dropdown")
    val preferredHand = settings.settings
        .map { it.preferredHand }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "right")

    init {
        viewModelScope.launch {
            val raw = settings.nursingTimerJson.first()
            _state.value = TimerState.fromJson(
                raw = raw,
                nowBootCount = currentBootCount(app),
            )
        }
    }

    private fun persist(s: TimerState) {
        _state.value = s
        viewModelScope.launch {
            settings.setNursingTimerJson(
                if (s.leftAccumMs == 0L &&
                    s.rightAccumMs == 0L &&
                    !s.leftRunning &&
                    !s.rightRunning &&
                    s.sessionStartedAt == null
                ) {
                    null
                } else {
                    s.toJson(savedBootCount = currentBootCount(app))
                },
            )
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
        viewModelScope.launch {
            toggleMutex.withLock {
                val now = SystemClock.elapsedRealtime()
                val wall = System.currentTimeMillis()
                val cur = _state.value
                val babyIdForStart = if (!cur.leftRunning && cur.babyId == null) {
                    careLog.getCurrentBaby()?.id ?: return@withLock
                } else {
                    null
                }
                val next = cur.withToggleLeft(
                    nowElapsed = now,
                    nowWall = wall,
                    babyIdForStart = babyIdForStart,
                ) ?: return@withLock
                persist(next)
            }
        }
    }

    fun toggleRight() {
        viewModelScope.launch {
            toggleMutex.withLock {
                val now = SystemClock.elapsedRealtime()
                val wall = System.currentTimeMillis()
                val cur = _state.value
                val babyIdForStart = if (!cur.rightRunning && cur.babyId == null) {
                    careLog.getCurrentBaby()?.id ?: return@withLock
                } else {
                    null
                }
                val next = cur.withToggleRight(
                    nowElapsed = now,
                    nowWall = wall,
                    babyIdForStart = babyIdForStart,
                ) ?: return@withLock
                persist(next)
            }
        }
    }

    internal fun freezeCompletion(
        initialNote: String = "",
        initialAmountMl: String = "",
    ): NursingCompletionDraft = freezeNursingCompletion(
        state = _state.value,
        nowElapsed = SystemClock.elapsedRealtime(),
        clickedAt = System.currentTimeMillis(),
        initialNote = initialNote,
        initialAmountMl = initialAmountMl,
    )

    internal fun complete(
        draft: NursingCompletionDraft,
        onDone: () -> Unit,
        onError: (String) -> Unit,
    ) {
        if (!completionInFlight.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                draft.validationError(System.currentTimeMillis())?.let {
                    onError(it)
                    return@launch
                }
                val babyId = _state.value.babyId ?: careLog.getCurrentBaby()?.id
                if (babyId == null) {
                    onError("请先添加宝宝")
                    return@launch
                }
                val command = draft.toCommand()
                val recordMode = settings.settings.first().recordAtStartOrEnd
                careLog.completeNursing(
                    babyId = babyId,
                    leftMin = command.leftMin,
                    rightMin = command.rightMin,
                    order = command.order,
                    amountMl = command.amountMl,
                    note = command.note,
                    startedAt = command.startedAt,
                    endedAt = command.endedAt,
                    recordMode = recordMode,
                )
                clear()
                onDone()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                onError(productUiError(throwable, "保存失败"))
            } finally {
                completionInFlight.set(false)
            }
        }
    }

    internal fun scheduleReminder(atMillis: Long?, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            onResult(runCatching { nextFeed.scheduleAfterFeed(atMillis) }.isSuccess)
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
    initialNote: String = "",
    initialAmountMl: String = "",
    vm: TimerViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val timeStepMin by vm.timeStepMin.collectAsStateWithLifecycle()
    val timePickerStyle by vm.timePickerStyle.collectAsStateWithLifecycle()
    val preferredHand by vm.preferredHand.collectAsStateWithLifecycle()
    var tick by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.leftRunning, state.rightRunning) {
        while (true) {
            tick = SystemClock.elapsedRealtime()
            delay(200)
        }
    }
    val leftMs = state.leftMs(tick)
    val rightMs = state.rightMs(tick)
    var completionDraft by remember { mutableStateOf<NursingCompletionDraft?>(null) }
    var completionSaving by remember { mutableStateOf(false) }
    var completionSaveError by remember { mutableStateOf<String?>(null) }
    var showDiscardConfirmation by remember { mutableStateOf(false) }
    var savedAwaitingReminder by remember { mutableStateOf(false) }
    var pendingReminderAt by remember { mutableStateOf<Long?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            savedAwaitingReminder = false
            onDone()
        } else {
            vm.scheduleReminder(pendingReminderAt) {
                savedAwaitingReminder = false
                onDone()
            }
        }
    }
    fun requestOrSchedule(atMillis: Long?) {
        pendingReminderAt = atMillis
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            vm.scheduleReminder(atMillis) {
                savedAwaitingReminder = false
                onDone()
            }
        } else {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val completionSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
                    onClick = {
                        completionSaveError = null
                        completionDraft = vm.freezeCompletion(
                            initialNote = initialNote,
                            initialAmountMl = initialAmountMl,
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                ) { Text("完成并记录") }
                TextButton(
                    onClick = {
                        if (state.hasTimerData()) {
                            showDiscardConfirmation = true
                        } else {
                            vm.clear()
                            onDone()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("丢弃") }
            }
        }
    }

    if (showDiscardConfirmation) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirmation = false },
            title = { Text("丢弃本次计时？") },
            text = { Text("已累计的喂奶计时将不会保存，此操作无法撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardConfirmation = false
                        vm.clear()
                        onDone()
                    },
                ) {
                    Text(
                        "确认丢弃",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirmation = false }) {
                    Text("继续计时")
                }
            },
        )
    }

    completionDraft?.let { draft ->
        ModalBottomSheet(
            onDismissRequest = {
                if (!completionSaving) {
                    completionDraft = null
                    completionSaveError = null
                }
            },
            sheetState = completionSheetState,
        ) {
            NursingCompletionSheet(
                draft = draft,
                saving = completionSaving,
                saveError = completionSaveError,
                timeStepMin = timeStepMin,
                timePickerStyle = timePickerStyle,
                preferredHand = preferredHand,
                onDraftChange = {
                    completionSaveError = null
                    completionDraft = it
                },
                onDismiss = {
                    if (!completionSaving) {
                        completionDraft = null
                        completionSaveError = null
                    }
                },
                onConfirm = { confirmed ->
                    completionSaving = true
                    completionSaveError = null
                    vm.complete(
                        draft = confirmed,
                        onDone = {
                            completionSaving = false
                            completionDraft = null
                            savedAwaitingReminder = true
                        },
                        onError = {
                            completionSaving = false
                            completionSaveError = it
                        },
                    )
                },
            )
        }
    }

    if (savedAwaitingReminder) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("设置下次喂养提醒？") },
            text = { Text("计时记录已经安全保存。可按设置间隔提醒、调整为 60 分钟，或不提醒。") },
            confirmButton = {
                TextButton(onClick = { requestOrSchedule(null) }) {
                    Text("确认提醒")
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            requestOrSchedule(System.currentTimeMillis() + 60 * 60_000L)
                        },
                    ) { Text("60 分钟") }
                    TextButton(
                        onClick = {
                            savedAwaitingReminder = false
                            onDone()
                        },
                    ) { Text("不提醒") }
                }
            },
        )
    }
}

internal fun TimerState.hasTimerData(): Boolean =
    leftRunning ||
        rightRunning ||
        leftAccumMs > 0L ||
        rightAccumMs > 0L ||
        sessionStartedAt != null

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
