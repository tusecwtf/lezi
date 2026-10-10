package com.lezi.babylog.feature.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.util.UUID

internal object PdfExport {
    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val PAGE_MARGIN = 40f
    private const val PHOTO_DECODE_WIDTH = 1_190
    private const val PHOTO_DECODE_HEIGHT = 1_484

    /** Generates a cache PDF. The document and every decoded bitmap close on all paths. */
    fun writePdf(
        context: Context,
        title: String,
        body: String,
        photoPaths: List<String>,
        exportDir: File,
        date: LocalDate = LocalDate.now(),
        fileToken: String = UUID.randomUUID().toString(),
        checkpoint: () -> Unit = {},
    ): File {
        checkpoint()
        check(exportDir.isDirectory || exportDir.mkdirs()) { "无法创建导出目录" }
        val file = File(exportDir, "lezi-$date-$fileToken.pdf")
        try {
            FileOutputStream(file).use { output ->
                writeTo(context, title, body, photoPaths, output, checkpoint)
            }
            checkpoint()
            return file
        } catch (failure: Throwable) {
            file.delete()
            throw failure
        }
    }

    /** Called in the private renderer process; native calls can be aborted by process death. */
    fun writeTo(
        context: Context,
        title: String,
        body: String,
        photoPaths: List<String>,
        output: java.io.OutputStream,
        checkpoint: () -> Unit = {},
    ) {
        checkpoint()
        val document = PdfDocument()
        recordExportMilestone(ExportMilestone.PDF_DOCUMENT_CREATED)
        try {
            recordExportMilestone(ExportMilestone.PDF_TEXT_STARTED)
            renderText(document, title, body, checkpoint)
            recordExportMilestone(ExportMilestone.PDF_TEXT_FINISHED)
            photoPaths.forEach { path ->
                checkpoint()
                if (isAllowedExportPhotoPath(context, path)) renderPhoto(document, path, checkpoint)
            }
            checkpoint()
            recordExportMilestone(ExportMilestone.PDF_WRITE_STARTED)
            document.writeTo(object : java.io.OutputStream() {
                override fun write(value: Int) {
                    checkpoint()
                    output.write(value)
                }
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    checkpoint()
                    output.write(bytes, offset, length)
                }
            })
            recordExportMilestone(ExportMilestone.PDF_WRITE_FINISHED)
            checkpoint()
        } finally {
            document.close()
        }
    }

    private fun renderText(document: PdfDocument, title: String, body: String, checkpoint: () -> Unit) {
        val paint = Paint().apply {
            textSize = 11f
            isAntiAlias = true
        }
        val titlePaint = Paint().apply {
            textSize = 16f
            isFakeBoldText = true
            isAntiAlias = true
        }
        var pageNumber = 1
        var page = document.startPage(pageInfo(pageNumber))
        var pageOpen = true
        var canvas = page.canvas
        var y = 48f
        fun nextPage() {
            document.finishPage(page)
            pageOpen = false
            checkpoint()
            pageNumber += 1
            page = document.startPage(pageInfo(pageNumber))
            pageOpen = true
            canvas = page.canvas
            y = 48f
        }

        try {
            canvas.drawText(title, PAGE_MARGIN, y, titlePaint)
            y += 28f
            body.lineSequence().forEach { line ->
                checkpoint()
                if (y > PAGE_HEIGHT - 48f) nextPage()
                if (line.isEmpty()) {
                    y += 16f
                } else {
                    var offset = 0
                    while (offset < line.length) {
                        checkpoint()
                        val count = paint.breakText(
                            line,
                            offset,
                            line.length,
                            true,
                            PAGE_WIDTH - PAGE_MARGIN * 2,
                            null,
                        ).coerceAtLeast(1)
                        canvas.drawText(line, offset, offset + count, PAGE_MARGIN, y, paint)
                        offset += count
                        y += 16f
                        if (y > PAGE_HEIGHT - 48f && offset < line.length) nextPage()
                    }
                }
            }
        } finally {
            if (pageOpen) document.finishPage(page)
        }
    }

    private fun renderPhoto(document: PdfDocument, path: String, checkpoint: () -> Unit) {
        checkpoint()
        recordExportMilestone(ExportMilestone.PHOTO_DECODE_STARTED)
        val bitmap = decodeSampled(path, PHOTO_DECODE_WIDTH, PHOTO_DECODE_HEIGHT, checkpoint)
        recordExportMilestone(ExportMilestone.PHOTO_DECODE_FINISHED)
        if (bitmap == null) return
        try {
            checkpoint()
            recordExportMilestone(ExportMilestone.PDF_PHOTO_STARTED)
            val pageNumber = document.pages.size + 1
            val page = document.startPage(pageInfo(pageNumber))
            try {
                val paint = Paint().apply { isAntiAlias = true }
                val titlePaint = Paint().apply {
                    textSize = 16f
                    isFakeBoldText = true
                    isAntiAlias = true
                }
                page.canvas.drawText("记录图片", PAGE_MARGIN, 42f, titlePaint)
                val availableWidth = PAGE_WIDTH - PAGE_MARGIN * 2
                val availableHeight = PAGE_HEIGHT - 100f
                val scale = minOf(
                    availableWidth / bitmap.width,
                    availableHeight / bitmap.height,
                )
                val width = bitmap.width * scale
                val height = bitmap.height * scale
                page.canvas.drawBitmap(
                    bitmap,
                    null,
                    RectF(PAGE_MARGIN, 60f, PAGE_MARGIN + width, 60f + height),
                    paint,
                )
            } finally {
                document.finishPage(page)
            }
            recordExportMilestone(ExportMilestone.PDF_PHOTO_FINISHED)
        } finally {
            bitmap.recycle()
        }
    }

    private fun pageInfo(pageNumber: Int): PdfDocument.PageInfo =
        PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()

    private fun decodeSampled(path: String, targetWidth: Int, targetHeight: Int, checkpoint: () -> Unit): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        checkpoint()
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(
                    bounds.outWidth,
                    bounds.outHeight,
                    targetWidth,
                    targetHeight,
                )
            },
        )
    }

    /** Power-of-two sample that caps decoded dimensions near twice the rendered resolution. */
    internal fun sampleSizeFor(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): Int {
        if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
            return 1
        }
        var sample = 1
        while (
            sourceWidth / sample > targetWidth * 2 ||
            sourceHeight / sample > targetHeight * 2
        ) {
            sample *= 2
        }
        return sample
    }

    internal fun exportCacheDir(context: Context): File =
        File(context.cacheDir, "export").apply { mkdirs() }

    /** Allows only app-private record media, never arbitrary readable filesystem paths. */
    internal fun isAllowedExportPhotoPath(context: Context, path: String): Boolean {
        if (path.isBlank()) return false
        return runCatching {
            val allowedRoot = File(context.filesDir, "record-media").canonicalFile
            isUnderCanonicalRoot(allowedRoot, File(path))
        }.getOrDefault(false)
    }

    internal fun isUnderCanonicalRoot(allowedRoot: File, candidate: File): Boolean {
        val root = allowedRoot.canonicalFile
        val file = candidate.canonicalFile
        return file.path == root.path || file.path.startsWith(root.path + File.separator)
    }
}
