package com.lezi.babylog.feature.export

import android.content.Context
import java.io.File
import java.util.UUID

/** Removes abandoned private export artifacts without racing the Android Sharesheet. */
object ExportCacheCleanup {
    private val processSession = UUID.randomUUID().toString()

    internal fun newAttemptToken(): String = "$processSession-${UUID.randomUUID()}"

    internal const val STALE_EXPORT_AGE_MS = 24L * 60L * 60L * 1_000L

    /**
     * Completed shares are age-only: opening a chooser does not prove the URI was consumed.
     * Staging from a previous app process can be reclaimed immediately. Current-session staging
     * is protected even if startup cleanup overlaps the first export or another screen's request.
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
            val staging = file.extension == "partial" || file.extension == "request"
            val abandoned = staging && !file.name.startsWith("$processSession-")
            file.isFile && (abandoned || file.lastModified() in 1..cutoff)
        }
    }

    internal fun deleteFiles(files: List<File>) {
        files.forEach { file -> runCatching { if (file.isFile) file.delete() } }
    }
}
