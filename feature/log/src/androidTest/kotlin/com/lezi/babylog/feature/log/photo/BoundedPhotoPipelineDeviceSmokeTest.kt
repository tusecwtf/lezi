package com.lezi.babylog.feature.log.photo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lezi.babylog.core.model.RecordPhotoResourcePolicy
import com.lezi.babylog.sync.media.AndroidSyncMediaFileStore
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.timeline.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.composer.*
import com.lezi.babylog.feature.log.layout.*

@RunWith(AndroidJUnit4::class)
class BoundedPhotoPipelineDeviceSmokeTest {
    @Test
    fun threeNearLimitJpegsImportNormalizeStreamAndCleanUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val runId = UUID.randomUUID().toString()
        val sources = File(context.cacheDir, "photo-pipeline-sources-$runId").apply {
            check(mkdirs())
        }
        val imports = File(context.filesDir, "photo-pipeline-imports-$runId").apply {
            check(mkdirs())
        }
        try {
            val nearLimitBytes = RecordPhotoResourcePolicy.maxSourceBytes - 1_024
            val sourceFiles = (1..3).map { index ->
                createPaddedJpeg(File(sources, "near-limit-$index.jpg"), nearLimitBytes)
            }
            val importer = BoundedRecordPhotoImporter(
                directory = imports,
                sniff = { file ->
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.path, bounds)
                    bounds.outWidth.takeIf { it > 0 }?.let { width ->
                        RecordPhotoFileInspection(
                            actualMime = bounds.outMimeType ?: "image/jpeg",
                            width = width,
                            height = bounds.outHeight,
                        )
                    }
                },
            )

            val imported = importer.import(
                sourceFiles.map { source ->
                    RecordPhotoImportSource("image/jpeg", source::inputStream)
                },
            )

            assertEquals(3, imported.size)
            assertTrue(imported.all { File(it).length() == nearLimitBytes })
            val mediaStore = AndroidSyncMediaFileStore(context)
            imported.forEach { path ->
                val prepared = mediaStore.prepareUpload(path)
                val normalizedFile = prepared.file
                try {
                    assertTrue(prepared.contentLength in 1L..RecordPhotoResourcePolicy.maxUploadBytes)
                    assertTrue(requireNotNull(prepared.width) <= RecordPhotoResourcePolicy.maxUploadEdge)
                    assertTrue(requireNotNull(prepared.height) <= RecordPhotoResourcePolicy.maxUploadEdge)
                    assertTrue(
                        prepared.width!!.toLong() * prepared.height!! <=
                            RecordPhotoResourcePolicy.maxUploadPixels,
                    )
                    val buffer = ByteArray(RecordPhotoResourcePolicy.streamBufferBytes)
                    var streamed = 0L
                    prepared.openStream().use { input ->
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            streamed += count
                        }
                    }
                    assertEquals(prepared.contentLength, streamed)
                } finally {
                    prepared.close()
                }
                assertFalse(normalizedFile.exists())
            }
        } finally {
            sources.deleteRecursively()
            imports.deleteRecursively()
        }
    }

    private fun createPaddedJpeg(file: File, byteSize: Long): File {
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(42, 116, 181))
        }
        try {
            file.outputStream().buffered().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
            }
        } finally {
            bitmap.recycle()
        }
        check(file.length() < byteSize)
        RandomAccessFile(file, "rw").use { it.setLength(byteSize) }
        return file
    }
}
