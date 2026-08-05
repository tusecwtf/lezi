package com.lezi.gf.app.export

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.lezi.gf.care.CareService
import java.io.File

/**
 * PDF ebook export for share sheet (PRD §4.8 / E-02).
 *
 * Lifecycle (must not break share):
 * 1. [createPdfFile] writes under cacheDir — file **must remain** while the system share target reads it.
 * 2. [shareIntent] builds ACTION_SEND with FileProvider URI — does **not** delete.
 * 3. Caller launches the chooser; only after share returns call [discardAfterShare].
 * 4. [purgeStaleExports] may delete only exports older than [STALE_MAX_AGE_MS] (never the fresh file).
 */
class PdfShareExport(
    private val context: Context,
) {
    fun exportDir(): File = File(context.cacheDir, EXPORT_DIR_NAME).also { it.mkdirs() }

    /**
     * Build PDF from export lines (Chinese type labels via [CareService.exportLines]).
     * Uses Android [PdfDocument] so CJK text paints with system fonts.
     */
    fun createPdfFile(
        care: CareService,
        babyClientUuid: String,
        title: String = "乐记导出",
    ): File {
        val lines = care.exportLines(babyClientUuid)
        val doc = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842
        val margin = 40f
        val lineHeight = 16f
        val paint = Paint().apply {
            isAntiAlias = true
            textSize = 11f
            color = android.graphics.Color.BLACK
        }
        val titlePaint = Paint(paint).apply {
            textSize = 16f
            isFakeBoldText = true
        }

        var pageNum = 1
        var y = margin + 24f
        var page = doc.startPage(
            PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create(),
        )
        var canvas = page.canvas
        canvas.drawText(title, margin, y, titlePaint)
        y += lineHeight * 2

        for (line in lines) {
            if (y > pageHeight - margin) {
                doc.finishPage(page)
                pageNum++
                page = doc.startPage(
                    PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create(),
                )
                canvas = page.canvas
                y = margin
            }
            val maxWidth = pageWidth - margin * 2
            var remaining = line
            while (remaining.isNotEmpty()) {
                val count = paint.breakText(remaining, true, maxWidth, null)
                val chunk = remaining.substring(0, count.coerceAtLeast(1).coerceAtMost(remaining.length))
                canvas.drawText(chunk, margin, y, paint)
                y += lineHeight
                remaining = remaining.substring(chunk.length)
                if (y > pageHeight - margin && remaining.isNotEmpty()) {
                    doc.finishPage(page)
                    pageNum++
                    page = doc.startPage(
                        PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create(),
                    )
                    canvas = page.canvas
                    y = margin
                }
            }
        }
        doc.finishPage(page)

        val file = File(exportDir(), "lezi-export-${System.currentTimeMillis()}.pdf")
        file.outputStream().use { out -> doc.writeTo(out) }
        doc.close()
        return file
    }

    /** Share intent — does not delete [file]. */
    fun shareIntent(file: File): Intent {
        require(file.exists()) { "export file missing before share: ${file.absolutePath}" }
        val uri = try {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
        } catch (_: Exception) {
            return Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "乐记 PDF: ${file.name}")
            }
        }
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Call only **after** the share activity returns.
     * Deletes this export and purges other stale cache PDFs.
     */
    fun discardAfterShare(file: File) {
        if (file.exists()) {
            file.delete()
        }
        purgeStaleExports()
    }

    /** Delete only exports older than [maxAgeMs]; never touches a just-created file. */
    fun purgeStaleExports(
        maxAgeMs: Long = STALE_MAX_AGE_MS,
        nowMs: Long = System.currentTimeMillis(),
    ): List<File> = purgeStaleIn(exportDir(), nowMs = nowMs, maxAgeMs = maxAgeMs)

    companion object {
        const val EXPORT_DIR_NAME: String = "lezi-export"
        const val STALE_MAX_AGE_MS: Long = 60_000L

        fun pdfBodyLines(care: CareService, babyClientUuid: String): List<String> =
            care.exportLines(babyClientUuid)

        /**
         * Pure filesystem policy for tests and [purgeStaleExports].
         * Returns files that were deleted.
         */
        fun purgeStaleIn(
            dir: File,
            nowMs: Long = System.currentTimeMillis(),
            maxAgeMs: Long = STALE_MAX_AGE_MS,
        ): List<File> {
            if (!dir.isDirectory) return emptyList()
            val deleted = mutableListOf<File>()
            dir.listFiles()?.forEach { f ->
                if (f.isFile &&
                    f.name.startsWith("lezi-export-") &&
                    f.extension == "pdf" &&
                    nowMs - f.lastModified() >= maxAgeMs
                ) {
                    if (f.delete()) deleted.add(f)
                }
            }
            return deleted
        }

        /** Policy helper: fresh export must still exist when building share intent. */
        fun assertExistsForShare(file: File) {
            check(file.exists() && file.length() >= 0) {
                "PDF must exist before share; got exists=${file.exists()}"
            }
        }
    }
}
