package com.lezi.babylog.designsystem

import com.lezi.babylog.core.model.RecordPhotoResourcePolicy
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

enum class LocalPhotoTarget(
    val maxWidthPx: Int,
    val maxHeightPx: Int,
    val maxPixels: Long,
) {
    THUMBNAIL(maxWidthPx = 256, maxHeightPx = 256, maxPixels = 65_536L),
    FULLSCREEN(maxWidthPx = 2_048, maxHeightPx = 2_048, maxPixels = 4_194_304L),
}

enum class LocalPhotoOrientation(internal val swapsAxes: Boolean) {
    NORMAL(false),
    FLIP_HORIZONTAL(false),
    ROTATE_180(false),
    FLIP_VERTICAL(false),
    TRANSPOSE(true),
    ROTATE_90(true),
    TRANSVERSE(true),
    ROTATE_270(true),
}

/** Maps the eight EXIF orientation values; unknown metadata is rendered as normal. */
fun localPhotoOrientationForExif(value: Int): LocalPhotoOrientation = when (value) {
    2 -> LocalPhotoOrientation.FLIP_HORIZONTAL
    3 -> LocalPhotoOrientation.ROTATE_180
    4 -> LocalPhotoOrientation.FLIP_VERTICAL
    5 -> LocalPhotoOrientation.TRANSPOSE
    6 -> LocalPhotoOrientation.ROTATE_90
    7 -> LocalPhotoOrientation.TRANSVERSE
    8 -> LocalPhotoOrientation.ROTATE_270
    else -> LocalPhotoOrientation.NORMAL
}

data class LocalPhotoDecodeRequest(
    val path: String,
    val target: LocalPhotoTarget,
)

data class LocalPhotoSourceInfo(
    val width: Int,
    val height: Int,
    val orientation: LocalPhotoOrientation,
)

/**
 * Cheap file stamp (length + mtime). Not source identity: an in-place
 * orientation edit can keep both fields unchanged.
 */
data class LocalPhotoFileStamp(
    val length: Long,
    val lastModifiedEpochMillis: Long,
)

/**
 * Cache identity for a decoded local photo.
 *
 * Path, target, source width, height, and orientation are required so an EXIF
 * or size change cannot reuse the old bitmap. Length and mtime may be included,
 * but a stamp match is not a lookup: the key is known only after inspect.
 * Internal: product code must not build parallel caches keyed by this type.
 */
internal data class LocalPhotoCacheKey(
    val path: String,
    val target: LocalPhotoTarget,
    val width: Int,
    val height: Int,
    val orientation: LocalPhotoOrientation,
    val length: Long,
    val lastModifiedEpochMillis: Long,
)

/**
 * Decode geometry for a successful load. Constructed only inside designsystem;
 * [cacheKey] is internal pin identity so features cannot key parallel caches.
 */
@ConsistentCopyVisibility
data class LocalPhotoDecodePlan internal constructor(
    val sampleSize: Int,
    val decodedWidth: Int,
    val decodedHeight: Int,
    val decodedPixels: Long,
    internal val cacheKey: LocalPhotoCacheKey,
)

sealed interface LocalPhotoLoadResult<out T : Any> {
    data object Loading : LocalPhotoLoadResult<Nothing>

    data class Ready<T : Any>(
        val value: T,
        val plan: LocalPhotoDecodePlan,
    ) : LocalPhotoLoadResult<T>

    data object Unavailable : LocalPhotoLoadResult<Nothing>
}

data class LocalPhotoDecoded<T : Any>(
    val value: T,
    val width: Int,
    val height: Int,
)

/** File and platform bitmap operations consumed by [BoundedLocalPhotoLoader]. */
interface LocalPhotoDecodeSource<T : Any> {
    /**
     * Length + mtime only. Null when the path is not a readable file.
     * A matching stamp must not skip [inspect]; width, height, and orientation
     * are required before a cache key exists. The default is a constant so JVM
     * fakes share an entry only when inspect also reports the same geometry.
     */
    suspend fun fileStamp(path: String): LocalPhotoFileStamp? =
        LocalPhotoFileStamp(length = 1L, lastModifiedEpochMillis = 0L)

    suspend fun inspect(path: String): LocalPhotoSourceInfo?

    suspend fun decode(path: String, sampleSize: Int): LocalPhotoDecoded<T>?

    /** Consumes [decoded] ownership and returns the oriented value. */
    suspend fun applyOrientation(
        decoded: LocalPhotoDecoded<T>,
        orientation: LocalPhotoOrientation,
    ): LocalPhotoDecoded<T>

    fun release(decoded: T)
}

/**
 * Loads one local photo on [decodeContext], bounded by the requested display budget.
 *
 * Product callers use the no-cache constructor or [rememberLocalPhoto] (which
 * wires the process-local [LocalPhotoMemoryCache]). Same-module code may pass a
 * cache; successful results are pinned and must be unpinned when no longer
 * displayed. Cancelled loads never populate the cache.
 */
