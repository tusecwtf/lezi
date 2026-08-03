package com.lezi.babylog.sync.media
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.lezi.babylog.core.database.LocalDataClearScope
import com.lezi.babylog.core.model.RecordPhotoResourcePolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class LocalMediaInfo(
    val byteSize: Long,
    val mime: String,
    val width: Int?,
    val height: Int?,
)

interface SyncMediaUploadSource {
    val contentLength: Long
    val mime: String?
    fun openStream(): InputStream
}

class PreparedMedia(
    val file: File,
    override val mime: String,
    val width: Int? = null,
    val height: Int? = null,
) : SyncMediaUploadSource, AutoCloseable {
    override val contentLength: Long = file.length()

    init {
        require(file.isFile && contentLength > 0L) { "待上传媒体文件为空" }
        require(contentLength <= RecordPhotoResourcePolicy.maxUploadBytes) {
            "待上传媒体不能超过 8 MiB"
        }
    }

    override fun openStream(): InputStream =
        file.inputStream().buffered(RecordPhotoResourcePolicy.streamBufferBytes)

    override fun close() {
        check(file.delete() || !file.exists()) { "无法清理待上传媒体临时文件" }
    }
}

interface SyncMediaFileStore {
    suspend fun inspect(localUri: String): LocalMediaInfo?
    suspend fun prepareUpload(localUri: String): PreparedMedia
    suspend fun saveDownloaded(
        clientUuid: String,
        kind: String,
        bytes: ByteArray,
        mime: String?,
    ): String

    /**
     * Deletes a local media file when present (missing = success).
     *
     * **Caller invariant (not enforced here):** reference-aware reclaim
     * ([ReferenceAwareMediaFileCleanup]) must call this **outside** any
     * [com.lezi.babylog.core.database.DatabaseTransactionRunner] / Room write lease so
     * slow FS work cannot hold the write connection. Local replica clear intentionally
     * deletes under a write lease while wiping the DB; do not add a shared depth==0
     * guard on this method or that path breaks.
     */
    suspend fun delete(localUri: String)

    /**
     * Reclaims files in product-owned media roots that have no surviving Room owner.
     *
     * Implementations must make this scope explicit; silently skipping the sweep
     * would break the user-facing privacy contract of local clear.
     */
    suspend fun sweepUnreferenced(
        scope: LocalDataClearScope,
        retainedLocalUris: Set<String>,
    )
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
        require(file.length() in 1L..RecordPhotoResourcePolicy.maxSourceBytes) {
            "本地媒体文件大小超出支持范围"
        }
        val bounds = BitmapFactory.Options().also { it.inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(RecordPhotoResourcePolicy.isAllowedMime(bounds.outMimeType)) {
            "本地媒体格式不受支持"
        }
        require(
            bounds.outWidth > 0 &&
                bounds.outHeight > 0 &&
                bounds.outWidth <= RecordPhotoResourcePolicy.maxSourceEdge &&
                bounds.outHeight <= RecordPhotoResourcePolicy.maxSourceEdge &&
                bounds.outWidth.toLong() * bounds.outHeight <=
                RecordPhotoResourcePolicy.maxSourcePixels,
        ) { "本地媒体尺寸超出支持范围" }
        val sample = RecordPhotoResourcePolicy.decodeSampleSize(
            width = bounds.outWidth,
            height = bounds.outHeight,
            maxEdge = RecordPhotoResourcePolicy.maxUploadEdge,
            maxPixels = RecordPhotoResourcePolicy.maxUploadPixels,
        )
        translateMediaPrepareOutOfMemory {
            prepareDecodedUpload(file, sample)
        }
    }

