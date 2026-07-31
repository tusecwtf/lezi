package com.lezi.babylog.feature.log

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class RecordPhotoStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * @param onPathCommitted see [BoundedRecordPhotoImporter.import] — used so Composer can
     *   reclaim paths if this suspending call is cancelled after files land on disk.
     */
    suspend fun import(
        uris: List<Uri>,
        onPathCommitted: ((String) -> Unit)? = null,
    ): List<String> = withContext(Dispatchers.IO) {
        val directory = File(context.filesDir, "record-media").apply { mkdirs() }
        BoundedRecordPhotoImporter(
            directory = directory,
            sniff = ::inspectImportedPhoto,
        ).import(
            inputs = uris.map { uri ->
                RecordPhotoImportSource(
                    declaredMime = context.contentResolver.getType(uri),
                    openStream = {
                        requireNotNull(context.contentResolver.openInputStream(uri)) {
                            "无法读取所选图片"
                        }
                    },
                )
            },
            onPathCommitted = onPathCommitted,
        )
    }

    suspend fun delete(paths: Collection<String>) = withContext(Dispatchers.IO) {
        val allowedRoot = File(context.filesDir, "record-media").canonicalFile
        paths.forEach { path ->
            runCatching {
                val file = File(path).canonicalFile
                if (file.parentFile == allowedRoot) file.delete()
            }
        }
    }

    private fun inspectImportedPhoto(file: File): RecordPhotoFileInspection? {
        val actualMime = sniffMime(file) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return RecordPhotoFileInspection(
            actualMime = actualMime,
            width = bounds.outWidth,
            height = bounds.outHeight,
        )
    }

    private fun sniffMime(file: File): String? {
        val header = ByteArray(12)
        val count = file.inputStream().use { it.read(header) }
        return when {
            count >= 3 &&
                header[0] == 0xff.toByte() &&
                header[1] == 0xd8.toByte() &&
                header[2] == 0xff.toByte() -> "image/jpeg"
            count >= 8 && header.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE) -> "image/png"
            count >= 12 &&
                header.copyOfRange(0, 4).contentEquals(RIFF_SIGNATURE) &&
                header.copyOfRange(8, 12).contentEquals(WEBP_SIGNATURE) -> "image/webp"
            else -> null
        }
    }

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4e,
            0x47,
            0x0d,
            0x0a,
            0x1a,
            0x0a,
        )
        val RIFF_SIGNATURE = byteArrayOf(0x52, 0x49, 0x46, 0x46)
        val WEBP_SIGNATURE = byteArrayOf(0x57, 0x45, 0x42, 0x50)
    }
}
