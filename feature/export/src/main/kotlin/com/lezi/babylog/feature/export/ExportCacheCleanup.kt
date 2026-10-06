package com.lezi.babylog.feature.export

import android.content.Context
import java.io.File

/** Removes abandoned private export artifacts without racing the Android Sharesheet. */
object ExportCacheCleanup {
    internal const val STALE_EXPORT_AGE_MS = 24L * 60L * 60L * 1_000L

    /**
     * Clean only aged artifacts. Opening a chooser is not proof that its target has consumed the
     * URI, so a live share must never start a fixed deletion timer.
     */
    fun cleanupStale(
        context: Context,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        val stale = staleFiles(
            files = PdfExport.exportCacheDir(context.applicationContext)
                .listFiles()
                .orEmpty()
                .toList(),
            nowMillis = nowMillis,
        )
        deleteFiles(stale)
    }

    internal fun staleFiles(
        files: List<File>,
        nowMillis: Long,
        maxAgeMillis: Long = STALE_EXPORT_AGE_MS,
    ): List<File> {
        val cutoff = nowMillis - maxAgeMillis
        return files.filter { file ->
            file.isFile && file.lastModified() in 1..cutoff
        }
    }

    internal fun deleteFiles(files: List<File>) {
        files.forEach { file -> runCatching { if (file.isFile) file.delete() } }
    }
}
