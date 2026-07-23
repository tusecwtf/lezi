package com.lezi.babylog.feature.export

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate

object PdfExport {
    fun writeAndShare(context: Context, title: String, body: String) {
        val doc = PdfDocument()
        val paint = Paint().apply {
            textSize = 11f
            isAntiAlias = true
        }
        val titlePaint = Paint().apply {
            textSize = 16f
            isFakeBoldText = true
            isAntiAlias = true
        }
        val pageWidth = 595
        val pageHeight = 842
        var pageNumber = 1
        var pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
        var page = doc.startPage(pageInfo)
        var canvas = page.canvas
        var y = 48f
        canvas.drawText(title, 40f, y, titlePaint)
        y += 28f
        val lines = body.split('\n')
        for (line in lines) {
            if (y > pageHeight - 48f) {
                doc.finishPage(page)
                pageNumber++
                pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                page = doc.startPage(pageInfo)
                canvas = page.canvas
                y = 48f
            }
            // crude wrap
            var rest = line
            while (rest.isNotEmpty()) {
                val count = paint.breakText(rest, true, pageWidth - 80f, null)
                canvas.drawText(rest.substring(0, count), 40f, y, paint)
                rest = rest.substring(count)
                y += 16f
                if (y > pageHeight - 48f && rest.isNotEmpty()) {
                    doc.finishPage(page)
                    pageNumber++
                    pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                    page = doc.startPage(pageInfo)
                    canvas = page.canvas
                    y = 48f
                }
            }
        }
        doc.finishPage(page)
        val dir = File(context.cacheDir, "export").apply { mkdirs() }
        val file = File(dir, "lezi-${LocalDate.now()}.pdf")
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "分享 PDF"))
    }
}
