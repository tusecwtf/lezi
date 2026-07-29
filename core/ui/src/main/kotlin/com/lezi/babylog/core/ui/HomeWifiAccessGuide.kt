package com.lezi.babylog.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Android route selected by the shared sync permission policy, expressed without a sync dependency. */
enum class HomeWifiSettingsAction { AppPermission, LocationServices, Wifi }

object HomeWifiAccessGuideCopy {
    const val title = "允许识别家庭 Wi‑Fi"
    const val permissionTitle = "位置权限"
    const val permissionDetail = "仅用于读取当前 Wi‑Fi 名称"
    const val locationTitle = "定位服务"
    const val locationDetail = "需保持开启；位置数据不会上传"
}

fun homeWifiSettingsActionLabel(action: HomeWifiSettingsAction): String = when (action) {
    HomeWifiSettingsAction.AppPermission -> "打开权限设置"
    HomeWifiSettingsAction.LocationServices -> "开启定位服务"
    HomeWifiSettingsAction.Wifi -> "打开 Wi-Fi 设置"
}

/** One explanation and action surface used by onboarding and the account family wizard. */
@Composable
fun HomeWifiAccessGuideDialog(
    settingsAction: HomeWifiSettingsAction,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = { Text(HomeWifiAccessGuideCopy.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HomeWifiAccessGuideRow(
                    marker = "1",
                    title = HomeWifiAccessGuideCopy.permissionTitle,
                    detail = HomeWifiAccessGuideCopy.permissionDetail,
                )
                HomeWifiAccessGuideRow(
                    marker = "2",
                    title = HomeWifiAccessGuideCopy.locationTitle,
                    detail = HomeWifiAccessGuideCopy.locationDetail,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenSettings) {
                Text(homeWifiSettingsActionLabel(settingsAction))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("稍后") } },
    )
}

@Composable
private fun HomeWifiAccessGuideRow(
    marker: String,
    title: String,
    detail: String,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            Text("$marker · $title", style = MaterialTheme.typography.labelLarge)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
