package com.lezi.babylog.sync

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LocalMediaInfo(
    val byteSize: Long,
    val mime: String,
    val width: Int?,
    val height: Int?,
)

data class PreparedMedia(
    val bytes: ByteArray,
    val mime: String,
    val width: Int? = null,
    val height: Int? = null,
)

interface SyncMediaFileStore {
    suspend fun inspect(localUri: String): LocalMediaInfo?
    suspend fun prepareUpload(localUri: String): PreparedMedia
    suspend fun saveDownloaded(
        clientUuid: String,
        kind: String,
        bytes: ByteArray,
        mime: String?,
    ): String
    suspend fun delete(localUri: String)
}

@Singleton
class AndroidSyncMediaFileStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : SyncMediaFileStore {
    override suspend fun inspect(localUri: String): LocalMediaInfo? = withContext(Dispatchers.IO) {
        val file = resolve(localUri)?.takeIf(File::isFile) ?: return@withContext null
        val bounds = BitmapFactory.Options().also { it.inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        LocalMediaInfo(
            byteSize = file.length(),
            mime = bounds.outMimeType ?: mimeFromName(file.name),
            width = bounds.outWidth.takeIf { it > 0 },
            height = bounds.outHeight.takeIf { it > 0 },
        )
    }

    override suspend fun prepareUpload(localUri: String): PreparedMedia = withContext(Dispatchers.IO) {
        val file = requireNotNull(resolve(localUri)?.takeIf(File::isFile)) {
            "本地媒体文件不存在"
        }
        val bounds = BitmapFactory.Options().also { it.inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val largest = max(bounds.outWidth, bounds.outHeight)
        if (largest <= 0) {
            return@withContext PreparedMedia(file.readBytes(), mimeFromName(file.name))
        }
        var sample = 1
        while (largest / sample > MEDIA_DECODE_MAX_EDGE * 2) sample *= 2
        val source = requireNotNull(
            BitmapFactory.decodeFile(
                file.path,
                BitmapFactory.Options().apply { inSampleSize = sample },
            ),
        ) { "无法读取本地媒体" }
        val oriented = applyExifOrientation(source, readExifOrientation(file))
        val scaled = if (max(oriented.width, oriented.height) > MEDIA_UPLOAD_MAX_EDGE) {
            val ratio = MEDIA_UPLOAD_MAX_EDGE.toDouble() / max(oriented.width, oriented.height)
            Bitmap.createScaledBitmap(
                oriented,
                (oriented.width * ratio).toInt().coerceAtLeast(1),
                (oriented.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            oriented
        }
        try {
            val bytes = ByteArrayOutputStream().use { output ->
                check(scaled.compress(Bitmap.CompressFormat.JPEG, MEDIA_JPEG_QUALITY, output)) {
                    "无法压缩本地媒体"
                }
                output.toByteArray()
            }
            PreparedMedia(
                bytes = bytes,
                mime = "image/jpeg",
                width = scaled.width,
                height = scaled.height,
            )
        } finally {
            if (scaled !== oriented) scaled.recycle()
            if (oriented !== source) oriented.recycle()
            source.recycle()
        }
    }

    override suspend fun saveDownloaded(
        clientUuid: String,
        kind: String,
        bytes: ByteArray,
        mime: String?,
    ): String = withContext(Dispatchers.IO) {
        require(bytes.isNotEmpty()) { "家庭服务器返回了空媒体" }
        val safeUuid = runCatching { UUID.fromString(clientUuid).toString() }
            .getOrElse { throw IllegalArgumentException("媒体同步标识无效") }
        val directoryName = if (kind == "avatar") AVATAR_DIRECTORY else RECORD_MEDIA_DIRECTORY
        val directory = File(context.filesDir, directoryName).apply {
            check(exists() || mkdirs()) { "无法创建本地媒体目录" }
        }
        val extension = extensionForMime(mime)
        val target = File(directory, "sync_$safeUuid.$extension")
        val temporary = File.createTempFile(".sync_", ".tmp", directory)
        try {
            temporary.outputStream().buffered().use { it.write(bytes) }
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                check(temporary.delete()) { "无法清理媒体临时文件" }
            }
        } finally {
            temporary.delete()
        }
        if (kind == "avatar") target.relativeTo(context.filesDir).path else target.absolutePath
    }

    override suspend fun delete(localUri: String) = withContext(Dispatchers.IO) {
        deleteExistingSyncMediaFile(resolve(localUri))
    }

    private fun resolve(localUri: String): File? {
        if (localUri.isBlank()) return null
        val root = context.filesDir.canonicalFile
        val raw = File(localUri)
        val candidate = if (raw.isAbsolute) raw.canonicalFile else File(root, localUri).canonicalFile
        return candidate.takeIf { it.path.startsWith(root.path + File.separator) }
    }

    private fun mimeFromName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }

    private fun extensionForMime(mime: String?): String = when (mime) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        else -> "jpg"
    }

    private fun readExifOrientation(file: File): Int = runCatching {
        ExifInterface(file).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun applyExifOrientation(source: Bitmap, orientation: Int): Bitmap {
        val transform = exifOrientationTransform(orientation)
        if (transform == ExifOrientationTransform()) return source
        val matrix = Matrix().apply {
            setRotate(transform.rotationDegrees)
            if (transform.flipHorizontal) postScale(-1f, 1f)
        }
        return Bitmap.createBitmap(
            source,
            0,
            0,
            source.width,
            source.height,
            matrix,
            true,
        )
    }

    private companion object {
        const val MEDIA_DECODE_MAX_EDGE = 2_048
        const val MEDIA_UPLOAD_MAX_EDGE = 1_600
        const val MEDIA_JPEG_QUALITY = 86
        const val AVATAR_DIRECTORY = "baby_avatars"
        const val RECORD_MEDIA_DIRECTORY = "record-media"
    }
}

internal fun deleteExistingSyncMediaFile(file: File?) {
    if (file?.isFile != true) return
    check(file.delete() || !file.exists()) { "无法删除本地媒体文件" }
}

internal data class ExifOrientationTransform(
    val rotationDegrees: Float = 0f,
    val flipHorizontal: Boolean = false,
)

internal fun exifOrientationTransform(orientation: Int): ExifOrientationTransform = when (orientation) {
    ExifInterface.ORIENTATION_FLIP_HORIZONTAL ->
        ExifOrientationTransform(flipHorizontal = true)
    ExifInterface.ORIENTATION_ROTATE_180 ->
        ExifOrientationTransform(rotationDegrees = 180f)
    ExifInterface.ORIENTATION_FLIP_VERTICAL ->
        ExifOrientationTransform(rotationDegrees = 180f, flipHorizontal = true)
    ExifInterface.ORIENTATION_TRANSPOSE ->
        ExifOrientationTransform(rotationDegrees = 90f, flipHorizontal = true)
    ExifInterface.ORIENTATION_ROTATE_90 ->
        ExifOrientationTransform(rotationDegrees = 90f)
    ExifInterface.ORIENTATION_TRANSVERSE ->
        ExifOrientationTransform(rotationDegrees = -90f, flipHorizontal = true)
    ExifInterface.ORIENTATION_ROTATE_270 ->
        ExifOrientationTransform(rotationDegrees = -90f)
    else -> ExifOrientationTransform()
}
