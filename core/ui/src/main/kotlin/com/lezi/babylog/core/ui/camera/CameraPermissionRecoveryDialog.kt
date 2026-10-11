package com.lezi.babylog.core.ui.camera

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.DialogProperties
import com.lezi.babylog.core.ui.CameraCaptureLauncher
import com.lezi.babylog.designsystem.LeziAlertDialog
import com.lezi.babylog.designsystem.LeziTextButton
import com.lezi.babylog.designsystem.LeziTextButtonTone
import com.lezi.babylog.designsystem.LeziTypography

/**
 * The feature retains its draft and supplies its existing Photo Picker action. Settings never
 * launch a capture on return: the user closes this recovery and explicitly taps 拍照 again.
 */
@Composable
fun CameraPermissionRecoveryDialog(
    launcher: CameraCaptureLauncher,
    enabled: Boolean = true,
    onChooseGallery: () -> Unit,
) {
    val recovery = launcher.permissionRecovery ?: return
    val context = LocalContext.current
    var settingsError by remember(recovery) { mutableStateOf(false) }
    CameraPermissionRecoveryDialogContent(
        recovery = recovery,
        enabled = enabled,
        settingsError = settingsError,
        onRetry = { launcher.launch() },
        onSettings = {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) {
                launcher.dismissPermissionRecovery()
            } else {
                settingsError = true
            }
        },
        onChooseGallery = {
            launcher.dismissPermissionRecovery()
            onChooseGallery()
        },
        onDismiss = launcher::dismissPermissionRecovery,
    )
}

/** Small UI seam: permission/file policy stays with the capture launcher. */
@Composable
internal fun CameraPermissionRecoveryDialogContent(
    recovery: CameraPermissionRecovery,
    enabled: Boolean,
    settingsError: Boolean,
    onRetry: () -> Unit,
    onSettings: () -> Unit,
    onChooseGallery: () -> Unit,
    onDismiss: () -> Unit,
) {
    LeziAlertDialog(
        onDismissRequest = { if (enabled) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = enabled,
            dismissOnClickOutside = enabled,
        ),
        title = { Text("拍照需要相机权限", style = LeziTypography.TitleSm) },
        text = {
            Column {
                Text(
                    if (recovery == CameraPermissionRecovery.OpenSettings) {
                        "相机权限未开启，可在系统设置中允许。返回后请再次点“拍照”；当前草稿会保留，也可从相册选择。"
                    } else {
                        "允许相机权限后才能拍照。可以再次申请，也可从相册选择；当前草稿会保留。"
                    },
                    style = LeziTypography.Body,
                )
                if (settingsError) {
                    Text(
                        "系统设置暂时打不开，可稍后重试或从相册选择",
                        style = LeziTypography.Meta,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                LeziTextButton(
                    label = if (recovery == CameraPermissionRecovery.OpenSettings) {
                        "打开设置"
                    } else {
                        "再次申请"
                    },
                    onClick = if (recovery == CameraPermissionRecovery.OpenSettings) {
                        onSettings
                    } else {
                        onRetry
                    },
                    enabled = enabled,
                    tone = LeziTextButtonTone.Primary,
                )
                LeziTextButton(
                    label = "从相册选择",
                    onClick = onChooseGallery,
                    enabled = enabled,
                )
                LeziTextButton(label = "暂不拍照", onClick = onDismiss, enabled = enabled)
            }
        },
    )
}
