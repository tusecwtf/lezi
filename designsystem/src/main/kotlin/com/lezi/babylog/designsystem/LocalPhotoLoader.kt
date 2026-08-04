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

data class LocalPhotoCacheKey(
    val path: String,
    val target: LocalPhotoTarget,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val orientation: LocalPhotoOrientation,
)

data class LocalPhotoDecodePlan(
    val sampleSize: Int,
    val decodedWidth: Int,
    val decodedHeight: Int,
    val decodedPixels: Long,
    val cacheKey: LocalPhotoCacheKey,
)

/**
 * Conservative in-memory bounds for the shared local-photo preview cache.
 *
 * Dual limits apply together: entry caps by [LocalPhotoTarget] **and** a shared
 * decoded-byte budget. Values are ARGB_8888-oriented (4 bytes/pixel).
 */
object LocalPhotoCachePolicy {
    const val MAX_THUMBNAIL_ENTRIES: Int = 24
    const val MAX_FULLSCREEN_ENTRIES: Int = 2
    /** 24 MiB shared decoded budget (within the product ~16–32 MiB band). */
    const val MAX_DECODED_BYTES: Long = 24L * 1024L * 1024L
    const val BYTES_PER_PIXEL: Int = 4

    fun decodedBytes(width: Int, height: Int): Long =
        width.toLong() * height.toLong() * BYTES_PER_PIXEL

    fun decodedBytes(plan: LocalPhotoDecodePlan): Long =
        decodedBytes(plan.decodedWidth, plan.decodedHeight)
}

/** Value + plan pair returned from cache get/put. */
data class LocalPhotoCachedValue<T : Any>(
    val value: T,
    val plan: LocalPhotoDecodePlan,
)

/**
 * Process-local LRU for decoded record/plan photo previews.
 *
 * Callers receive one pin from [get] or [put] and must [unpin] when the value is
 * no longer displayed. Only unpinned entries are eligible for eviction; eviction
 * invokes [release] so platform bitmaps can be recycled.
 */
