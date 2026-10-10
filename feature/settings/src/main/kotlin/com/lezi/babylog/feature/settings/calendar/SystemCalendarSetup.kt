package com.lezi.babylog.feature.settings.calendar

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import com.lezi.babylog.designsystem.LeziAlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import com.lezi.babylog.core.common.productUiError
import kotlinx.coroutines.CancellationException
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.LeziTextButton
import com.lezi.babylog.domain.calendar.SystemCalendarDisclosureLevel
import com.lezi.babylog.domain.calendar.SystemCalendarPort
import com.lezi.babylog.domain.calendar.SystemCalendarTarget
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
        "标题显示「宝宝昵称 · 记录类型」。"
    SystemCalendarDisclosureLevel.DETAILS ->
        "增加备注、照片数量和返回乐记的链接；照片本身不会写入日历。"
}

data class SystemCalendarSetupSelection(
    val calendarId: String,
    val disclosureLevel: Int,
)

/** Dialog-local draft. Nothing is persisted until [confirmedOrNull] is submitted. */
internal data class SystemCalendarSetupDraft(
    val selectedCalendarId: String?,
    val disclosureLevel: Int,
) {
    fun selectCalendar(calendarId: String): SystemCalendarSetupDraft =
        copy(selectedCalendarId = calendarId.trim().takeIf(String::isNotEmpty))

    fun selectDisclosureLevel(level: Int): SystemCalendarSetupDraft =
        copy(disclosureLevel = level.coerceIn(1, 3))

    fun reconcileWritableCalendars(calendarIds: Set<String>): SystemCalendarSetupDraft =
        if (selectedCalendarId in calendarIds) this else copy(selectedCalendarId = null)

    fun confirmedOrNull(): SystemCalendarSetupSelection? = selectedCalendarId
        ?.takeIf(String::isNotBlank)
        ?.let { SystemCalendarSetupSelection(it, disclosureLevel.coerceIn(1, 3)) }

    companion object {
        fun from(
            currentCalendarId: String?,
            currentDisclosureLevel: Int,
        ): SystemCalendarSetupDraft = SystemCalendarSetupDraft(
            selectedCalendarId = currentCalendarId?.trim()?.takeIf(String::isNotEmpty),
            disclosureLevel = currentDisclosureLevel.coerceIn(1, 3),
        )
    }
}

