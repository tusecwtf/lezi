package com.lezi.babylog.designsystem

/**
 * Conservative in-memory bounds for the shared local-photo preview cache.
 *
 * Dual limits apply together: entry caps by [LocalPhotoTarget] **and** a shared
 * decoded-byte budget. Values are ARGB_8888-oriented (4 bytes/pixel).
 *
 * **Soft caps under pin:** entry and byte limits only evict *unpinned* entries.
 * Concurrent [rememberLocalPhoto] / loader pins (e.g. HorizontalPager offscreen
 * pages) may therefore retain more than [MAX_FULLSCREEN_ENTRIES] fullscreen
 * bitmaps and can exceed [MAX_DECODED_BYTES] while those compositions stay
 * ready. Caps become hard only after unpin; product “at most” wording is the
 * unpinned steady-state target, not a hard peak while pages remain composed.
 *
 * This type is internal so feature modules cannot spin parallel caches;
 * product code uses [rememberLocalPhoto] / [BoundedLocalPhotoLoader] only.
 */
internal object LocalPhotoCachePolicy {
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
internal data class LocalPhotoCachedValue<T : Any>(
    val value: T,
    val plan: LocalPhotoDecodePlan,
)

/**
 * Process-local LRU for decoded record/plan photo previews.
 *
 * Callers receive one pin from [get] or [put] and must [unpin] when the value is
 * no longer displayed. Only unpinned entries are eligible for eviction; eviction
 * invokes [release] so platform bitmaps can be recycled.
 *
 * Internal to designsystem: wire only through [BoundedLocalPhotoLoader] and
 * [rememberLocalPhoto]. Same-module JVM tests may construct instances directly.
 */
internal class LocalPhotoMemoryCache<T : Any>(
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

    /**
     * When only a thumbnail or fullscreen entry cap is violated, prefer the
     * eldest unpinned victim of that target so dual-budget thrash does not
     * recycle thumbs to free a fullscreen overage (or the reverse). Pure or
     * mixed byte overage falls back to global unpinned LRU.
     */
    private fun evictUntilWithinBoundsLocked() {
        while (true) {
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
            val thumbsOver = thumbs > maxThumbnailEntries
            val fullsOver = fulls > maxFullscreenEntries
            val bytesOver = bytes > maxDecodedBytes
            if (!thumbsOver && !fullsOver && !bytesOver) break

            val preferredTarget: LocalPhotoTarget? = when {
                // Entry-cap-only pressure: pick a peer of the violated target.
                thumbsOver && !fullsOver && !bytesOver -> LocalPhotoTarget.THUMBNAIL
                fullsOver && !thumbsOver && !bytesOver -> LocalPhotoTarget.FULLSCREEN
                else -> null
            }
            val victimKey = if (preferredTarget != null) {
                entries.entries
                    .firstOrNull { it.value.pins == 0 && it.key.target == preferredTarget }
                    ?.key
                    // No unpinned peer of that target (all pinned): stop; soft cap under pin.
                    ?: break
            } else {
                entries.entries.firstOrNull { it.value.pins == 0 }?.key
                    ?: break
            }
            val victim = entries.remove(victimKey) ?: break
            releaseSafely(victim.value)
        }
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
