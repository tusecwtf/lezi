package com.lezi.babylog.feature.family

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.lezi.babylog.designsystem.LeziPrimaryButton
import com.lezi.babylog.designsystem.LeziSecondaryButton
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.dismissKeyboardOnTap
import com.lezi.babylog.sync.DEFAULT_SERVER_PORT
import com.lezi.babylog.sync.PUBLIC_CLEARTEXT_WARNING
import com.lezi.babylog.sync.isPublicCleartextBaseUrl

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FamilyNetworkSettingsSheet(
    ui: FamilyUi,
    host: String,
    onHostChange: (String) -> Unit,
    port: String,
    onPortChange: (String) -> Unit,
    ssid1: String,
    onSsid1Change: (String) -> Unit,
    ssid2: String,
    onSsid2Change: (String) -> Unit,
    previewBaseUrl: String,
    networkConfigured: Boolean,
    onUseCurrentWifi: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val persistedEmpty = ui.serverHost.isBlank() && ui.baseUrl.isBlank()
    val draftHost = host.trim()
    val draftPort = port.toIntOrNull() ?: DEFAULT_SERVER_PORT
    val draftSsids = listOf(ssid1, ssid2).map(String::trim).filter(String::isNotEmpty)
    val networkDraftDirty =
        draftHost != ui.serverHost.trim() ||
            (ui.serverHost.isNotBlank() && draftPort != ui.serverPort) ||
            (ui.serverHost.isBlank() && draftHost.isNotBlank()) ||
            draftSsids != ui.allowedSsids

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = LeziSpacing.Page)
                .padding(bottom = LeziSpacing.Xxl)
                .dismissKeyboardOnTap(),
            verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
        ) {
            Text("家庭网络设置", style = LeziTypography.TitleSm)
            FamilyGuideRow(
                step = "1",
                title = "填写并保存",
                detail = "服务器和家庭 Wi-Fi 仅存本机",
                complete = networkConfigured,
            )
            OutlinedTextField(
                value = host,
                onValueChange = onHostChange,
                label = { Text("家庭服务器主机（IP 或域名）") },
                placeholder = { Text("192.168.50.4") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = port,
                onValueChange = { onPortChange(it.filter(Char::isDigit).take(5)) },
                label = { Text("端口") },
                placeholder = { Text("8765") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = ssid1,
                onValueChange = onSsid1Change,
                label = { Text("家庭 Wi‑Fi 名称 1（如 2.4G）") },
                placeholder = { Text("当前连接的 Wi‑Fi 名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = ssid2,
                onValueChange = onSsid2Change,
                label = { Text("家庭 Wi‑Fi 名称 2（可选，如 5G）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            LeziSecondaryButton(
                "填入当前 Wi‑Fi 名称",
                onClick = onUseCurrentWifi,
                modifier = Modifier.fillMaxWidth(),
            )
            if (isPublicCleartextBaseUrl(previewBaseUrl)) {
                Text(
                    PUBLIC_CLEARTEXT_WARNING,
                    style = LeziTypography.Meta,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (persistedEmpty || networkDraftDirty) {
                Text(
                    if (networkDraftDirty && !persistedEmpty) "有未保存的更改"
                    else "保存后即可新建或加入家庭",
                    style = LeziTypography.Meta,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            LeziPrimaryButton(
                "保存家庭网络与服务器",
                onClick = onSave,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
