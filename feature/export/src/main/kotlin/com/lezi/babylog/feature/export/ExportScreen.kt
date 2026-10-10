package com.lezi.babylog.feature.export

import android.content.ClipData
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lezi.babylog.core.common.failure.FailureAction
import com.lezi.babylog.core.common.failure.FailureKind
import com.lezi.babylog.core.ui.failure.FailureExplanationDialog
import com.lezi.babylog.designsystem.LeziSurfacePanel
import com.lezi.babylog.designsystem.LeziDetailTopBar
import com.lezi.babylog.designsystem.LeziDatePicker
import com.lezi.babylog.designsystem.LeziDatePickerDialog
import com.lezi.babylog.designsystem.LeziMotion
import com.lezi.babylog.designsystem.LeziPrimaryButton
import com.lezi.babylog.designsystem.LeziSecondaryButton
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.LeziThemeExt
import com.lezi.babylog.designsystem.LeziSwitch
import com.lezi.babylog.designsystem.StateContainer
import com.lezi.babylog.designsystem.StateKind
import com.lezi.babylog.designsystem.leziMotionMillis
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.export.ExportPort
import dagger.hilt.android.lifecycle.HiltViewModel
import com.lezi.babylog.core.ui.formatBabyBirthday
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneOffset
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Primary (PDF) / secondary (TXT) action chrome for the export surface.
 * Only the active format owns busy label + spinner while generating; the other
 * stays idle but disabled. After prepare succeeds, [sharePending] keeps controls
 * locked without lying about "正在生成…".
 */
internal data class ExportActionChrome(
    val txtLabel: String,
    val pdfLabel: String,
    val txtBusy: Boolean,
    val pdfBusy: Boolean,
    val controlsEnabled: Boolean,
)

/** Pure presentation seam — no domain/export IO. */
internal const val EXPORT_GENERATION_MAX_ELAPSED_MILLIS = 30_000L

internal suspend fun <T> runBoundedExportGeneration(
    timeoutMillis: Long = EXPORT_GENERATION_MAX_ELAPSED_MILLIS,
    block: suspend () -> T,
): T = withTimeout(timeoutMillis) { block() }

internal fun exportActionChrome(
    busyFormat: ExportFormat?,
    sharePending: Boolean = false,
): ExportActionChrome {
    return ExportActionChrome(
        txtLabel = if (busyFormat == ExportFormat.Txt) "正在生成…" else "导出 TXT 并分享",
        pdfLabel = if (busyFormat == ExportFormat.Pdf) "正在生成…" else "导出 PDF 并分享",
        txtBusy = busyFormat == ExportFormat.Txt,
        pdfBusy = busyFormat == ExportFormat.Pdf,
        controlsEnabled = busyFormat == null && !sharePending,
    )
}

internal data class ExportUiState(
    val busyFormat: ExportFormat? = null,
    val request: ExportRequest? = null,
    val preview: String? = null,
    val pendingShare: PreparedExport? = null,
    val failureKind: FailureKind? = null,
    /** 未识别异常的内联兜底文案（票 10：不再一律折叠成 InvalidInput 说明框）。 */
    val failureMessage: String? = null,
    /** 所选范围 0 条护理记录：给空态卡，不产出近空文件。 */
    val emptyRange: Boolean = false,
) {
    /** True while file generation is running (not while sharesheet is pending). */
    val busy: Boolean get() = busyFormat != null

    /** Double-submit / control lock: generating or waiting for sharesheet launch. */
    val inFlight: Boolean get() = busyFormat != null || pendingShare != null
}

