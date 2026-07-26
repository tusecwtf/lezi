package com.lezi.babylog.feature.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.domain.SystemCalendarDisclosureLevel
import com.lezi.babylog.domain.SystemCalendarPort
import com.lezi.babylog.domain.SystemCalendarTarget
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SystemCalendarPortEntryPoint {
    fun systemCalendarPort(): SystemCalendarPort
}

/** User-facing labels for the three disclosure grades (settings + setup). */
fun systemCalendarDisclosureLabel(level: Int): String = when (
    SystemCalendarDisclosureLevel.fromStored(level)
) {
    SystemCalendarDisclosureLevel.EVENT_ONLY -> "仅乐记事件"
    SystemCalendarDisclosureLevel.BABY_AND_TYPE -> "宝宝昵称 · 记录类型"
    SystemCalendarDisclosureLevel.DETAILS -> "含备注与照片数量"
}

fun systemCalendarDisclosureDetail(level: Int): String = when (
    SystemCalendarDisclosureLevel.fromStored(level)
) {
    SystemCalendarDisclosureLevel.EVENT_ONLY ->
        "标题仅显示「乐记 · 护理计划」，不写入宝宝或类型。"
    SystemCalendarDisclosureLevel.BABY_AND_TYPE ->
        "标题「宝宝昵称 · 记录类型」。首次配置默认预选此项。"
    SystemCalendarDisclosureLevel.DETAILS ->
        "标题同第二级；描述可含文字备注，有照片时写「照片 N 张，打开乐记查看」与深链，从不上传照片。"
}

/**
 * Explicit user enable flow: request calendar permission only when opened, then
 * list writable calendars for a deliberate pick (never silent default).
 * Disclosure level is confirmed in the same surface (default L2).
 * Cancel leaves CarePlan save paths unblocked.
 */
@Composable
fun SystemCalendarSetupDialog(
    currentCalendarId: String?,
    currentDisclosureLevel: Int = 2,
    onPick: (calendarId: String) -> Unit,
    onDisclosureLevel: (level: Int) -> Unit = {},
    onDisable: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val port = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            SystemCalendarPortEntryPoint::class.java,
        ).systemCalendarPort()
    }
    var targets by remember { mutableStateOf<List<SystemCalendarTarget>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var selectedLevel by remember(currentDisclosureLevel) {
        mutableStateOf(currentDisclosureLevel.coerceIn(1, 3))
    }
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        hasPermission = result[Manifest.permission.READ_CALENDAR] == true &&
            result[Manifest.permission.WRITE_CALENDAR] == true
        if (!hasPermission) {
            status = "未授予日历权限，仍可仅使用乐记提醒"
        }
    }

    fun refreshTargets() {
        scope.launch {
            targets = port.listWritableCalendars()
            if (hasPermission && targets.isEmpty()) {
                status = "未找到可写日历"
            }
        }
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) refreshTargets()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("系统日历") },
        text = {
            Column {
                Text(
                    "仅本机有效，不随家庭同步。请选择可写日历与披露级别；默认预选第二级。",
                    style = LeziTypography.Meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "披露级别",
                    style = LeziTypography.Body,
                    modifier = Modifier.padding(top = 12.dp),
                )
                listOf(1, 2, 3).forEach { level ->
                    val selected = selectedLevel == level
                    Text(
                        buildString {
                            append(if (selected) "● " else "○ ")
                            append(systemCalendarDisclosureLabel(level))
                            if (level == 2) append("（默认）")
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedLevel = level
                                onDisclosureLevel(level)
                            }
                            .padding(vertical = 6.dp),
                        style = LeziTypography.Body,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    Text(
                        systemCalendarDisclosureDetail(level),
                        style = LeziTypography.Meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, bottom = 4.dp),
                    )
                }
                Text(
                    "目标日历",
                    style = LeziTypography.Body,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (!hasPermission) {
                    TextButton(
                        onClick = {
                            permissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.READ_CALENDAR,
                                    Manifest.permission.WRITE_CALENDAR,
                                ),
                            )
                        },
                    ) {
                        Text("授予日历权限")
                    }
                } else if (targets.isEmpty()) {
                    Text(
                        status ?: "正在加载可写日历…",
                        style = LeziTypography.Meta,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                } else {
                    targets.forEach { target ->
                        val selected = target.calendarId == currentCalendarId
                        Text(
                            buildString {
                                append(target.displayName)
                                if (target.accountName.isNotBlank()) {
                                    append(" · ")
                                    append(target.accountName)
                                }
                                if (selected) append("（当前）")
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(target.calendarId) }
                                .padding(vertical = 10.dp),
                            style = LeziTypography.Body,
                        )
                    }
                }
                status?.let {
                    Text(
                        it,
                        style = LeziTypography.Meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        dismissButton = {
            if (currentCalendarId != null) {
                TextButton(onClick = onDisable) { Text("关闭同步") }
            }
        },
    )
}
