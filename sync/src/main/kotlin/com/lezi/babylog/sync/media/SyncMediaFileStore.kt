package com.lezi.babylog.sync.media
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.lezi.babylog.core.database.LocalDataClearScope
import com.lezi.babylog.core.model.RecordPhotoResourcePolicy
import com.lezi.babylog.sync.backend.MediaDownloadReceipt
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
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
    /** HTTP body length (`Content-Length`). Zero for a family-blob bind PUT. */
    val contentLength: Long

    /**
     * Receipt/blob size the server must echo. Same as [contentLength] for a
     * full upload; the frozen spool size for [EmptyCausalMediaBindSource].
     */
    val declaredByteSize: Long get() = contentLength
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

/**
 * Suspend operations are main-safe: concrete disk stores own their I/O dispatch.
 * The engine keeps orchestration and in-memory adapters on the caller's dispatcher.
 */
interface SyncMediaFileStore {
    /**
     * Resolves [localUri] to a readable regular file. Default is an absolute
     * [java.io.File] path. Product stores also accept filesDir-relative paths
     * (avatars). Used to hash skip-candidate / import files without assuming
     * the Room string is already absolute.
     */
    fun readableFile(localUri: String): File? {
        if (localUri.isBlank()) return null
        val file = File(localUri)
        return file.takeIf { it.isFile && it.canRead() }
    }

    /**
     * Cheap length-only probe for repair pre-filters: one stat, no decode.
     * Null when the file is absent or unreadable. Default rides [readableFile].
     */
    suspend fun statLength(localUri: String): Long? = withContext(Dispatchers.IO) {
        readableFile(localUri)?.length()
    }

    suspend fun inspect(localUri: String): LocalMediaInfo?
    suspend fun prepareUpload(localUri: String): PreparedMedia
    suspend fun saveDownloaded(
        clientUuid: String,
        kind: String,
        bytes: ByteArray,
        mime: String?,
    ): String

    /** Production uses an exclusive file; the adapter supports existing in-memory stores. */
    suspend fun beginDownload(
        clientUuid: String,
        kind: String,
        mime: String?,
        registerOwnership: (MediaDownloadOwnership) -> Unit,
    ): SyncMediaDownloadTarget =
        BufferedMediaDownloadTarget(this, clientUuid, kind, mime).also(registerOwnership)

    /** Re-arm owned replacement bytes before Room can forget their path. Legacy stores are unchanged. */
    suspend fun retainForReplacement(
        localUri: String,
        registerOwnership: (MediaDownloadOwnership) -> Unit,
    ) = Unit

    /** Only files with strict durable ownership markers may be recovered. */
    suspend fun pendingDownloads(): List<MediaDownloadOwnership> = emptyList()

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
    private val downloads by lazy { OwnedMediaDownloadStore(context.filesDir) }

    override suspend fun beginDownload(
        clientUuid: String,
        kind: String,
        mime: String?,
        registerOwnership: (MediaDownloadOwnership) -> Unit,
    ): SyncMediaDownloadTarget = withContext(Dispatchers.IO) {
        // Register before withContext's cancellable return, so even a lost result
        // has an owner whose finally closes/reclaims the file after all writers join.
        downloads.begin(clientUuid, kind, mime).also(registerOwnership)
    }

    override suspend fun retainForReplacement(
        localUri: String,
        registerOwnership: (MediaDownloadOwnership) -> Unit,
    ) = withContext(Dispatchers.IO) {
        downloads.retainForReplacement(localUri)?.let(registerOwnership)
        Unit
    }

    override suspend fun pendingDownloads(): List<MediaDownloadOwnership> = withContext(Dispatchers.IO) {
        downloads.pending()
    }

    override fun readableFile(localUri: String): File? =
        resolve(localUri)?.takeIf { it.isFile && it.canRead() }

    override suspend fun inspect(localUri: String): LocalMediaInfo? = withContext(Dispatchers.IO) {
        val file = readableFile(localUri) ?: return@withContext null
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

    private fun resolve(localUri: String): File? =
        resolveUnderFilesRoot(context.filesDir.canonicalFile, localUri)

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

private class BufferedMediaDownloadTarget(
    private val store: SyncMediaFileStore,
    private val clientUuid: String,
    private val kind: String,
    private val mime: String?,
) : SyncMediaDownloadTarget {
    override val output = ByteArrayOutputStream()
    private var localUri: String? = null

    override suspend fun complete(receipt: MediaDownloadReceipt): String {
        require(receipt.byteCount > 0) { "家庭服务器返回了空媒体" }
        check(receipt.byteCount == output.size().toLong()) { "下载媒体长度与流式回执不符" }
        return store.saveDownloaded(clientUuid, kind, output.toByteArray(), mime).also { localUri = it }
    }

    override fun close() = output.close()

    override suspend fun release(reclaimUnreferenced: suspend (String) -> Unit) {
        close()
        localUri?.let { reclaimUnreferenced(it) }
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
    if (file == null) return
    if (file.isFile) check(file.delete() || !file.exists()) { "无法删除本地媒体文件" }
    removeEmptyOwnedDownloadDirectory(file)
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
