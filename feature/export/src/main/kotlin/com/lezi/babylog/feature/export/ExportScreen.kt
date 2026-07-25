package com.lezi.babylog.feature.export

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lezi.babylog.designsystem.LeziCard
import com.lezi.babylog.designsystem.LeziPrimaryButton
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.ExportPort
import com.lezi.babylog.domain.ExportDocument
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class ExportViewModel @Inject constructor(
    private val exportPort: ExportPort,
    private val careLog: CareLog,
) : ViewModel() {
    fun exportRange(from: LocalDate, to: LocalDate, onResult: (Result<ExportDocument>) -> Unit) {
        viewModelScope.launch {
            val result = runCatching {
                val baby = careLog.getCurrentBaby() ?: error("请先添加宝宝")
                exportPort.exportDocument(baby.id, from, to)
            }
            onResult(result)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportRoute(
    onBack: () -> Unit,
    vm: ExportViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    var preview by remember { mutableStateOf<String?>(null) }
    val defaultMonth = remember { YearMonth.now() }
    var fromText by remember { mutableStateOf(defaultMonth.atDay(1).toString()) }
    var toText by remember { mutableStateOf(defaultMonth.atEndOfMonth().toString()) }
    var includePhotos by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("导出记录") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
                .padding(LeziSpacing.Page)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
        ) {
            Text("选择任意日期范围，可导出至少一个完整自然月。正文、备注与业务摘要使用时间轴同一语义。", style = LeziTypography.Body)
            OutlinedTextField(
                value = fromText,
                onValueChange = { fromText = it; error = null },
                label = { Text("开始日期 YYYY-MM-DD") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = toText,
                onValueChange = { toText = it; error = null },
                label = { Text("结束日期 YYYY-MM-DD") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("PDF 包含记录图片", style = LeziTypography.Body)
                Switch(checked = includePhotos, onCheckedChange = { includePhotos = it })
            }
            LeziPrimaryButton(
                "导出 TXT 并分享",
                onClick = {
                    val from = runCatching { LocalDate.parse(fromText) }.getOrNull()
                    val to = runCatching { LocalDate.parse(toText) }.getOrNull()
                    if (from == null || to == null || to.isBefore(from)) {
                        error = "请输入有效日期范围"
                    } else {
                        vm.exportRange(from, to) { result ->
                            result.onSuccess { document ->
                                preview = document.text
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, "乐记导出")
                                    putExtra(Intent.EXTRA_TEXT, document.text)
                                }
                                context.startActivity(Intent.createChooser(send, "分享导出"))
                            }.onFailure { error = it.message ?: "导出失败" }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            LeziPrimaryButton(
                "导出 PDF 并分享",
                onClick = {
                    val from = runCatching { LocalDate.parse(fromText) }.getOrNull()
                    val to = runCatching { LocalDate.parse(toText) }.getOrNull()
                    if (from == null || to == null || to.isBefore(from)) {
                        error = "请输入有效日期范围"
                    } else {
                        vm.exportRange(from, to) { result ->
                            result.onSuccess { document ->
                                preview = document.text
                                PdfExport.writeAndShare(
                                    context,
                                    "乐记导出",
                                    document.text,
                                    if (includePhotos) document.photoPaths else emptyList(),
                                )
                            }.onFailure { error = it.message ?: "导出失败" }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let {
                Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
            }
            preview?.let { text ->
                LeziCard(Modifier.fillMaxWidth()) {
                    Text("预览", style = LeziTypography.TitleSm)
                    Spacer(Modifier.height(LeziSpacing.Xs))
                    Text(text.take(2000), style = LeziTypography.Mono)
                }
            }
        }
    }
}
