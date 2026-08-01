package com.lezi.babylog.sync.session
import android.content.Context
import android.os.Build
import android.provider.Settings

/** Product default for an editable device label; never an authentication identity. */
fun defaultAndroidDeviceName(context: Context): String = resolveAndroidDeviceName(
    settingsName = runCatching {
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
    }.getOrNull(),
    model = Build.MODEL,
)

internal fun resolveAndroidDeviceName(settingsName: String?, model: String?): String =
    settingsName?.trim()?.takeIf(String::isNotEmpty)
        ?: model?.trim()?.takeIf(String::isNotEmpty)
        ?: "Android 设备"
