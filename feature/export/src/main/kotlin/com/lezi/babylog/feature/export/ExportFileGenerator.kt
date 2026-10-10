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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
class ExportFileGenerator internal constructor(
    private val context: Context,
    private val renderer: AndroidExportRenderer,
) {
    @Inject constructor(@ApplicationContext context: Context) : this(context, AndroidExportRenderer(context))

    internal suspend fun prepare(
        format: ExportFormat,
        title: String,
        document: ExportDocument,
        includePhotos: Boolean,
    ): PreparedExport {
        var ownedFile: File? = null
        try {
            return withContext(Dispatchers.IO) {
                val jobContext = currentCoroutineContext()
                val checkpoint = { jobContext.ensureActive() }
                checkpoint()
                ExportCacheCleanup.cleanupStale(context)
                val exportDir = PdfExport.exportCacheDir(context)
                val token = ExportCacheCleanup.newAttemptToken()
                val extension = if (format == ExportFormat.Pdf) "pdf" else "txt"
                val file = File(exportDir, "lezi-${LocalDate.now()}-$token.$extension")
                val staging = File(exportDir, "$token.partial")
                ownedFile = staging
                val request = ExportRenderRequest(
                    format, title, document.text,
                    if (includePhotos && format == ExportFormat.Pdf) document.photoPaths else emptyList(),
                )
                checkpoint()
                check(staging.createNewFile()) { "无法创建导出文件" }
                renderer.render(request, staging)
                checkpoint()
                check(staging.renameTo(file)) { "无法完成导出文件" }
                ownedFile = file
                checkpoint()
                PreparedExport(
                    uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file),
                    mimeType = format.mimeType,
                    chooserTitle = format.chooserTitle,
                    file = file,
                )
            }
        } catch (failure: Throwable) {
            ownedFile?.delete()
            throw failure
        }
    }

    companion object {
        internal fun writeTxtTo(output: java.io.OutputStream, body: String, checkpoint: () -> Unit) {
            output.writer(Charsets.UTF_8).use { writer ->
                var offset = 0
                while (offset < body.length) {
                    checkpoint()
                    val count = minOf(8_192, body.length - offset)
                    writer.write(body, offset, count)
                    offset += count
                }
            }
            checkpoint()
        }

        internal fun writeTxtFile(
            exportDir: File,
            body: String,
            date: LocalDate = LocalDate.now(),
            fileToken: String = UUID.randomUUID().toString(),
            checkpoint: () -> Unit = {},
        ): File {
            checkpoint()
            check(exportDir.isDirectory || exportDir.mkdirs()) { "无法创建导出目录" }
            val file = File(exportDir, "lezi-$date-$fileToken.txt")
            try {
                file.outputStream().use { writeTxtTo(it, body, checkpoint) }
                checkpoint()
                return file
            } catch (failure: Throwable) {
                file.delete()
                throw failure
            }
        }
    }
}
