package com.lezi.gf.app.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lezi.gf.app.AppContainer
import com.lezi.gf.app.export.PdfShareExport
import com.lezi.gf.app.media.PhotoStore
import com.lezi.gf.app.reminders.LocalReminderScheduler
import com.lezi.gf.app.sync.ForegroundSyncCoordinator
import com.lezi.gf.app.ui.account.AccountStack
import com.lezi.gf.app.ui.components.DateBar
import com.lezi.gf.app.ui.components.DaySummaryChips
import com.lezi.gf.app.ui.components.GrowthCurveCanvas
import com.lezi.gf.app.ui.components.MoreSheet
import com.lezi.gf.app.ui.components.QuickDock
import com.lezi.gf.app.ui.components.SwipeTimelineRow
import com.lezi.gf.app.ui.components.ThreeDayAxis
import com.lezi.gf.app.ui.components.WeekBarChart
import com.lezi.gf.app.ui.log.ComposerSheet
import com.lezi.gf.app.ui.log.MonthCalendarDialog
import com.lezi.gf.app.ui.log.NursingTimerScreen
import com.lezi.gf.app.ui.model.ChartSeries
import com.lezi.gf.app.ui.model.ComposerFields
import com.lezi.gf.app.ui.model.DayAxisModel
import com.lezi.gf.app.ui.model.DockModel
import com.lezi.gf.app.ui.model.MonthCalendarModel
import com.lezi.gf.app.ui.search.SearchScreen
import com.lezi.gf.app.ui.theme.LeziColors
import com.lezi.gf.app.ui.theme.LeziDayAge
import com.lezi.gf.app.ui.theme.LeziDensity
import com.lezi.gf.app.ui.theme.LeziSpacing
import com.lezi.gf.app.ui.theme.LeziTypeGlyph
import com.lezi.gf.care.CareAggregation
import com.lezi.gf.care.ComposerDraft
import com.lezi.gf.care.GrowthCurves
import com.lezi.gf.care.PhotoRef
import com.lezi.gf.care.RecordType
import com.lezi.gf.family.JoinState
import com.lezi.gf.kernel.GfResult
import com.lezi.gf.kernel.ProductVersion
import com.lezi.gf.kernel.SystemClock
import com.lezi.gf.settings.Handedness
import com.lezi.gf.settings.LocalSettings
import com.lezi.gf.settings.TimeFormat
import com.lezi.gf.settings.UiTemplate
import com.lezi.gf.syncsession.WireAppUpdate
import java.io.File
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(container: AppContainer, onDone: () -> Unit) {
    var name by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("欢迎使用乐记", style = MaterialTheme.typography.headlineMedium)
        Text("先离线创建宝宝，之后可连接家庭服务器。", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("宝宝昵称") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                when (container.family.createOfflineBaby(name)) {
                    is GfResult.Ok -> onDone()
                    is GfResult.Err -> Unit
                }
            },
            enabled = name.isNotBlank(),
        ) { Text("开始记录") }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LogScreen(
    container: AppContainer,
    settings: LocalSettings,
    modifier: Modifier = Modifier,
    pendingComposerTypeKey: String? = null,
    onPendingComposerConsumed: () -> Unit = {},
    onChanged: () -> Unit,
) {
    val baby = container.family.currentBaby() ?: return
    val density = LeziDensity.forTemplate(settings.template)
    val now = SystemClock.nowEpochMs()
    val todayStart = CareAggregation.dayStartMs(now)
    val dayMs = DayAxisModel.DAY_MS

    var selectedDayStart by remember { mutableLongStateOf(todayStart) }
    var viewportStart by remember { mutableStateOf<Long?>(null) }
    var dayTypeFilter by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf<ComposerDraft?>(null) }
    /** When non-null, ComposerSheet is editing this record (type-specific fields). */
    var editingRecordUuid by remember { mutableStateOf<String?>(null) }
    var showTimer by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var showCal by remember { mutableStateOf(false) }
    var showLayout by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var deleteUuid by remember { mutableStateOf<String?>(null) }
    var previewPhotos by remember { mutableStateOf<List<PhotoRef>?>(null) }
    var previewIndex by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var expandedSwipeUuid by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val babyAccent = LeziColors.babyAccent(
        baby.themeColor,
        journalFallback = settings.template == UiTemplate.JOURNAL,
    )
    val dayAgeLine = LeziDayAge.format(
        birthdayEpochDay = baby.birthdayEpochDay,
        todayEpochDay = LeziDayAge.todayEpochDay(now),
        useDayAgeMode = settings.useDayAgeMode,
    )

    val summary = container.care.daySummary(baby.clientUuid, selectedDayStart)
    val timeline = container.care.timelineFiltered(
        baby.clientUuid,
        selectedDayStart,
        typeKeyFilter = dayTypeFilter,
        newestFirst = settings.timelineNewestFirst,
    )
    val pending = container.care.pendingPlans(baby.clientUuid)
    val window = DayAxisModel.buildWindow(
        selectedDayStartMs = selectedDayStart,
        nowMs = now,
        viewportStartMs = viewportStart,
    )
    val axisMarks = remember(selectedDayStart, baby.clientUuid) {
        val wStart = DayAxisModel.threeDayWindowStart(selectedDayStart)
        val wEnd = DayAxisModel.threeDayWindowEnd(selectedDayStart)
        container.care.store().allRecords()
            .filter {
                it.babyClientUuid == baby.clientUuid &&
                    it.deletedAtMs == null &&
                    it.timestampMs >= wStart &&
                    it.timestampMs < wEnd
            }
            .map { it.timestampMs to it.typeKey }
    }
    val dockSlots = DockModel.resolveSlots(
        container.care.store().layout,
        container.care.liveCustomDefs(),
    )

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        val d = draft ?: return@rememberLauncherForActivityResult
        if (uri == null || d.photos.size >= PhotoStore.MAX_PHOTOS_PER_RECORD) {
            return@rememberLauncherForActivityResult
        }
        // Full product path: decode + JPEG compress to filesDir/photos — never truncate raw bytes
        val photo = PhotoStore.importFromUri(context, uri) ?: return@rememberLauncherForActivityResult
        draft = d.copy(photos = d.photos + photo, dirty = true)
    }

    fun openComposerForType(type: RecordType, customDefUuid: String? = null) {
        val openSleep = if (type == RecordType.SLEEP) {
            container.care.openSleepRecord(baby.clientUuid)
        } else {
            null
        }
        if (type == RecordType.SLEEP && openSleep != null) {
            // Prefill wake mode closing the open sleep
            val start = ComposerFields.parseSleep(openSleep.payloadJson).startMs
                .takeIf { it > 0 } ?: openSleep.timestampMs
            val wakePayload = container.care.wakeSleepPayload(
                openSleep,
                wakeMs = SystemClock.nowEpochMs(),
                isNap = ComposerFields.parseSleep(openSleep.payloadJson).isNap,
                anomaly = false,
            )
            editingRecordUuid = openSleep.clientUuid
            draft = ComposerDraft(
                type = RecordType.SLEEP,
                babyClientUuid = baby.clientUuid,
                timestampMs = SystemClock.nowEpochMs(),
                note = openSleep.note,
                payloadJson = wakePayload,
                photos = openSleep.photos,
                dirty = true,
            )
            // ensure start_ms present for UI
            val parsed = ComposerFields.parseSleep(wakePayload)
            if (parsed.startMs <= 0) {
                draft = draft!!.copy(
                    payloadJson = ComposerFields.buildSleep(
                        parsed.copy(
                            mode = ComposerFields.SleepMode.WAKE,
                            startMs = start,
                            endMs = SystemClock.nowEpochMs(),
                            open = false,
                        ),
                    ),
                )
            }
        } else {
            editingRecordUuid = null
            draft = container.care.openComposer(type, baby.clientUuid, customDefUuid = customDefUuid)
        }
    }

    fun openEditComposer(recordUuid: String) {
        val rec = container.care.store().getRecord(recordUuid) ?: return
        val type = RecordType.fromKey(rec.typeKey) ?: RecordType.DIARY
        editingRecordUuid = rec.clientUuid
        draft = ComposerDraft(
            type = type,
            babyClientUuid = rec.babyClientUuid,
            timestampMs = rec.timestampMs,
            note = rec.note,
            payloadJson = rec.payloadJson,
            photos = rec.photos,
            customDefUuid = rec.customDefUuid,
            dirty = false,
        )
    }

    LaunchedEffect(pendingComposerTypeKey) {
        val key = pendingComposerTypeKey ?: return@LaunchedEffect
        val type = RecordType.fromKey(key) ?: RecordType.FORMULA
        openComposerForType(type)
        onPendingComposerConsumed()
    }

    LaunchedEffect(selectedDayStart) {
        viewportStart = null
        dayTypeFilter = null
    }

    if (showLayout) {
        LayoutEditorScreen(
            care = container.care,
            onDismiss = { showLayout = false },
            onChanged = onChanged,
            reduceMotion = settings.reduceMotion,
        )
        return
    }
    if (showSearch) {
        SearchScreen(
            container = container,
            onDismiss = { showSearch = false },
            onOpenRecord = { uuid ->
                showSearch = false
                openEditComposer(uuid)
            },
        )
        return
    }
    if (showTimer) {
        NursingTimerScreen(
            container = container,
            babyUuid = baby.clientUuid,
            onDismiss = { showTimer = false },
            onConfirmed = {
                showTimer = false
                onChanged()
            },
        )
        return
    }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            scope.launch {
                refreshing = true
                runForegroundSync(container)
                onChanged()
                refreshing = false
            }
        },
        modifier = modifier
            .fillMaxSize()
            .semantics { contentDescription = "记录主路径" },
    ) {
        Column(Modifier.fillMaxSize()) {
            // Top bar — nickname + day age + baby theme accent (Spec 02 E7)
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(
                        if (settings.template == UiTemplate.JOURNAL) {
                            babyAccent.copy(alpha = 0.12f)
                        } else {
                            Color.Transparent
                        },
                    )
                    .padding(horizontal = density.topBarHorizontal, vertical = LeziSpacing.Sm),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        baby.nickname,
                        style = MaterialTheme.typography.titleLarge,
                        color = babyAccent,
                    )
                    Text(
                        buildString {
                            if (dayAgeLine != null) {
                                append(dayAgeLine)
                                append(" · ")
                            }
                            append(if (settings.template == UiTemplate.JOURNAL) "紧凑记录簿" else "温暖卡片")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row {
                    TextButton(onClick = { showSearch = true }) { Text("搜索") }
                    TextButton(onClick = {
                        runForegroundSync(container)
                        onChanged()
                    }) { Text("同步") }
                }
            }

            DateBar(
                selectedDayStartMs = selectedDayStart,
                nowMs = now,
                onPrev = {
                    selectedDayStart -= dayMs
                    viewportStart = null
                },
                onNext = {
                    selectedDayStart += dayMs
                    viewportStart = null
                },
                onOpenCalendar = { showCal = true },
                onBackToToday = {
                    selectedDayStart = MonthCalendarModel.todayStartMs(now)
                    viewportStart = null
                },
                density = density,
            )

            DaySummaryChips(
                summary = summary,
                selectedTypeKey = dayTypeFilter,
                onSelect = { dayTypeFilter = it },
                density = density,
            )

            Spacer(Modifier.height(density.sectionGap / 2))
            ThreeDayAxis(
                window = window,
                nowMs = now,
                marks = axisMarks,
                onPan = { delta ->
                    val panned = DayAxisModel.pan(window, delta)
                    viewportStart = panned.viewportStartMs
                },
                density = density,
            )
            Spacer(Modifier.height(density.sectionGap / 2))

            if (pending.isNotEmpty()) {
                Column(Modifier.padding(horizontal = density.panelContent)) {
                    Text("待履行", style = MaterialTheme.typography.titleSmall)
                    pending.forEach { p ->
                        val label = RecordType.fromKey(p.typeKey)?.chineseLabel ?: p.typeKey
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("$label · ${p.status}")
                            Row {
                                TextButton(onClick = {
                                    container.care.fulfillPlan(p.clientUuid)
                                    onChanged()
                                }) { Text("履行") }
                                TextButton(onClick = {
                                    container.care.skipPlan(p.clientUuid)
                                    onChanged()
                                }) { Text("跳过") }
                            }
                        }
                    }
                }
            }

            if (timeline.isEmpty()) {
                Box(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("还没有记录 · 点底坞开始", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    items(timeline, key = { it.clientUuid }) { r ->
                        val author = container.care.authorDisplayName(
                            r,
                            container.family.membershipNames(),
                            container.family.selfMembershipId(),
                        )
                        SwipeTimelineRow(
                            record = r,
                            author = author,
                            nowMs = now,
                            onEdit = {
                                expandedSwipeUuid = null
                                openEditComposer(r.clientUuid)
                            },
                            onDelete = {
                                expandedSwipeUuid = null
                                deleteUuid = r.clientUuid
                            },
                            onOpenPhotos = { idx ->
                                previewPhotos = r.photos
                                previewIndex = idx
                            },
                            density = density,
                            reduceMotion = settings.reduceMotion,
                            expanded = expandedSwipeUuid == r.clientUuid,
                            onExpandRequest = { expandedSwipeUuid = r.clientUuid },
                        )
                    }
                }
            }

            QuickDock(
                slots = dockSlots,
                onSlotClick = { slot ->
                    val key = slot.bindingKey ?: return@QuickDock
                    when {
                        key.startsWith("custom:") -> {
                            openComposerForType(
                                RecordType.CUSTOM,
                                customDefUuid = key.removePrefix("custom:"),
                            )
                        }
                        else -> {
                            val type = RecordType.fromKey(key) ?: return@QuickDock
                            openComposerForType(type)
                        }
                    }
                },
                onMore = { showMore = true },
                onLongPressLayout = { showLayout = true },
                density = density,
            )
            // Secondary row for timer / layout / calendar (not in 4-slot dock)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = density.dockOuterHorizontal, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                TextButton(
                    onClick = { showTimer = true },
                    modifier = Modifier.semantics {
                        // Exact a11y label for dual-capture harness (avoid "计 计时" glyph+text join).
                        contentDescription = "计时"
                    },
                ) { Text("计时") }
                TextButton(onClick = { showLayout = true }) { Text("布局") }
                TextButton(onClick = { showCal = true }) { Text("月历") }
            }
        }
    }

    if (showCal) {
        MonthCalendarDialog(
            selectedDayStartMs = selectedDayStart,
            weekStartsOnMonday = settings.weekStartsOnMonday,
            onDismiss = { showCal = false },
            onSelectDayStartMs = {
                selectedDayStart = it
                viewportStart = null
                dayTypeFilter = null
            },
        )
    }

    if (showMore) {
        val catalog = DockModel.moreCatalog(
            container.care.store().layout,
            container.care.liveCustomDefs(),
            includeTimer = true,
        )
        MoreSheet(
            catalog = catalog,
            onDismiss = { showMore = false },
            onSelect = { item ->
                when {
                    item.bindingKey == "__timer__" -> {
                        showMore = false
                        showTimer = true
                    }
                    item.isCustom -> {
                        openComposerForType(
                            RecordType.CUSTOM,
                            customDefUuid = item.bindingKey.removePrefix("custom:"),
                        )
                        showMore = false
                    }
                    else -> {
                        val t = RecordType.fromKey(item.typeKey ?: return@MoreSheet) ?: return@MoreSheet
                        openComposerForType(t)
                        showMore = false
                    }
                }
            },
        )
    }

    draft?.let { d ->
        val noteSuggestions = container.care.recentNoteCandidates(baby.clientUuid, d.type.key)
        val openSleepForUi = container.care.openSleepRecord(baby.clientUuid)
        ComposerSheet(
            draft = d,
            noteSuggestions = noteSuggestions,
            amountStepMl = settings.amountStepMl,
            editingRecordUuid = editingRecordUuid,
            openSleep = openSleepForUi,
            onDismiss = {
                val keepPaths = editingRecordUuid
                    ?.let { container.care.store().getRecord(it)?.photos?.map { p -> p.localPath }?.toSet() }
                    ?: emptySet()
                d.photos
                    .filter { it.isDraftOwned && it.localPath !in keepPaths }
                    .forEach { PhotoStore.deleteIfDraftOwned(it) }
                container.care.discardDraft(d)
                draft = null
                editingRecordUuid = null
            },
            onConfirm = { updated ->
                val editId = editingRecordUuid
                if (editId != null) {
                    when (
                        container.care.editRecord(
                            uuid = editId,
                            note = updated.note,
                            payloadJson = updated.payloadJson,
                            timestampMs = updated.timestampMs,
                            photos = updated.photos,
                        )
                    ) {
                        is GfResult.Ok -> {
                            container.sync.markLocalPending(1)
                            draft = null
                            editingRecordUuid = null
                            onChanged()
                        }
                        is GfResult.Err -> Unit
                    }
                } else {
                    when (val r = container.care.confirmCreate(updated)) {
                        is GfResult.Ok -> {
                            container.sync.markLocalPending(1)
                            if (updated.type in setOf(RecordType.NURSING, RecordType.FORMULA, RecordType.PUMPED_FEED)) {
                                container.care.createPlan(
                                    babyClientUuid = baby.clientUuid,
                                    typeKey = RecordType.NURSING.key,
                                    scheduledAtMs = SystemClock.nowEpochMs() + 3 * 60 * 60 * 1000,
                                    isNextFeed = true,
                                )
                            }
                            draft = null
                            editingRecordUuid = null
                            onChanged()
                        }
                        is GfResult.Err -> {
                            if (r.error.toString().contains("FUTURE_AS_PLAN")) {
                                draft = null
                                editingRecordUuid = null
                                onChanged()
                            }
                        }
                    }
                }
            },
            onChange = { draft = it },
            onAddPhoto = {
                if ((draft?.photos?.size ?: 0) < PhotoStore.MAX_PHOTOS_PER_RECORD) {
                    photoPicker.launch("image/*")
                }
            },
            onPreviewPhoto = { photos, idx ->
                previewPhotos = photos
                previewIndex = idx
            },
            onAdjustTimeMinutes = { delta ->
                val cur = draft ?: return@ComposerSheet
                draft = cur.copy(
                    timestampMs = cur.timestampMs + delta * 60_000L,
                    dirty = true,
                )
            },
        )
    }

    deleteUuid?.let { uuid ->
        AlertDialog(
            onDismissRequest = { deleteUuid = null },
            title = { Text("确认删除？") },
            text = { Text("删除后可在同步中体现软删除。") },
            confirmButton = {
                TextButton(onClick = {
                    container.care.deleteRecord(uuid, confirmed = true)
                    deleteUuid = null
                    onChanged()
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteUuid = null }) { Text("取消") } },
        )
    }

    previewPhotos?.let { photos ->
        FullscreenPhotoPager(
            photos = photos,
            startIndex = previewIndex,
            onDismiss = { previewPhotos = null },
        )
    }
}

