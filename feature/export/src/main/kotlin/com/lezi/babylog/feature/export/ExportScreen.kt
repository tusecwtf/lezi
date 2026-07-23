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
    fun exportThisMonth(onResult: (String) -> Unit) {
        viewModelScope.launch {
            val baby = careLog.getCurrentBaby() ?: return@launch
            val ym = YearMonth.now()
            val text = exportPort.exportTxt(baby.id, ym.atDay(1), ym.atEndOfMonth())
            onResult(text)
        }
    }

    fun exportRange(from: LocalDate, to: LocalDate, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val baby = careLog.getCurrentBaby() ?: return@launch
            onResult(exportPort.exportTxt(baby.id, from, to))
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
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("导出 TXT") },
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
            Text("导出当前宝宝的记录为纯文本，可分享到文件/邮件。无水印。", style = LeziTypography.Body)
            LeziPrimaryButton(
                "导出本月并分享",
                onClick = {
                    vm.exportThisMonth { text ->
                        preview = text
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "乐记导出")
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        context.startActivity(Intent.createChooser(send, "分享导出"))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            LeziPrimaryButton(
                "导出本月 PDF 并分享",
                onClick = {
                    vm.exportThisMonth { text ->
                        preview = text
                        PdfExport.writeAndShare(context, "乐记导出", text)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
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