class BoundedLocalPhotoLoader<T : Any> private constructor(
    private val source: LocalPhotoDecodeSource<T>,
    private val decodeContext: CoroutineContext,
    private val cache: LocalPhotoMemoryCache<T>?,
) {
    constructor(
        source: LocalPhotoDecodeSource<T>,
        decodeContext: CoroutineContext = Dispatchers.IO,
    ) : this(source, decodeContext, cache = null)

    /** Same-module shared-cache wiring (Android process cache + JVM tests). */
    internal constructor(
        source: LocalPhotoDecodeSource<T>,
        cache: LocalPhotoMemoryCache<T>?,
        decodeContext: CoroutineContext = Dispatchers.IO,
    ) : this(source, decodeContext, cache)

    suspend fun load(request: LocalPhotoDecodeRequest): LocalPhotoLoadResult<T> =
        withContext(decodeContext) {
            var ownedValue: LocalPhotoDecoded<T>? = null
            var pinnedKey: LocalPhotoCacheKey? = null
            try {
                coroutineContext.ensureActive()
                if (request.path.isBlank()) return@withContext LocalPhotoLoadResult.Unavailable
                val stamp = source.fileStamp(request.path)
                    ?: return@withContext LocalPhotoLoadResult.Unavailable
                val sourceInfo = source.inspect(request.path)
                    ?: return@withContext LocalPhotoLoadResult.Unavailable
                val lookupKey = LocalPhotoCacheKey(
                    path = request.path,
                    target = request.target,
                    width = sourceInfo.width,
                    height = sourceInfo.height,
                    orientation = sourceInfo.orientation,
                    length = stamp.length,
                    lastModifiedEpochMillis = stamp.lastModifiedEpochMillis,
                )
                cache?.get(lookupKey)?.let { cached ->
                    pinnedKey = lookupKey
                    coroutineContext.ensureActive()
                    pinnedKey = null // pin ownership transfers to caller via Ready
                    return@withContext LocalPhotoLoadResult.Ready(
                        value = cached.value,
                        plan = cached.plan,
                    )
                }

                val provisionalPlan = decodePlan(request, sourceInfo, lookupKey)
                    ?: return@withContext LocalPhotoLoadResult.Unavailable

                coroutineContext.ensureActive()
                ownedValue = source.decode(request.path, provisionalPlan.sampleSize)
                    ?: return@withContext LocalPhotoLoadResult.Unavailable
                coroutineContext.ensureActive()
                ownedValue = source.applyOrientation(ownedValue, sourceInfo.orientation)
                coroutineContext.ensureActive()
                val decodedPixels = ownedValue.width.toLong() * ownedValue.height
                if (
                    ownedValue.width <= 0 ||
                    ownedValue.height <= 0 ||
                    ownedValue.width > request.target.maxWidthPx ||
                    ownedValue.height > request.target.maxHeightPx ||
                    decodedPixels > request.target.maxPixels
                ) {
                    releaseSafely(ownedValue)
                    ownedValue = null
                    return@withContext LocalPhotoLoadResult.Unavailable
                }
                val finalPlan = provisionalPlan.copy(
                    decodedWidth = ownedValue.width,
                    decodedHeight = ownedValue.height,
                    decodedPixels = decodedPixels,
                )
                val readyValue = ownedValue.value
                ownedValue = null
                if (cache != null) {
                    val cached = cache.put(readyValue, finalPlan)
                    pinnedKey = finalPlan.cacheKey
                    coroutineContext.ensureActive()
                    pinnedKey = null
                    LocalPhotoLoadResult.Ready(value = cached.value, plan = cached.plan)
                } else {
                    LocalPhotoLoadResult.Ready(value = readyValue, plan = finalPlan)
                }
            } catch (cancelled: CancellationException) {
                pinnedKey?.let { key -> cache?.unpin(key) }
                ownedValue?.let(::releaseSafely)
                throw cancelled
            } catch (_: OutOfMemoryError) {
                pinnedKey?.let { key -> cache?.unpin(key) }
                ownedValue?.let(::releaseSafely)
                LocalPhotoLoadResult.Unavailable
            } catch (_: Exception) {
                pinnedKey?.let { key -> cache?.unpin(key) }
                ownedValue?.let(::releaseSafely)
                LocalPhotoLoadResult.Unavailable
            }
        }

    private fun releaseSafely(value: LocalPhotoDecoded<T>) {
        try {
            source.release(value.value)
        } catch (_: OutOfMemoryError) {
            // Cleanup failure must not replace the decode result or cancellation signal.
        } catch (_: Exception) {
            // Cleanup failure must not replace the decode result or cancellation signal.
        }
    }
}

private fun decodePlan(
    request: LocalPhotoDecodeRequest,
    source: LocalPhotoSourceInfo,
    cacheKey: LocalPhotoCacheKey,
): LocalPhotoDecodePlan? {
    if (
        source.width <= 0 ||
        source.height <= 0 ||
        source.width > RecordPhotoResourcePolicy.maxSourceEdge ||
        source.height > RecordPhotoResourcePolicy.maxSourceEdge ||
        source.width.toLong() * source.height > RecordPhotoResourcePolicy.maxSourcePixels
    ) {
        return null
    }
    val orientedWidth = if (source.orientation.swapsAxes) source.height else source.width
    val orientedHeight = if (source.orientation.swapsAxes) source.width else source.height
    var sampleSize = 1
    var decodedWidth: Int
    var decodedHeight: Int
    while (true) {
        decodedWidth = ceilDiv(orientedWidth, sampleSize)
        decodedHeight = ceilDiv(orientedHeight, sampleSize)
        val decodedPixels = decodedWidth.toLong() * decodedHeight
        if (
            decodedWidth <= request.target.maxWidthPx &&
            decodedHeight <= request.target.maxHeightPx &&
            decodedPixels <= request.target.maxPixels
        ) {
            return LocalPhotoDecodePlan(
                sampleSize = sampleSize,
                decodedWidth = decodedWidth,
                decodedHeight = decodedHeight,
                decodedPixels = decodedPixels,
                cacheKey = cacheKey,
            )
        }
        sampleSize *= 2
    }
}

private fun ceilDiv(value: Int, divisor: Int): Int =
    (value.toLong() + divisor - 1L).div(divisor).toInt()
