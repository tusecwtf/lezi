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
    ): File {
        check(exportDir.isDirectory || exportDir.mkdirs()) { "无法创建导出目录" }
        val file = File(exportDir, "lezi-$date-$fileToken.pdf")
        val document = PdfDocument()
        try {
            renderText(document, title, body)
            photoPaths
                .asSequence()
                .filter { isAllowedExportPhotoPath(context, it) }
                .forEach { path -> renderPhoto(document, path) }
            FileOutputStream(file).use(document::writeTo)
        } catch (error: Throwable) {
            file.delete()
            throw error
        } finally {
            document.close()
        }
        return file
    }

    private fun renderText(document: PdfDocument, title: String, body: String) {
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
        var canvas = page.canvas
        var y = 48f
        canvas.drawText(title, PAGE_MARGIN, y, titlePaint)
        y += 28f

        fun nextPage() {
            document.finishPage(page)
            pageNumber += 1
            page = document.startPage(pageInfo(pageNumber))
            canvas = page.canvas
            y = 48f
        }

        body.lineSequence().forEach { line ->
            if (y > PAGE_HEIGHT - 48f) nextPage()
            if (line.isEmpty()) {
                y += 16f
            } else {
                var offset = 0
                while (offset < line.length) {
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
        document.finishPage(page)
    }

    private fun renderPhoto(document: PdfDocument, path: String) {
        val bitmap = decodeSampled(path, PHOTO_DECODE_WIDTH, PHOTO_DECODE_HEIGHT) ?: return
        try {
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
        } finally {
            bitmap.recycle()
        }
    }

    private fun pageInfo(pageNumber: Int): PdfDocument.PageInfo =
        PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()

    private fun decodeSampled(path: String, targetWidth: Int, targetHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
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

    internal fun clearExportCache(dir: File) {
        dir.listFiles()?.forEach { old -> runCatching { old.delete() } }
    }

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