class LocalPhotoMemoryCache<T : Any>(
    val maxThumbnailEntries: Int = LocalPhotoCachePolicy.MAX_THUMBNAIL_ENTRIES,
    val maxFullscreenEntries: Int = LocalPhotoCachePolicy.MAX_FULLSCREEN_ENTRIES,
    val maxDecodedBytes: Long = LocalPhotoCachePolicy.MAX_DECODED_BYTES,
    private val release: (T) -> Unit,
) {
    private data class Entry<T : Any>(
        val value: T,
        val plan: LocalPhotoDecodePlan,
        val byteSize: Long,
        var pins: Int,
    )

    private val lock = Any()
    private val entries = LinkedHashMap<LocalPhotoCacheKey, Entry<T>>(16, 0.75f, /* accessOrder = */ true)

    val totalEntryCount: Int
        get() = synchronized(lock) { entries.size }

    val thumbnailEntryCount: Int
        get() = synchronized(lock) {
            entries.keys.count { it.target == LocalPhotoTarget.THUMBNAIL }
        }

    val fullscreenEntryCount: Int
        get() = synchronized(lock) {
            entries.keys.count { it.target == LocalPhotoTarget.FULLSCREEN }
        }

    val decodedByteSize: Long
        get() = synchronized(lock) { entries.values.sumOf { it.byteSize } }

    /** Cache hit: moves to MRU and grants one pin. */
    fun get(key: LocalPhotoCacheKey): LocalPhotoCachedValue<T>? = synchronized(lock) {
        val entry = entries[key] ?: return null
        entry.pins += 1
        LocalPhotoCachedValue(entry.value, entry.plan)
    }

    /**
     * Inserts [value] under [plan].cacheKey, granting the caller one pin.
     * If another entry already won the same key, releases [value] when distinct
     * and pins the existing entry instead.
     */
    fun put(value: T, plan: LocalPhotoDecodePlan): LocalPhotoCachedValue<T> = synchronized(lock) {
        val key = plan.cacheKey
        val existing = entries[key]
        if (existing != null) {
            if (value !== existing.value) {
                releaseSafely(value)
            }
            existing.pins += 1
            return LocalPhotoCachedValue(existing.value, existing.plan)
        }
        val byteSize = LocalPhotoCachePolicy.decodedBytes(plan)
        entries[key] = Entry(value = value, plan = plan, byteSize = byteSize, pins = 1)
        evictUntilWithinBoundsLocked()
        val stored = entries[key]
            ?: error("fresh pin must remain after eviction of unpinned peers only")
        LocalPhotoCachedValue(stored.value, stored.plan)
    }

    fun unpin(key: LocalPhotoCacheKey) {
        synchronized(lock) {
            val entry = entries[key] ?: return
            if (entry.pins > 0) {
                entry.pins -= 1
            }
            evictUntilWithinBoundsLocked()
        }
    }

    private fun evictUntilWithinBoundsLocked() {
        while (overBudgetLocked()) {
            val victimKey = entries.entries.firstOrNull { it.value.pins == 0 }?.key
                ?: break
            val victim = entries.remove(victimKey) ?: break
            releaseSafely(victim.value)
        }
    }

    private fun overBudgetLocked(): Boolean {
        var thumbs = 0
        var fulls = 0
        var bytes = 0L
        for ((key, entry) in entries) {
            when (key.target) {
                LocalPhotoTarget.THUMBNAIL -> thumbs += 1
                LocalPhotoTarget.FULLSCREEN -> fulls += 1
            }
            bytes += entry.byteSize
        }
        return thumbs > maxThumbnailEntries ||
            fulls > maxFullscreenEntries ||
            bytes > maxDecodedBytes
    }

    private fun releaseSafely(value: T) {
        try {
            release(value)
        } catch (_: OutOfMemoryError) {
            // Eviction cleanup must not throw into load/unpin callers.
        } catch (_: Exception) {
            // Eviction cleanup must not throw into load/unpin callers.
        }
    }
}

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
 * When [cache] is provided, successful results are pinned in the cache; callers
 * must [LocalPhotoMemoryCache.unpin] the [LocalPhotoDecodePlan.cacheKey] when the
 * value is no longer displayed. Cancelled loads never populate the cache.
 */
class BoundedLocalPhotoLoader<T : Any>(
    private val source: LocalPhotoDecodeSource<T>,
    private val decodeContext: CoroutineContext = Dispatchers.IO,
    private val cache: LocalPhotoMemoryCache<T>? = null,
) {
    suspend fun load(request: LocalPhotoDecodeRequest): LocalPhotoLoadResult<T> =
        withContext(decodeContext) {
            var ownedValue: LocalPhotoDecoded<T>? = null
            var pinnedKey: LocalPhotoCacheKey? = null
            try {
                coroutineContext.ensureActive()
                if (request.path.isBlank()) return@withContext LocalPhotoLoadResult.Unavailable
                val sourceInfo = source.inspect(request.path)
                    ?: return@withContext LocalPhotoLoadResult.Unavailable
                val provisionalPlan = decodePlan(request, sourceInfo)
                    ?: return@withContext LocalPhotoLoadResult.Unavailable

                cache?.get(provisionalPlan.cacheKey)?.let { cached ->
                    pinnedKey = provisionalPlan.cacheKey
                    coroutineContext.ensureActive()
                    pinnedKey = null // pin ownership transfers to caller via Ready
                    return@withContext LocalPhotoLoadResult.Ready(
                        value = cached.value,
                        plan = cached.plan,
                    )
                }

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
                cacheKey = LocalPhotoCacheKey(
                    path = request.path,
                    target = request.target,
                    sourceWidth = source.width,
                    sourceHeight = source.height,
                    orientation = source.orientation,
                ),
            )
        }
        sampleSize *= 2
    }
}

private fun ceilDiv(value: Int, divisor: Int): Int =
    (value.toLong() + divisor - 1L).div(divisor).toInt()
