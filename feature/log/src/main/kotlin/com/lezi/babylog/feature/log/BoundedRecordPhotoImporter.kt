package com.lezi.babylog.feature.log

import com.lezi.babylog.core.model.MAX_RECORD_PHOTOS
import com.lezi.babylog.core.model.RecordPhotoResourcePolicy
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class RecordPhotoImportSource(
    val declaredMime: String?,
    val openStream: () -> InputStream,
)

data class RecordPhotoFileInspection(
    val actualMime: String,
    val width: Int,
    val height: Int,
)

class BoundedRecordPhotoImporter(
    private val directory: File,
    private val sniff: (File) -> RecordPhotoFileInspection?,
    private val ioContext: CoroutineContext = Dispatchers.IO,
    private val nextName: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun import(inputs: List<RecordPhotoImportSource>): List<String> =
        withContext(ioContext) {
            require(inputs.size <= MAX_RECORD_PHOTOS) { "每条记录最多只能导入 $MAX_RECORD_PHOTOS 张图片" }
            if (inputs.isEmpty()) return@withContext emptyList()
            check(directory.exists() || directory.mkdirs()) { "无法创建记录图片目录" }

            val temporaryFiles = mutableSetOf<File>()
            val completedFiles = mutableSetOf<File>()
            val importedPaths = mutableListOf<String>()
            val buffer = ByteArray(RecordPhotoResourcePolicy.streamBufferBytes)
            try {
                for (input in inputs) {
                    coroutineContext.ensureActive()
                    val declaredMime = validateDeclaredMime(input.declaredMime)
                    val temporary = File.createTempFile(".import_", ".tmp", directory)
                        .also(temporaryFiles::add)
                    input.openStream().use { source ->
                        temporary.outputStream().use { target ->
                            copyBounded(source, target, buffer)
                        }
                    }
                    coroutineContext.ensureActive()
                    val inspection = requireNotNull(sniff(temporary)) { "所选文件不是支持的图片" }
                    val actualMime = RecordPhotoResourcePolicy
                        .canonicalDeclaredMime(inspection.actualMime)
                        ?.takeIf(RecordPhotoResourcePolicy::isAllowedMime)
                        ?: throw IllegalArgumentException("所选文件不是支持的图片")
                    require(declaredMime == null || declaredMime == actualMime) {
                        "图片格式与文件类型不一致"
                    }
                    requireValidBounds(inspection)

                    val completed = File(directory, "${nextName()}.${extensionFor(actualMime)}")
                    check(!completed.exists() && temporary.renameTo(completed)) {
                        "无法保存记录图片"
                    }
                    temporaryFiles.remove(temporary)
                    completedFiles += completed
                    importedPaths += completed.absolutePath
                }
                importedPaths
            } catch (failure: Throwable) {
                temporaryFiles.forEach(File::delete)
                completedFiles.forEach(File::delete)
                throw failure
            }
        }

    private fun validateDeclaredMime(rawMime: String?): String? {
        val canonical = RecordPhotoResourcePolicy.canonicalDeclaredMime(rawMime)
        require(canonical == null || RecordPhotoResourcePolicy.isAllowedMime(canonical)) {
            "仅支持 JPEG、PNG 或 WebP 图片"
        }
        return canonical
    }

    private suspend fun copyBounded(
        source: InputStream,
        target: java.io.OutputStream,
        buffer: ByteArray,
    ) {
        var copied = 0L
        while (true) {
            coroutineContext.ensureActive()
            val count = source.read(buffer)
            if (count < 0) return
            if (count == 0) continue
            copied += count
            require(copied <= RecordPhotoResourcePolicy.maxSourceBytes) {
                "单张图片不能超过 16 MiB"
            }
            target.write(buffer, 0, count)
        }
    }

    private fun requireValidBounds(inspection: RecordPhotoFileInspection) {
        require(
            inspection.width > 0 &&
                inspection.height > 0 &&
                inspection.width <= RecordPhotoResourcePolicy.maxSourceEdge &&
                inspection.height <= RecordPhotoResourcePolicy.maxSourceEdge &&
                inspection.width.toLong() * inspection.height <=
                RecordPhotoResourcePolicy.maxSourcePixels,
        ) { "图片尺寸超出支持范围" }
    }

    private fun extensionFor(mime: String): String = when (mime) {
        RecordPhotoResourcePolicy.PNG_MIME -> "png"
        RecordPhotoResourcePolicy.WEBP_MIME -> "webp"
        else -> "jpg"
    }
}