@Composable
fun PhotoThumbnailRow(photos: List<PhotoRef>, onOpen: (Int) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(photos) { index, photo ->
            val bmp = remember(photo.mediaUuid, photo.localPath) { decodePhotoBitmap(photo) }
            Box(
                Modifier
                    .size(56.dp)
                    .background(Color(0xFFE0E0E0))
                    .clickable { onOpen(index) },
                contentAlignment = Alignment.Center,
            ) {
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "照片缩略图",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Text("${index + 1}", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
fun FullscreenPhotoPager(
    photos: List<PhotoRef>,
    startIndex: Int,
    onDismiss: () -> Unit,
) {
    var index by remember { mutableIntStateOf(startIndex.coerceIn(0, photos.lastIndex.coerceAtLeast(0))) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("照片 ${index + 1}/${photos.size}") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val photo = photos.getOrNull(index)
                val bmp = photo?.let { remember(it.mediaUuid) { decodePhotoBitmap(it) } }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                        .background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "全屏照片",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    } else {
                        Text(photo?.localPath ?: "", color = Color.White)
                    }
                }
                Row {
                    TextButton(
                        onClick = { if (index > 0) index-- },
                        enabled = index > 0,
                    ) { Text("上一张") }
                    TextButton(
                        onClick = { if (index < photos.lastIndex) index++ },
                        enabled = index < photos.lastIndex,
                    ) { Text("下一张") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/** Decode JPEG from app-private file path or legacy b64: payload. */
fun decodePhotoBitmap(photo: PhotoRef): android.graphics.Bitmap? =
    PhotoStore.decodeBitmap(photo)
        ?: android.graphics.Bitmap.createBitmap(48, 48, android.graphics.Bitmap.Config.ARGB_8888).also {
            it.eraseColor(android.graphics.Color.rgb(200, 160, 140))
        }

@Composable
fun SummaryScreen(container: AppContainer, settings: LocalSettings, modifier: Modifier = Modifier, onChanged: () -> Unit) {
    val baby = container.family.currentBaby() ?: return
    val density = LeziDensity.forTemplate(settings.template)
    val now = SystemClock.nowEpochMs()
    val weekStart = CareAggregation.dayStartMs(now) - 3 * DayAxisModel.DAY_MS
    val days = CareAggregation.weekModule(
        container.care.store().allRecords().filter { it.babyClientUuid == baby.clientUuid },
        weekStart,
        now,
    )
    val milkBars = ChartSeries.weekBars(days, ChartSeries.WeekMetric.MILK_ML)
    val sleepBars = ChartSeries.weekBars(days, ChartSeries.WeekMetric.SLEEP_MIN)
    val peeBars = ChartSeries.weekBars(days, ChartSeries.WeekMetric.PEE)
    val poopBars = ChartSeries.weekBars(days, ChartSeries.WeekMetric.POOP)

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(density.panelContent)
            .semantics { contentDescription = "本周汇总图表" },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("本周汇总", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = {
                runForegroundSync(container)
                onChanged()
            }) { Text("同步") }
        }
        Spacer(Modifier.height(density.sectionGap))
        WeekBarChart(milkBars, "喂养 ml", Color(0xFF2A9D8F), density)
        Spacer(Modifier.height(density.sectionGap))
        WeekBarChart(sleepBars, "睡眠 分钟", Color(0xFF457B9D), density)
        Spacer(Modifier.height(density.sectionGap))
        WeekBarChart(peeBars, "尿 次数", Color(0xFFE9C46A), density)
        Spacer(Modifier.height(density.sectionGap))
        WeekBarChart(poopBars, "便 次数", Color(0xFFB08968), density)
        Spacer(Modifier.height(8.dp))
        days.forEachIndexed { i, d ->
            Text(
                "D$i 奶${d.milkMl} 睡${d.sleepMinutes} 尿${d.peeCount} 便${d.poopCount}",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
fun GrowthScreen(container: AppContainer, settings: LocalSettings, modifier: Modifier = Modifier, onChanged: () -> Unit) {
    val baby = container.family.currentBaby() ?: return
    val density = LeziDensity.forTemplate(settings.template)
    var grams by remember { mutableStateOf("5000") }
    val ageMonths = remember(baby.birthdayEpochDay) {
        val days = (SystemClock.nowEpochMs() / 86_400_000L - baby.birthdayEpochDay).coerceAtLeast(0)
        (days / 30).toInt()
    }
    val male = baby.sex.name == "MALE"
    val weightDrawable = ChartSeries.growthWeightDrawable(
        ageMonthsNow = ageMonths,
        male = male,
        weightRecords = container.care.store().allRecords().filter { it.babyClientUuid == baby.clientUuid },
        birthdayEpochDay = baby.birthdayEpochDay,
    )
    val lengthDrawable = ChartSeries.growthLengthDrawable(ageMonths, male)

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(density.panelContent)
            .semantics { contentDescription = "成长曲线" },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("成长曲线", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = {
                runForegroundSync(container)
                onChanged()
            }) { Text("同步") }
        }
        Text(GrowthCurves.NON_DIAGNOSIS_DISCLAIMER, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(density.sectionGap))
        GrowthCurveCanvas(weightDrawable, "体重 kg · WS/T 423 参考", density)
        Spacer(Modifier.height(density.sectionGap))
        GrowthCurveCanvas(lengthDrawable, "身长/身高 cm 参考", density)
        Spacer(Modifier.height(density.sectionGap))
        OutlinedTextField(value = grams, onValueChange = { grams = it }, label = { Text("体重 g") })
        Button(onClick = {
            val d = container.care.openComposer(RecordType.WEIGHT, baby.clientUuid)
            container.care.confirmCreate(
                d.copy(payloadJson = """{"grams":${grams.toIntOrNull() ?: 0}}""", dirty = true),
            )
            onChanged()
        }) { Text("录入体重") }
    }
}

@Composable
fun AccountScreen(container: AppContainer, settings: LocalSettings, modifier: Modifier = Modifier, onChanged: () -> Unit) {
    val density = LeziDensity.forTemplate(settings.template)
    AccountStack(container = container, density = density, modifier = modifier, onChanged = onChanged)
}

@Composable
fun MenuScreen(
    container: AppContainer,
    settings: LocalSettings,
    onSettingsChange: (LocalSettings) -> Unit,
    modifier: Modifier = Modifier,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val reminderScheduler = remember {
        LocalReminderScheduler(context).also {
            it.setLocalRemindersEnabled(settings.localReminders)
            it.setSystemCalendarProjectionEnabled(settings.systemCalendarProjection)
        }
    }
    var clearStep by remember { mutableStateOf(0) }
    var exportText by remember { mutableStateOf<String?>(null) }
    var reminderMsg by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var showLayout by remember { mutableStateOf(false) }

    if (showSearch) {
        SearchScreen(container = container, onDismiss = { showSearch = false })
        return
    }
    if (showLayout) {
        LayoutEditorScreen(
            care = container.care,
            onDismiss = { showLayout = false },
            onChanged = onChanged,
            reduceMotion = settings.reduceMotion,
        )
        return
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("菜单", style = MaterialTheme.typography.headlineSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("记录簿模板（journal 更紧凑）")
            Switch(
                checked = settings.template == UiTemplate.JOURNAL,
                onCheckedChange = {
                    onSettingsChange(
                        settings.copy(template = if (it) UiTemplate.JOURNAL else UiTemplate.WARM),
                    )
                },
            )
        }
        Text(
            "warm cardPad=${LeziDensity.Warm.cardPad} · journal=${LeziDensity.Journal.cardPad}",
            style = MaterialTheme.typography.labelSmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("深色")
            Switch(
                checked = settings.darkTheme,
                onCheckedChange = { onSettingsChange(settings.copy(darkTheme = it)) },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("减弱动效")
            Switch(
                checked = settings.reduceMotion,
                onCheckedChange = { onSettingsChange(settings.copy(reduceMotion = it)) },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("惯用手：左")
            Switch(
                checked = settings.handedness == Handedness.RIGHT,
                onCheckedChange = {
                    onSettingsChange(
                        settings.copy(handedness = if (it) Handedness.RIGHT else Handedness.LEFT),
                    )
                },
            )
            Text("右")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("24 小时制")
            Switch(
                checked = settings.timeFormat == TimeFormat.H24,
                onCheckedChange = {
                    onSettingsChange(
                        settings.copy(timeFormat = if (it) TimeFormat.H24 else TimeFormat.H12),
                    )
                },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("周起始周一")
            Switch(
                checked = settings.weekStartsOnMonday,
                onCheckedChange = { onSettingsChange(settings.copy(weekStartsOnMonday = it)) },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("日龄算法（生后 N 日）")
            Switch(
                checked = settings.useDayAgeMode,
                onCheckedChange = { onSettingsChange(settings.copy(useDayAgeMode = it)) },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("奶量步进 5ml")
            Switch(
                checked = settings.amountStepMl == 5,
                onCheckedChange = {
                    onSettingsChange(settings.copy(amountStepMl = if (it) 5 else 10))
                },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("时间轴新在上")
            Switch(
                checked = settings.timelineNewestFirst,
                onCheckedChange = { onSettingsChange(settings.copy(timelineNewestFirst = it)) },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("发热说明")
            Switch(
                checked = settings.showFeverHint,
                onCheckedChange = { onSettingsChange(settings.copy(showFeverHint = it)) },
            )
        }
        if (settings.showFeverHint) {
            Text("低月龄发热请及时就医（说明可关）。非诊断。", style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("系统日历投影")
            Switch(
                checked = settings.systemCalendarProjection,
                onCheckedChange = {
                    onSettingsChange(settings.copy(systemCalendarProjection = it))
                    reminderScheduler.setSystemCalendarProjectionEnabled(it)
                },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("本地提醒")
            Switch(
                checked = settings.localReminders,
                onCheckedChange = {
                    onSettingsChange(settings.copy(localReminders = it))
                    reminderScheduler.setLocalRemindersEnabled(it)
                },
            )
        }
        Text("分级披露：")
        reminderScheduler.policy().gradedDisclosureCopy().forEach { Text("· $it") }
        Button(onClick = {
            val plan = container.care.pendingPlans(
                container.family.currentBaby()?.clientUuid ?: return@Button,
            ).firstOrNull()
            if (plan != null) {
                val ok = reminderScheduler.scheduleInexactReminder(plan)
                val proj = reminderScheduler.projectToSystemCalendar(plan)
                reminderMsg = "提醒=${if (ok) "已排" else "跳过"} 日历=${proj ?: "未写/无权限"}"
            } else {
                reminderMsg = "无待履行计划可投影"
            }
        }) { Text("为待履行计划调度提醒/投影") }
        if (reminderMsg.isNotBlank()) Text(reminderMsg)

        Button(onClick = { showSearch = true }) { Text("打开搜索") }
        Button(onClick = {
            val b = container.family.currentBaby() ?: return@Button
            exportText = container.care.exportTxt(b.clientUuid)
        }) { Text("导出 TXT") }
        exportText?.let { Text(it.take(200)) }
        var pdfMsg by remember { mutableStateOf("") }
        var pendingPdfFile by remember { mutableStateOf<File?>(null) }
        val pdfShareLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) {
            val exporter = PdfShareExport(context)
            pendingPdfFile?.let { exporter.discardAfterShare(it) }
            pendingPdfFile = null
            exporter.purgeStaleExports()
            pdfMsg = "分享结束；已清理本次导出缓存"
        }
        Button(onClick = {
            val b = container.family.currentBaby() ?: return@Button
            val exporter = PdfShareExport(context)
            exporter.purgeStaleExports()
            val file = exporter.createPdfFile(
                care = container.care,
                babyClientUuid = b.clientUuid,
                title = "乐记 · ${b.nickname}",
            )
            PdfShareExport.assertExistsForShare(file)
            pendingPdfFile = file
            try {
                pdfShareLauncher.launch(
                    Intent.createChooser(exporter.shareIntent(file), "分享乐记 PDF"),
                )
                pdfMsg = "已打开系统分享；PDF 在分享结束后删除"
            } catch (_: Exception) {
                exporter.discardAfterShare(file)
                pendingPdfFile = null
                pdfMsg = "无法打开分享；已清理导出文件"
            }
        }) { Text("导出 PDF") }
        if (pdfMsg.isNotBlank()) Text(pdfMsg, style = MaterialTheme.typography.labelSmall)
        Button(onClick = { container.persist() }) { Text("本机备份") }
        Text("本机备份不替代家庭同步。")
        Button(onClick = { showLayout = true }) { Text("布局与自定义项目") }
        Button(onClick = {
            // Widget config entry — open formula composer deep-link intent for pin instruction
            val intent = Intent(context, com.lezi.gf.app.MainActivity::class.java).apply {
                action = com.lezi.gf.app.MainActivity.ACTION_OPEN_COMPOSER
                putExtra(com.lezi.gf.app.MainActivity.EXTRA_COMPOSER_TYPE, RecordType.FORMULA.key)
            }
            context.startActivity(intent)
        }) { Text("小组件快捷（打开配方奶 Composer，不写入）") }
        Text(
            "将「乐记」小组件添加到主屏幕后，点摘要打开预填 Composer。",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = {
            val joined = container.family.account().joinState == JoinState.JOINED
            if (container.settings.canCheckAppUpdate(joined)) {
                container.sync.checkAppUpdate(true)
            }
            onChanged()
        }) {
            Text(
                if (container.family.account().joinState == JoinState.JOINED) "检查更新"
                else container.settings.unjoinedUpdateHonestyMessage().take(12) + "…",
            )
        }
        Text("关于 乐记 ${ProductVersion.NAME}")
        when (clearStep) {
            0 -> Button(onClick = { clearStep = 1 }) { Text("清空记录…") }
            1 -> Button(onClick = {
                container.care.clearAllRecords(step1 = true, step2 = true)
                clearStep = 0
                onChanged()
            }) { Text("再次确认清空记录") }
        }
    }
}

object UpdateShells {
    @Composable
    fun Optional(update: WireAppUpdate, onLater: () -> Unit, onNow: () -> Unit) {
        AlertDialog(
            onDismissRequest = onLater,
            title = { Text("发现新版本 ${update.version_name}") },
            text = { Text(update.release_notes.ifBlank { "可选更新" }) },
            confirmButton = { TextButton(onClick = onNow) { Text("现在更新") } },
            dismissButton = { TextButton(onClick = onLater) { Text("稍后") } },
        )
    }

    @Composable
    fun Forced(update: WireAppUpdate) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("需要更新到 ${update.version_name}", style = MaterialTheme.typography.headlineSmall)
            Text("当前版本过旧，无法继续使用主功能。")
            Text(update.release_notes)
        }
    }
}

/** Public for unit tests — uses [ForegroundSyncCoordinator] apply path. */
fun runForegroundSync(container: AppContainer): GfResult<*> {
    return ForegroundSyncCoordinator(container.care, container.family, container.sync).run()
}