@HiltViewModel
class ExportViewModel @Inject constructor(
    private val exportPort: ExportPort,
    private val careLog: CareLog,
    private val fileGenerator: ExportFileGenerator,
) : ViewModel() {
    private val _state = MutableStateFlow(ExportUiState())
    internal val state = _state.asStateFlow()

    internal fun exportRange(
        from: LocalDate,
        to: LocalDate,
        format: ExportFormat,
        includePhotos: Boolean,
    ) {
        if (_state.value.inFlight) return
        _state.update {
            it.copy(
                busyFormat = format,
                request = ExportRequest(ExportRequestDraft(from, to, includePhotos), format),
                preview = null,
                pendingShare = null,
                failureKind = null,
                failureMessage = null,
                emptyRange = false,
            )
        }
        viewModelScope.launch {
            var ownedExport: PreparedExport? = null
            var published = false
            try {
                val generation = try {
                    runBoundedExportGeneration {
                        withContext(Dispatchers.IO) {
                            val baby = careLog.getCurrentBaby() ?: error("请先添加宝宝")
                            val document = exportPort.exportDocument(baby.id, from, to)
                            // 票 10（T3）：范围内 0 条护理记录 → 空态，不产出近空文件。
                            if (document.recordCount == 0) {
                                null
                            } else {
                                val prepared = fileGenerator.prepare(
                                    format = format,
                                    title = "乐记导出",
                                    document = document,
                                    includePhotos = includePhotos,
                                )
                                // Own the file before crossing the cancellable dispatcher boundary.
                                ownedExport = prepared
                                document.text to prepared
                            }
                        }
                    }
                } catch (timeout: TimeoutCancellationException) {
                    failWith(exportFailureAttribution(timeout))
                    return@launch
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    failWith(exportFailureAttribution(error))
                    return@launch
                }
                currentCoroutineContext().ensureActive()
                _state.update {
                    if (generation == null) {
                        it.copy(busyFormat = null, emptyRange = true)
                    } else {
                        // Clear generate-busy when preview is ready; keep controls locked via
                        // pendingShare until shareLaunched / shareDismissed so chrome is honest.
                        it.copy(
                            busyFormat = null,
                            preview = generation.first,
                            pendingShare = generation.second,
                        )
                    }
                }
                published = true
            } finally {
                if (!published) ownedExport?.file?.delete()
            }
        }
    }

    override fun onCleared() {
        // A file not yet handed to the Sharesheet still belongs to this screen.
        _state.value.pendingShare?.file?.delete()
        super.onCleared()
    }

    private fun failWith(attribution: ExportFailureAttribution) {
        _state.update {
            it.copy(
                busyFormat = null,
                failureKind = attribution.dialogKind,
                failureMessage = attribution.inlineMessage,
            )
        }
    }

    internal fun shareLaunched() {
        // The Sharesheet may stay open indefinitely before a target reads the URI. Keep the
        // cache file until age-based startup/next-export cleanup instead of racing that read.
        _state.update { it.copy(busyFormat = null, pendingShare = null) }
    }

    internal fun consumeFailureKind() {
        _state.update { it.copy(failureKind = null) }
    }

    internal fun shareDismissed() {
        // 票 10（T3）：sharesheet 没能打开或被用户取消，都不算失败——生成物按龄保留，
        // 用户可在本页再次导出分享；终态提示由界面层给「文件已生成，可在本页再次分享」。
        _state.update { it.copy(busyFormat = null, pendingShare = null) }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ExportRoute(
    onBack: () -> Unit,
    vm: ExportViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // 票 10（T3）：sharesheet 是系统选择器，不回传取消/完成结果；用「打开后回到本页」
    // 这个可观察事实补发终态提示——取消或分享完成，文件都还在。
    var awaitingShareReturn by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && awaitingShareReturn) {
                awaitingShareReturn = false
                scope.launch { snackbarHostState.showSnackbar(EXPORT_SHARE_CANCELLED_SNACKBAR) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var draft by rememberExportRequestDraft()
    val fromDate = draft.from
    val toDate = draft.to
    val includePhotos = draft.includePhotos
    var dateTarget by remember { mutableStateOf<ExportDateTarget?>(null) }
    var inputError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.pendingShare) {
        val share = state.pendingShare ?: return@LaunchedEffect
        runCatching {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = share.mimeType
                putExtra(Intent.EXTRA_SUBJECT, "乐记导出")
                putExtra(Intent.EXTRA_STREAM, share.uri)
                clipData = ClipData.newUri(context.contentResolver, "乐记导出", share.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, share.chooserTitle))
        }.onSuccess {
            awaitingShareReturn = true
            vm.shareLaunched()
            // 用组合级 scope：shareLaunched 清掉 pendingShare 会重启本 effect，
            // 直接 suspend 会被取消导致提示闪断。
            scope.launch { snackbarHostState.showSnackbar(EXPORT_GENERATED_SNACKBAR) }
        }.onFailure {
            // 打不开 sharesheet 也不是失败：文件已生成并按龄保留，可从本页再次导出分享。
            vm.shareDismissed()
            scope.launch { snackbarHostState.showSnackbar(EXPORT_SHARE_CANCELLED_SNACKBAR) }
        }
    }

    fun request(format: ExportFormat) {
        if (toDate.isBefore(fromDate)) {
            inputError = "请输入有效日期范围"
            return
        }
        inputError = null
        vm.exportRange(fromDate, toDate, format, includePhotos)
    }

    val actions = exportActionChrome(
        busyFormat = state.busyFormat,
        sharePending = state.pendingShare != null,
    )
    val previewEnterMs = leziMotionMillis(LeziMotion.Base)
    val previewExitMs = leziMotionMillis(LeziMotion.Fast)

    Scaffold(
        topBar = {
            LeziDetailTopBar(title = "导出记录", onBack = onBack)
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(LeziSpacing.Page)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
        ) {
            LeziSurfacePanel(Modifier.fillMaxWidth(), bottomBand = true) {
                Column(verticalArrangement = Arrangement.spacedBy(LeziSpacing.Xs)) {
                    Text("导出范围", style = LeziTypography.TitleSm)
                    Text(
                        "${fromDate.exportLabel()} — ${toDate.exportLabel()}",
                        style = LeziTypography.BodyStrong,
                    )
                    LeziSecondaryButton(
                        label = "选择开始日期",
                        onClick = { dateTarget = ExportDateTarget.From },
                        enabled = actions.controlsEnabled,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LeziSecondaryButton(
                        label = "选择结束日期",
                        onClick = { dateTarget = ExportDateTarget.To },
                        enabled = actions.controlsEnabled,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("PDF 包含记录图片", style = LeziTypography.Body)
                LeziSwitch(
                    checked = includePhotos,
                    onCheckedChange = { draft = draft.copy(includePhotos = it) },
                    enabled = actions.controlsEnabled,
                )
            }
            // 票 10（T3）：生成期在按钮区上方给不确定进度条 + 一句时长预期。
            if (state.busy) {
                Column(verticalArrangement = Arrangement.spacedBy(LeziSpacing.Xxs)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        EXPORT_GENERATION_NOTE,
                        style = LeziTypography.Meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Secondary TXT first; primary PDF below — hierarchy matches Lezi patterns.
            LeziSecondaryButton(
                label = actions.txtLabel,
                onClick = { request(ExportFormat.Txt) },
                enabled = actions.controlsEnabled,
                busy = actions.txtBusy,
                modifier = Modifier.fillMaxWidth(),
            )
            LeziPrimaryButton(
                label = actions.pdfLabel,
                onClick = { request(ExportFormat.Pdf) },
                enabled = actions.controlsEnabled,
                busy = actions.pdfBusy,
                modifier = Modifier.fillMaxWidth(),
            )
            inputError?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            state.failureMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            // 票 10（T3）：范围内 0 条护理记录 → 空态卡，不产出近空文件。
            if (state.emptyRange) {
                StateContainer(
                    kind = StateKind.Empty,
                    title = EXPORT_EMPTY_RANGE_TITLE,
                    message = EXPORT_EMPTY_RANGE_MESSAGE,
                    modifier = Modifier.testTag("export_empty_range"),
                )
            }
            AnimatedVisibility(
                visible = state.preview != null,
                enter = fadeIn(animationSpec = tween(durationMillis = previewEnterMs)) +
                    expandVertically(animationSpec = tween(durationMillis = previewEnterMs)),
                exit = fadeOut(animationSpec = tween(durationMillis = previewExitMs)) +
                    shrinkVertically(animationSpec = tween(durationMillis = previewExitMs)),
            ) {
                LeziSurfacePanel(Modifier.fillMaxWidth(), bottomBand = true) {
                    Text("预览", style = LeziTypography.TitleSm)
                    Spacer(Modifier.height(LeziSpacing.Xs))
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(state.preview.orEmpty().take(2_000), style = LeziThemeExt.typography.Mono)
                    }
                }
            }
        }
    }

    dateTarget?.let { target ->
        val selected = if (target == ExportDateTarget.From) fromDate else toDate
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = selected
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli(),
        )
        LeziDatePickerDialog(
            onDismissRequest = { dateTarget = null },
            onConfirm = {
                picker.selectedDateMillis?.let { millis ->
                    val picked = Instant.ofEpochMilli(millis)
                        .atZone(ZoneOffset.UTC)
                        .toLocalDate()
                    if (target == ExportDateTarget.From) {
                        draft = draft.copy(from = picked, to = maxOf(toDate, picked))
                    } else {
                        draft = draft.copy(to = picked, from = minOf(fromDate, picked))
                    }
                    inputError = null
                }
                dateTarget = null
            },
        ) {
            LeziDatePicker(state = picker)
        }
    }

    state.failureKind?.takeIf { it.usesSharedDialog }?.let { kind ->
        FailureExplanationDialog(
            kind = kind,
            onAction = { action ->
                vm.consumeFailureKind()
                if (action == FailureAction.GoBack) onBack()
            },
            onDismissRequest = vm::consumeFailureKind,
        )
    }
}

private enum class ExportDateTarget { From, To }

private fun LocalDate.exportLabel(): String = formatBabyBirthday(toEpochDay())
