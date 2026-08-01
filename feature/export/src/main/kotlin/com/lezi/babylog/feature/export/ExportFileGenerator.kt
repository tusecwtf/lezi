package com.lezi.babylog.feature.export

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.lezi.babylog.domain.export.ExportDocument
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class ExportFormat(
    val mimeType: String,
    val chooserTitle: String,
) {
    Txt("text/plain", "分享 TXT"),
    Pdf("application/pdf", "分享 PDF"),
}

internal data class PreparedExport(
    val uri: Uri,
    val mimeType: String,
    val chooserTitle: String,
    val file: File,
)

/** Owns cache-file generation and FileProvider exposure for every export format. */
@Singleton
class ExportFileGenerator @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    internal suspend fun prepare(
        format: ExportFormat,
        title: String,
        document: ExportDocument,
        includePhotos: Boolean,
    ): PreparedExport = withContext(Dispatchers.IO) {
        ExportCacheCleanup.cleanupStale(context)
        val exportDir = PdfExport.exportCacheDir(context)
        val fileToken = UUID.randomUUID().toString()
        val file = when (format) {
            ExportFormat.Txt -> writeTxtFile(exportDir, document.text, fileToken = fileToken)
            ExportFormat.Pdf -> PdfExport.writePdf(
                context = context,
                title = title,
                body = document.text,
                photoPaths = if (includePhotos) document.photoPaths else emptyList(),
                exportDir = exportDir,
                fileToken = fileToken,
            )
        }
        PreparedExport(
            uri = FileProvider.getUriForFile(
                context,
                context.packageName + ".fileprovider",
                file,
            ),
            mimeType = format.mimeType,
            chooserTitle = format.chooserTitle,
            file = file,
        )
    }

    internal fun discard(prepared: PreparedExport) {
        ExportCacheCleanup.discard(prepared)
    }

    companion object {
        internal fun writeTxtFile(
            exportDir: File,
            body: String,
            date: LocalDate = LocalDate.now(),
            fileToken: String = UUID.randomUUID().toString(),
        ): File {
            check(exportDir.isDirectory || exportDir.mkdirs()) { "无法创建导出目录" }
            return File(exportDir, "lezi-$date-$fileToken.txt").apply {
                writeText(body, Charsets.UTF_8)
            }
        }
    }
}
