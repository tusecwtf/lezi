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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lezi.babylog.core.common.productUiError
import com.lezi.babylog.designsystem.LeziSurfacePanel
import com.lezi.babylog.designsystem.LeziDetailTopBar
import com.lezi.babylog.designsystem.LeziDatePicker
import com.lezi.babylog.designsystem.LeziDatePickerDialog
import com.lezi.babylog.designsystem.LeziMotion
import com.lezi.babylog.designsystem.LeziPrimaryButton
import com.lezi.babylog.designsystem.LeziSecondaryButton
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.LeziSwitch
import com.lezi.babylog.designsystem.leziMotionMillis
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.export.ExportPort
import dagger.hilt.android.lifecycle.HiltViewModel
import com.lezi.babylog.core.ui.formatBabyBirthday
import java.time.LocalDate
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val preview: String? = null,
    val pendingShare: PreparedExport? = null,
    val error: String? = null,
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
            it.copy(busyFormat = format, preview = null, pendingShare = null, error = null)
        }
        viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    val baby = careLog.getCurrentBaby() ?: error("请先添加宝宝")
                    val document = exportPort.exportDocument(baby.id, from, to)
                    val prepared = fileGenerator.prepare(
                        format = format,
                        title = "乐记导出",
                        document = document,
                        includePhotos = includePhotos,
                    )
                    document.text to prepared
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        busyFormat = null,
                        error = productUiError(error, "导出失败，请重试"),
                    )
                }
                return@launch
            }
            // Clear generate-busy when preview is ready; keep controls locked via pendingShare
            // until shareLaunched / shareFailed so chrome is honest post-prep.
            _state.update {
                it.copy(
                    busyFormat = null,
                    preview = result.first,
                    pendingShare = result.second,
                    error = null,
                )
            }
        }
    }

    internal fun shareLaunched() {
        // The Sharesheet may stay open indefinitely before a target reads the URI. Keep the
        // cache file until age-based startup/next-export cleanup instead of racing that read.
        _state.update { it.copy(busyFormat = null, pendingShare = null) }
    }

    internal fun shareFailed(error: Throwable) {
        _state.value.pendingShare?.let(fileGenerator::discard)
        _state.update {
            it.copy(
                busyFormat = null,
                pendingShare = null,
                error = productUiError(error, "无法打开系统分享，请重试"),
            )
        }
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
    val defaultMonth = remember { YearMonth.now() }
    var fromDate by remember { mutableStateOf(defaultMonth.atDay(1)) }
    var toDate by remember { mutableStateOf(defaultMonth.atEndOfMonth()) }
    var dateTarget by remember { mutableStateOf<ExportDateTarget?>(null) }
    var includePhotos by remember { mutableStateOf(true) }
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
            vm.shareLaunched()
        }.onFailure(vm::shareFailed)
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
                    onCheckedChange = { includePhotos = it },
                    enabled = actions.controlsEnabled,
                )
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
            (inputError ?: state.error)?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
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
                        Text(state.preview.orEmpty().take(2_000), style = LeziTypography.Mono)
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
                        fromDate = picked
                        if (toDate.isBefore(picked)) toDate = picked
                    } else {
                        toDate = picked
                        if (fromDate.isAfter(picked)) fromDate = picked
                    }
                    inputError = null
                }
                dateTarget = null
            },
        ) {
            LeziDatePicker(state = picker)
        }
    }
}

private enum class ExportDateTarget { From, To }

private fun LocalDate.exportLabel(): String = formatBabyBirthday(toEpochDay())
