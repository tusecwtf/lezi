package com.lezi.babylog.feature.log

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class RecordPhotoStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun import(uris: List<Uri>): List<String> = withContext(Dispatchers.IO) {
        val directory = File(context.filesDir, "record-media").apply { mkdirs() }
        uris.map { uri ->
            val mime = context.contentResolver.getType(uri).orEmpty()
            val extension = when (mime) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                else -> "jpg"
            }
            val target = File(directory, "${UUID.randomUUID()}.$extension")
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "无法读取所选图片" }
                target.outputStream().use(input::copyTo)
            }
            target.absolutePath
        }
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
}