    private suspend fun prepareDecodedUpload(file: File, sample: Int): PreparedMedia {
        var source: Bitmap? = null
        var oriented: Bitmap? = null
        var scaled: Bitmap? = null
        var temporary: File? = null
        var handedOff = false
        try {
            currentCoroutineContext().ensureActive()
            source = requireNotNull(
                BitmapFactory.decodeFile(
                    file.path,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                ),
            ) { "无法读取本地媒体" }
            oriented = applyExifOrientation(source, readExifOrientation(file))
            if (oriented !== source) source.recycleIfNeeded()
            scaled = if (
                max(oriented.width, oriented.height) > RecordPhotoResourcePolicy.maxUploadEdge
            ) {
                val ratio = RecordPhotoResourcePolicy.maxUploadEdge.toDouble() /
                    max(oriented.width, oriented.height)
                Bitmap.createScaledBitmap(
                    oriented,
                    (oriented.width * ratio).toInt().coerceAtLeast(1),
                    (oriented.height * ratio).toInt().coerceAtLeast(1),
                    true,
                )
            } else {
                oriented
            }
            if (scaled !== oriented) oriented.recycleIfNeeded()

            val temporaryDirectory = File(context.cacheDir, "sync-media-upload").apply {
                check(exists() || mkdirs()) { "无法创建待上传媒体目录" }
            }
            temporary = File.createTempFile("normalized_", ".jpg", temporaryDirectory)
            currentCoroutineContext().ensureActive()
            val job = currentCoroutineContext()[Job]
            temporary.outputStream().buffered(RecordPhotoResourcePolicy.streamBufferBytes).use { output ->
                val bounded = CancellableBoundedOutputStream(
                    output = output,
                    maxBytes = RecordPhotoResourcePolicy.maxUploadBytes,
                    job = job,
                )
                check(
                    scaled.compress(
                        Bitmap.CompressFormat.JPEG,
                        RecordPhotoResourcePolicy.jpegQuality,
                        bounded,
                    ),
                ) {
                    "无法压缩本地媒体"
                }
            }
            currentCoroutineContext().ensureActive()
            val prepared = PreparedMedia(
                file = temporary,
                mime = "image/jpeg",
                width = scaled.width,
                height = scaled.height,
            )
            handedOff = true
            return prepared
        } finally {
            if (!handedOff) temporary?.delete()
            scaled.recycleIfNeeded()
            if (oriented !== scaled) oriented.recycleIfNeeded()
            if (source !== oriented && source !== scaled) source.recycleIfNeeded()
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

    override suspend fun sweepUnreferenced(
        scope: LocalDataClearScope,
        retainedLocalUris: Set<String>,
    ) = withContext(Dispatchers.IO) {
        sweepUnreferencedProductMedia(
            filesDir = context.filesDir,
            scope = scope,
            retainedLocalUris = retainedLocalUris,
        )
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
        const val AVATAR_DIRECTORY = "baby_avatars"
        const val RECORD_MEDIA_DIRECTORY = "record-media"
    }
}

/**
 * Scope-aware local-clear sweep for the two app-private product media roots.
 *
 * RecordsOnly owns `record-media`; AllLocalData additionally owns `baby_avatars`.
 * The root directories themselves remain so a later import does not race directory
 * creation. Canonical containment keeps stale or hostile paths outside filesDir out
 * of the deletion set.
 */
internal fun sweepUnreferencedProductMedia(
    filesDir: File,
    scope: LocalDataClearScope,
    retainedLocalUris: Set<String>,
) {
    val filesRoot = filesDir.canonicalFile
    val retained = retainedLocalUris.mapNotNullTo(linkedSetOf()) { localUri ->
        resolveUnderFilesRoot(filesRoot, localUri)?.path
    }
    val roots = buildList {
        add(File(filesRoot, "record-media").canonicalFile)
        if (scope == LocalDataClearScope.AllLocalData) {
            add(File(filesRoot, "baby_avatars").canonicalFile)
        }
    }
    roots.forEach { root ->
        if (!root.exists()) return@forEach
        check(root.isDirectory && root.path.startsWith(filesRoot.path + File.separator)) {
            "本机媒体目录无效"
        }
        sweepDirectoryContents(root, root, retained)
    }
}

private fun resolveUnderFilesRoot(filesRoot: File, localUri: String): File? {
    if (localUri.isBlank()) return null
    val raw = File(localUri)
    val candidate = if (raw.isAbsolute) raw.canonicalFile else File(filesRoot, localUri).canonicalFile
    return candidate.takeIf { it.path.startsWith(filesRoot.path + File.separator) }
}

private fun sweepDirectoryContents(
    directory: File,
    ownedRoot: File,
    retained: Set<String>,
) {
    val children = checkNotNull(directory.listFiles()) { "无法读取本机媒体目录" }
    children.forEach { child ->
        if (Files.isSymbolicLink(child.toPath())) {
            check(Files.deleteIfExists(child.toPath())) { "无法清理本机媒体链接" }
            return@forEach
        }
        val canonical = child.canonicalFile
        if (!canonical.path.startsWith(ownedRoot.path + File.separator)) {
            error("本机媒体路径越界")
        }
        if (child.isDirectory) {
            sweepDirectoryContents(child, ownedRoot, retained)
            if (child.listFiles()?.isEmpty() == true) {
                check(child.delete() || !child.exists()) { "无法清理本机媒体空目录" }
            }
        } else if (canonical.path !in retained) {
            check(child.delete() || !child.exists()) { "无法清理本机媒体文件" }
        }
    }
}

private class CancellableBoundedOutputStream(
    output: OutputStream,
    private val maxBytes: Long,
    private val job: Job?,
) : FilterOutputStream(output) {
    private var written = 0L

    override fun write(value: Int) {
        beforeWrite(1)
        out.write(value)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        beforeWrite(length)
        out.write(buffer, offset, length)
    }

    private fun beforeWrite(count: Int) {
        job?.ensureActive()
        written += count
        require(written <= maxBytes) { "待上传媒体不能超过 8 MiB" }
    }
}

private fun Bitmap?.recycleIfNeeded() {
    if (this != null && !isRecycled) recycle()
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