internal fun systemCalendarTargetSummary(
    calendarId: String?,
    hasPermission: Boolean,
    targets: List<SystemCalendarTarget>,
): String {
    val normalizedId = calendarId?.trim()?.takeIf(String::isNotEmpty) ?: return "未配置"
    if (!hasPermission) return "需要日历权限以确认目标"
    val target = targets.firstOrNull { it.calendarId == normalizedId }
        ?: return "原日历不可用，请重新选择"
    return buildString {
        append(target.displayName)
        if (target.accountName.isNotBlank()) {
            append(" · ")
            append(target.accountName)
        }
    }
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
    onConfirm: (SystemCalendarSetupSelection) -> Unit,
    onDisable: () -> Unit,
    onDismiss: () -> Unit,
    commandState: SystemCalendarSetupCommandState = SystemCalendarSetupCommandState(),
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
    var targetsLoaded by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var draft by rememberSaveable(stateSaver = listSaver(
        save = { listOf(it.selectedCalendarId.orEmpty(), it.disclosureLevel.toString()) },
        restore = { SystemCalendarSetupDraft.from(it[0].ifBlank { null }, it[1].toInt()) },
    )) {
        val pending = commandState.selection
        mutableStateOf(SystemCalendarSetupDraft.from(
            pending?.calendarId ?: currentCalendarId,
            pending?.disclosureLevel ?: currentDisclosureLevel,
        ))
    }
    LaunchedEffect(commandState.completed) {
        if (commandState.completed) onDismiss()
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
            try {
                targets = port.listWritableCalendars()
                targetsLoaded = true
                draft = draft.reconcileWritableCalendars(
                    targets.mapTo(linkedSetOf(), SystemCalendarTarget::calendarId),
                )
                status = when {
                    targets.isEmpty() -> "未找到可写日历"
                    currentCalendarId != null && draft.selectedCalendarId == null ->
                        "原日历不可用，请重新选择"
                    else -> null
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                targetsLoaded = true
                status = productUiError(error, "暂时无法读取日历，请重试")
            }
        }
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) refreshTargets()
    }

    LeziAlertDialog(
        onDismissRequest = { if (!commandState.busy) onDismiss() },
        title = { Text("系统日历") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "仅本机有效，不随家庭同步。",
                    style = LeziTypography.Meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "披露级别",
                    style = LeziTypography.Body,
                    modifier = Modifier.padding(top = LeziSpacing.Sm),
                )
                listOf(1, 2, 3).forEach { level ->
                    val selected = draft.disclosureLevel == level
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = LeziSpacing.Touch)
                            .selectable(
                                selected = selected,
                                enabled = !commandState.busy,
                                role = Role.RadioButton,
                                onClick = {
                                    draft = draft.selectDisclosureLevel(level)
                                },
                            )
                            .padding(vertical = LeziSpacing.Xxs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Column(Modifier.weight(1f)) {
                            Text(
                                buildString {
                                    append(systemCalendarDisclosureLabel(level))
                                    if (level == 2) append("（默认）")
                                },
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
                            )
                        }
                    }
                }
                Text(
                    "目标日历",
                    style = LeziTypography.Body,
                    modifier = Modifier.padding(top = LeziSpacing.Sm),
                )
                if (!hasPermission) {
                    LeziTextButton(label = "授予日历权限", onClick = {
                            permissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.READ_CALENDAR,
                                    Manifest.permission.WRITE_CALENDAR,
                                ),
                            )
                        })
                } else if (!targetsLoaded) {
                    Text(
                        "正在加载可写日历…",
                        style = LeziTypography.Meta,
                        modifier = Modifier.padding(top = LeziSpacing.Xs),
                    )
                } else if (targets.isEmpty()) {
                    Text(status ?: "未找到可写日历", style = LeziTypography.Meta)
                } else {
                    targets.forEach { target ->
                        val selected = target.calendarId == draft.selectedCalendarId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = LeziSpacing.Touch)
                                .selectable(
                                    selected = selected,
                                    enabled = !commandState.busy,
                                    role = Role.RadioButton,
                                    onClick = {
                                        draft = draft.selectCalendar(target.calendarId)
                                        status = null
                                    },
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected, onClick = null)
                            Column(Modifier.weight(1f)) {
                                Text(target.displayName, style = LeziTypography.Body)
                                if (target.accountName.isNotBlank()) {
                                    Text(
                                        target.accountName,
                                        style = LeziTypography.Meta,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                if (hasPermission && targetsLoaded) {
                    LeziTextButton(label = "重新读取日历", onClick = ::refreshTargets, enabled = !commandState.busy)
                }
                commandState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                status?.let {
                    Text(
                        it,
                        style = LeziTypography.Meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = LeziSpacing.Xs),
                    )
                }
            }
        },
        confirmButton = {
            val selection = draft.confirmedOrNull()
            LeziTextButton(label = if (commandState.busy) "保存中…" else "保存",
                onClick = { selection?.let(onConfirm) },
                enabled = selection != null && hasPermission && targetsLoaded &&
                    targets.any { it.calendarId == selection.calendarId } && !commandState.busy)
        },
        dismissButton = {
            Row {
                if (currentCalendarId != null) {
                    LeziTextButton(label = "关闭同步", onClick = onDisable, enabled = !commandState.busy)
                }
                LeziTextButton(label = "取消", onClick = onDismiss, enabled = !commandState.busy)
            }
        },
    )
}
