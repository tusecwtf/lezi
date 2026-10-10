package com.lezi.babylog.feature.export

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File

/** Checked before the Application starts any database, gate, sync or reminder work. */
fun isExportRendererProcess(context: Context): Boolean {
    return currentExportProcessName() == context.packageName + ":export_renderer"
}

internal fun currentExportProcessName(): String = if (Build.VERSION.SDK_INT >= 28) {
    Application.getProcessName()
} else {
    File("/proc/self/cmdline").inputStream().use { stream ->
        buildString {
            while (true) {
                val byte = stream.read()
                if (byte <= 0) break
                append(byte.toChar())
            }
        }
    }
}
