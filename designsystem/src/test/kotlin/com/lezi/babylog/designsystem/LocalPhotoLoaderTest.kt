package com.lezi.babylog.designsystem

import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPhotoLoaderTest {
    @Test
    fun `huge photo is sampled within each target budget without cache collisions`() =
        kotlinx.coroutines.runBlocking {
            val source = RecordingPhotoSource(
                inspections = mapOf(
                    "huge.jpg" to LocalPhotoSourceInfo(
                        width = 20_000,
                        height = 12_000,
                        orientation = LocalPhotoOrientation.NORMAL,
                    ),
                ),
            )
            val loader = BoundedLocalPhotoLoader(source)

            val thumbnail = loader.load(
                LocalPhotoDecodeRequest("huge.jpg", LocalPhotoTarget.THUMBNAIL),
            ) as LocalPhotoLoadResult.Ready
            val fullscreen = loader.load(
                LocalPhotoDecodeRequest("huge.jpg", LocalPhotoTarget.FULLSCREEN),
            ) as LocalPhotoLoadResult.Ready

            assertEquals(listOf(128, 16), source.requestedSampleSizes)
            assertTrue(thumbnail.plan.decodedWidth <= LocalPhotoTarget.THUMBNAIL.maxWidthPx)
            assertTrue(thumbnail.plan.decodedHeight <= LocalPhotoTarget.THUMBNAIL.maxHeightPx)
            assertTrue(thumbnail.plan.decodedPixels <= LocalPhotoTarget.THUMBNAIL.maxPixels)
            assertTrue(fullscreen.plan.decodedWidth <= LocalPhotoTarget.FULLSCREEN.maxWidthPx)
            assertTrue(fullscreen.plan.decodedHeight <= LocalPhotoTarget.FULLSCREEN.maxHeightPx)
            assertTrue(fullscreen.plan.decodedPixels <= LocalPhotoTarget.FULLSCREEN.maxPixels)
            assertNotEquals(thumbnail.plan.cacheKey, fullscreen.plan.cacheKey)
        }

    @Test
    fun `exif orientation is normalized before the final decode budget is accepted`() =
        kotlinx.coroutines.runBlocking {
            assertEquals(
                listOf(
                    LocalPhotoOrientation.NORMAL,
                    LocalPhotoOrientation.FLIP_HORIZONTAL,
                    LocalPhotoOrientation.ROTATE_180,
                    LocalPhotoOrientation.FLIP_VERTICAL,
                    LocalPhotoOrientation.TRANSPOSE,
                    LocalPhotoOrientation.ROTATE_90,
                    LocalPhotoOrientation.TRANSVERSE,
                    LocalPhotoOrientation.ROTATE_270,
                ),
                (1..8).map(::localPhotoOrientationForExif),
            )
            assertEquals(LocalPhotoOrientation.NORMAL, localPhotoOrientationForExif(0))
            assertEquals(LocalPhotoOrientation.NORMAL, localPhotoOrientationForExif(9))

            val source = RecordingPhotoSource(
                inspections = mapOf(
                    "rotated.jpg" to LocalPhotoSourceInfo(
                        width = 20_000,
                        height = 12_000,
                        orientation = localPhotoOrientationForExif(6),
                    ),
                ),
            )

            val result = BoundedLocalPhotoLoader(source).load(
                LocalPhotoDecodeRequest("rotated.jpg", LocalPhotoTarget.FULLSCREEN),
            ) as LocalPhotoLoadResult.Ready

            assertEquals(750, result.plan.decodedWidth)
            assertEquals(1_250, result.plan.decodedHeight)
            assertTrue(result.plan.decodedPixels <= LocalPhotoTarget.FULLSCREEN.maxPixels)
            assertEquals(listOf(LocalPhotoOrientation.ROTATE_90), source.appliedOrientations)
            assertEquals(1L, result.plan.cacheKey.length)
            assertEquals(0L, result.plan.cacheKey.lastModifiedEpochMillis)
            assertNotEquals(
                result.plan.cacheKey,
                result.plan.cacheKey.copy(length = result.plan.cacheKey.length + 1),
            )
            assertNotEquals(
                result.plan.cacheKey,
                result.plan.cacheKey.copy(
                    lastModifiedEpochMillis = result.plan.cacheKey.lastModifiedEpochMillis + 1,
                ),
            )
        }

    @Test
    fun `missing corrupt unsupported oom and hostile bounds share the unavailable result`() =
        kotlinx.coroutines.runBlocking {
            val validInfo = LocalPhotoSourceInfo(
                width = 800,
                height = 600,
                orientation = LocalPhotoOrientation.NORMAL,
            )
            val missing = BoundaryPhotoSource(inspectBlock = { null })
            val corrupt = BoundaryPhotoSource(
                inspectBlock = { throw IllegalArgumentException("bad header") },
            )
            val unsupported = BoundaryPhotoSource(
                inspectBlock = { validInfo },
                decodeBlock = { _, _ -> null },
            )
            val oom = BoundaryPhotoSource(
                inspectBlock = { validInfo },
                decodeBlock = { _, _ -> throw OutOfMemoryError("allocation") },
            )
            val hostileBounds = BoundaryPhotoSource(
                inspectBlock = {
                    LocalPhotoSourceInfo(
                        width = 65_536,
                        height = 4_096,
                        orientation = LocalPhotoOrientation.NORMAL,
                    )
                },
            )
            val hostilePixels = BoundaryPhotoSource(
                inspectBlock = {
                    LocalPhotoSourceInfo(
                        width = 32_768,
                        height = 32_768,
                        orientation = LocalPhotoOrientation.NORMAL,
                    )
                },
            )

            listOf(
                missing,
                corrupt,
                unsupported,
                oom,
                hostileBounds,
                hostilePixels,
            ).forEachIndexed { index, source ->
                val result = BoundedLocalPhotoLoader(source).load(
                    LocalPhotoDecodeRequest("case-$index.jpg", LocalPhotoTarget.THUMBNAIL),
                )
                assertSame(LocalPhotoLoadResult.Unavailable, result)
            }
            assertEquals(0, hostileBounds.decodeCount)
            assertEquals(0, hostilePixels.decodeCount)
        }

    @Test
    fun `cancelling stale photo releases it and a rapid replacement alone becomes ready`() =
        runBlocking {
            val source = SwitchingPhotoSource()
            val loader = BoundedLocalPhotoLoader(source)
            val stale = async {
                loader.load(
                    LocalPhotoDecodeRequest("a.jpg", LocalPhotoTarget.FULLSCREEN),
                )
            }
            source.staleOrientationStarted.await()

            stale.cancelAndJoin()
            val replacement = loader.load(
                LocalPhotoDecodeRequest("b.jpg", LocalPhotoTarget.FULLSCREEN),
            ) as LocalPhotoLoadResult.Ready

            assertTrue(stale.isCancelled)
            assertEquals(listOf("a.jpg@1"), source.released)
            assertEquals("b.jpg@1", replacement.value)
        }

    @Test
    fun `all source inspection decode and orientation work stays on the decode dispatcher`() {
        val dispatcher = Executors.newSingleThreadExecutor { task ->
            Thread(task, "bounded-photo-io")
        }.asCoroutineDispatcher()
        try {
            runBlocking {
                val threads = mutableListOf<String>()
                val source = BoundaryPhotoSource(
                    inspectBlock = {
                        threads += Thread.currentThread().name
                        LocalPhotoSourceInfo(100, 100, LocalPhotoOrientation.NORMAL)
                    },
                    decodeBlock = { path, sample ->
                        threads += Thread.currentThread().name
                        "$path@$sample"
                    },
                    orientationBlock = { decoded, _ ->
                        threads += Thread.currentThread().name
                        decoded
                    },
                )

                BoundedLocalPhotoLoader(source, dispatcher).load(
                    LocalPhotoDecodeRequest("off-main.jpg", LocalPhotoTarget.THUMBNAIL),
                )

                assertEquals(3, threads.size)
                assertTrue(threads.all { it.startsWith("bounded-photo-io") })
            }
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun `actual dimensions after orientation are rejected when the decoder exceeds the budget`() =
        runBlocking {
            val source = OversizedDecodedPhotoSource()

            val result = BoundedLocalPhotoLoader(source).load(
                LocalPhotoDecodeRequest("density-scaled.jpg", LocalPhotoTarget.THUMBNAIL),
            )

            assertSame(LocalPhotoLoadResult.Unavailable, result)
            assertEquals(listOf("oversized-bitmap"), source.released)
        }

    @Test
    fun `cache hit reuses decoded value and path target identity miss re-decodes`() = runBlocking {
        val source = RecordingPhotoSource(
            inspections = mapOf(
                "a.jpg" to LocalPhotoSourceInfo(200, 100, LocalPhotoOrientation.NORMAL),
                "b.jpg" to LocalPhotoSourceInfo(200, 100, LocalPhotoOrientation.NORMAL),
            ),
        )
        val released = mutableListOf<String>()
        val cache = LocalPhotoMemoryCache<String>(release = { released += it })
        val loader = BoundedLocalPhotoLoader(source, cache = cache)

        val first = loader.load(
            LocalPhotoDecodeRequest("a.jpg", LocalPhotoTarget.THUMBNAIL),
        ) as LocalPhotoLoadResult.Ready<String>
        val second = loader.load(
            LocalPhotoDecodeRequest("a.jpg", LocalPhotoTarget.THUMBNAIL),
        ) as LocalPhotoLoadResult.Ready<String>
        // Third identical load is a hit (not an orientation miss).
        val sameIdentityHit = loader.load(
            LocalPhotoDecodeRequest("a.jpg", LocalPhotoTarget.THUMBNAIL),
        ) as LocalPhotoLoadResult.Ready<String>
        val targetMiss = loader.load(
            LocalPhotoDecodeRequest("a.jpg", LocalPhotoTarget.FULLSCREEN),
        ) as LocalPhotoLoadResult.Ready<String>
        val pathMiss = loader.load(
            LocalPhotoDecodeRequest("b.jpg", LocalPhotoTarget.THUMBNAIL),
        ) as LocalPhotoLoadResult.Ready<String>

        assertSame(first.value, second.value)
        assertSame(first.value, sameIdentityHit.value)
        // Every load inspects. Identical geometry hits skip decode.
        // a.jpg thumb is loaded three times and fullscreen once.
        assertEquals(4, source.inspectCountFor("a.jpg"))
        assertEquals(1, source.inspectCountFor("b.jpg"))
        assertEquals(2, source.decodeCountFor("a.jpg"))
        assertEquals(1, source.decodeCountFor("b.jpg"))
        assertNotEquals(first.plan.cacheKey, targetMiss.plan.cacheKey)
        assertNotEquals(first.plan.cacheKey, pathMiss.plan.cacheKey)
        assertEquals(LocalPhotoTarget.FULLSCREEN, targetMiss.plan.cacheKey.target)
        assertTrue(released.isEmpty())
        cache.unpin(first.plan.cacheKey)
        cache.unpin(second.plan.cacheKey)
        cache.unpin(sameIdentityHit.plan.cacheKey)
        cache.unpin(targetMiss.plan.cacheKey)
        cache.unpin(pathMiss.plan.cacheKey)
    }

    @Test
    fun `rewritten file stamp misses and does not reuse the previous bitmap`() =
        runBlocking {
            val source = MutatingInspectPhotoSource(
                sequence = listOf(
                    LocalPhotoFileStamp(200L, 1L) to
                        LocalPhotoSourceInfo(200, 100, LocalPhotoOrientation.NORMAL),
                    LocalPhotoFileStamp(200L, 2L) to
                        LocalPhotoSourceInfo(200, 100, LocalPhotoOrientation.ROTATE_90),
                    LocalPhotoFileStamp(400L, 3L) to
                        LocalPhotoSourceInfo(400, 200, LocalPhotoOrientation.ROTATE_90),
                ),
            )
            val released = mutableListOf<String>()
            val cache = LocalPhotoMemoryCache<String>(release = { released += it })
            val loader = BoundedLocalPhotoLoader(source, cache = cache)

            val baseline = loader.load(
                LocalPhotoDecodeRequest("mutate.jpg", LocalPhotoTarget.THUMBNAIL),
            ) as LocalPhotoLoadResult.Ready<String>
            assertEquals(1, source.decodeCount)
            assertEquals(1, source.inspectCount)
            assertEquals(1L, baseline.plan.cacheKey.lastModifiedEpochMillis)

            val sameStamp = loader.load(
                LocalPhotoDecodeRequest("mutate.jpg", LocalPhotoTarget.THUMBNAIL),
            ) as LocalPhotoLoadResult.Ready<String>
            assertSame(baseline.value, sameStamp.value)
            assertEquals(1, source.decodeCount)
            // A matching stamp still inspects; only decode is skipped.
            assertEquals(2, source.inspectCount)

            source.advance()
            val orientationMiss = loader.load(
                LocalPhotoDecodeRequest("mutate.jpg", LocalPhotoTarget.THUMBNAIL),
            ) as LocalPhotoLoadResult.Ready<String>
            assertEquals(2, source.decodeCount)
            assertEquals(3, source.inspectCount)
            assertEquals(2L, orientationMiss.plan.cacheKey.lastModifiedEpochMillis)
            assertNotEquals(baseline.plan.cacheKey, orientationMiss.plan.cacheKey)
            assertTrue(
                "a rewritten file must not reuse the prior decoded instance",
                baseline.value !== orientationMiss.value,
            )

            source.advance()
            val sizeMiss = loader.load(
                LocalPhotoDecodeRequest("mutate.jpg", LocalPhotoTarget.THUMBNAIL),
            ) as LocalPhotoLoadResult.Ready<String>
            assertEquals(3, source.decodeCount)
            assertEquals(4, source.inspectCount)
            assertEquals(400L, sizeMiss.plan.cacheKey.length)
            assertNotEquals(orientationMiss.plan.cacheKey, sizeMiss.plan.cacheKey)
            assertTrue(
                "a rewritten file must not reuse the prior decoded instance",
                orientationMiss.value !== sizeMiss.value,
            )
            assertTrue(released.isEmpty())

            cache.unpin(baseline.plan.cacheKey)
            cache.unpin(sameStamp.plan.cacheKey)
            cache.unpin(orientationMiss.plan.cacheKey)
            cache.unpin(sizeMiss.plan.cacheKey)
        }

    @Test
    fun `same file stamp misses on a new orientation and the same orientation decodes once`() =
        runBlocking {
            val stamp = LocalPhotoFileStamp(length = 200L, lastModifiedEpochMillis = 1L)
            val source = MutatingInspectPhotoSource(
                sequence = listOf(
                    stamp to LocalPhotoSourceInfo(200, 100, LocalPhotoOrientation.NORMAL),
                    stamp to LocalPhotoSourceInfo(200, 100, LocalPhotoOrientation.ROTATE_90),
                ),
            )
            val cache = LocalPhotoMemoryCache<String>(release = {})
            val loader = BoundedLocalPhotoLoader(source, cache = cache)
            val request = LocalPhotoDecodeRequest("mutate.jpg", LocalPhotoTarget.THUMBNAIL)

            val first = loader.load(request) as LocalPhotoLoadResult.Ready<String>
            val sameOrientation = loader.load(request) as LocalPhotoLoadResult.Ready<String>
            assertSame(first.value, sameOrientation.value)
            assertEquals(1, source.decodeCount)
            assertEquals(2, source.inspectCount)
            assertEquals(stamp.length, first.plan.cacheKey.length)
            assertEquals(stamp.lastModifiedEpochMillis, first.plan.cacheKey.lastModifiedEpochMillis)
            assertEquals(200, first.plan.cacheKey.width)
            assertEquals(100, first.plan.cacheKey.height)
            assertEquals(LocalPhotoOrientation.NORMAL, first.plan.cacheKey.orientation)

            source.advance()
            val rotated = loader.load(request) as LocalPhotoLoadResult.Ready<String>
            assertEquals(2, source.decodeCount)
            assertEquals(3, source.inspectCount)
            assertEquals(stamp.length, rotated.plan.cacheKey.length)
            assertEquals(
                stamp.lastModifiedEpochMillis,
                rotated.plan.cacheKey.lastModifiedEpochMillis,
            )
            assertEquals(LocalPhotoOrientation.ROTATE_90, rotated.plan.cacheKey.orientation)
            assertNotEquals(first.plan.cacheKey, rotated.plan.cacheKey)
            assertTrue(
                "a new inspect orientation must not reuse the prior decoded instance",
                first.value !== rotated.value,
            )

            cache.unpin(first.plan.cacheKey)
            cache.unpin(sameOrientation.plan.cacheKey)
            cache.unpin(rotated.plan.cacheKey)
        }

    @Test
    fun `pinned entries refuse eviction past entry and byte caps until unpin`() = runBlocking {
        val inspections = (0 until 6).associate { index ->
            "p$index.jpg" to LocalPhotoSourceInfo(64, 64, LocalPhotoOrientation.NORMAL)
        }
        val source = RecordingPhotoSource(inspections = inspections)
        val released = mutableListOf<String>()
        val oneThumbBytes = 64L * 64L * LocalPhotoCachePolicy.BYTES_PER_PIXEL
        val cache = LocalPhotoMemoryCache<String>(
            maxThumbnailEntries = 2,
            maxFullscreenEntries = 2,
            maxDecodedBytes = oneThumbBytes * 2,
            release = { released += it },
        )
        val loader = BoundedLocalPhotoLoader(source, cache = cache)

        val pinned = (0 until 3).map { index ->
            loader.load(
                LocalPhotoDecodeRequest("p$index.jpg", LocalPhotoTarget.THUMBNAIL),
            ) as LocalPhotoLoadResult.Ready<String>
        }
        // Soft cap under pin: three ready pins stay get-able and none released.
        assertTrue(released.isEmpty())
        assertEquals(3, cache.thumbnailEntryCount)
        assertTrue(cache.decodedByteSize > cache.maxDecodedBytes)
        for (ready in pinned) {
            val again = cache.get(ready.plan.cacheKey)
            assertSame(ready.value, requireNotNull(again).value)
            // get grants an extra pin; balance so only the original load pin remains.
            cache.unpin(ready.plan.cacheKey)
        }

        // After unpin, excess entries become eviction-eligible and release fires.
        for (ready in pinned) {
            cache.unpin(ready.plan.cacheKey)
        }
        assertTrue(released.isNotEmpty())
        assertTrue(cache.thumbnailEntryCount <= 2)
        assertTrue(cache.decodedByteSize <= cache.maxDecodedBytes)

        // Fullscreen soft cap under pin: three concurrent fullscreen pins exceed cap 2.
        val fullSource = RecordingPhotoSource(
            inspections = mapOf(
                "fp0.jpg" to LocalPhotoSourceInfo(100, 100, LocalPhotoOrientation.NORMAL),
                "fp1.jpg" to LocalPhotoSourceInfo(100, 100, LocalPhotoOrientation.NORMAL),
                "fp2.jpg" to LocalPhotoSourceInfo(100, 100, LocalPhotoOrientation.NORMAL),
            ),
        )
        val fullReleased = mutableListOf<String>()
        val fullCache = LocalPhotoMemoryCache<String>(
            maxThumbnailEntries = LocalPhotoCachePolicy.MAX_THUMBNAIL_ENTRIES,
            maxFullscreenEntries = LocalPhotoCachePolicy.MAX_FULLSCREEN_ENTRIES,
            maxDecodedBytes = LocalPhotoCachePolicy.MAX_DECODED_BYTES,
            release = { fullReleased += it },
        )
        val fullLoader = BoundedLocalPhotoLoader(fullSource, cache = fullCache)
        val fullPinned = (0 until 3).map { index ->
            fullLoader.load(
                LocalPhotoDecodeRequest("fp$index.jpg", LocalPhotoTarget.FULLSCREEN),
            ) as LocalPhotoLoadResult.Ready<String>
        }
        assertTrue(fullReleased.isEmpty())
        assertEquals(3, fullCache.fullscreenEntryCount)
        assertTrue(fullCache.fullscreenEntryCount > LocalPhotoCachePolicy.MAX_FULLSCREEN_ENTRIES)
        for (ready in fullPinned) {
            assertSame(ready.value, requireNotNull(fullCache.get(ready.plan.cacheKey)).value)
            fullCache.unpin(ready.plan.cacheKey)
            fullCache.unpin(ready.plan.cacheKey)
        }
        assertTrue(fullReleased.isNotEmpty())
        assertEquals(LocalPhotoCachePolicy.MAX_FULLSCREEN_ENTRIES, fullCache.fullscreenEntryCount)
    }

    @Test
    fun `entry-cap overage prefers same-target unpinned victims over older other target`() =
        runBlocking {
            val released = mutableListOf<String>()
            val cache = LocalPhotoMemoryCache<String>(
                maxThumbnailEntries = 2,
                maxFullscreenEntries = 1,
                maxDecodedBytes = 32L * 1024 * 1024,
                release = { released += it },
            )
            val source = RecordingPhotoSource(
                inspections = mapOf(
                    "old-thumb.jpg" to LocalPhotoSourceInfo(64, 64, LocalPhotoOrientation.NORMAL),
                    "f0.jpg" to LocalPhotoSourceInfo(100, 100, LocalPhotoOrientation.NORMAL),
                    "f1.jpg" to LocalPhotoSourceInfo(100, 100, LocalPhotoOrientation.NORMAL),
                    "t0.jpg" to LocalPhotoSourceInfo(64, 64, LocalPhotoOrientation.NORMAL),
                    "t1.jpg" to LocalPhotoSourceInfo(64, 64, LocalPhotoOrientation.NORMAL),
                    "t2.jpg" to LocalPhotoSourceInfo(64, 64, LocalPhotoOrientation.NORMAL),
                ),
            )
            val loader = BoundedLocalPhotoLoader(source, cache = cache)

            val oldThumb = loader.load(
                LocalPhotoDecodeRequest("old-thumb.jpg", LocalPhotoTarget.THUMBNAIL),
            ) as LocalPhotoLoadResult.Ready<String>
            cache.unpin(oldThumb.plan.cacheKey)

            val full0 = loader.load(
                LocalPhotoDecodeRequest("f0.jpg", LocalPhotoTarget.FULLSCREEN),
            ) as LocalPhotoLoadResult.Ready<String>
            cache.unpin(full0.plan.cacheKey)

            // Fullscreen cap 1: second fullscreen must evict the unpinned fullscreen peer,
            // not the older unpinned thumbnail.
            val full1 = loader.load(
                LocalPhotoDecodeRequest("f1.jpg", LocalPhotoTarget.FULLSCREEN),
            ) as LocalPhotoLoadResult.Ready<String>
            cache.unpin(full1.plan.cacheKey)
            assertTrue(released.any { it.startsWith("f0.jpg@") })
            assertTrue(released.none { it.startsWith("old-thumb.jpg@") })
            assertSame(
                oldThumb.value,
                requireNotNull(cache.get(oldThumb.plan.cacheKey)).value,
            )
            cache.unpin(oldThumb.plan.cacheKey)

            // Thumbnail cap 2 with one thumb already present: third unpinned thumb
            // evicts eldest unpinned thumb, not the remaining fullscreen.
            val t0 = loader.load(
                LocalPhotoDecodeRequest("t0.jpg", LocalPhotoTarget.THUMBNAIL),
            ) as LocalPhotoLoadResult.Ready<String>
            cache.unpin(t0.plan.cacheKey)
            val t1 = loader.load(
                LocalPhotoDecodeRequest("t1.jpg", LocalPhotoTarget.THUMBNAIL),
            ) as LocalPhotoLoadResult.Ready<String>
            cache.unpin(t1.plan.cacheKey)
            // Now 3 thumbs (old-thumb, t0, t1) → over thumb cap; eldest thumb released.
            assertTrue(released.any { it.startsWith("old-thumb.jpg@") })
            assertEquals(1, cache.fullscreenEntryCount)
            assertSame(
                full1.value,
                requireNotNull(cache.get(full1.plan.cacheKey)).value,
            )
            cache.unpin(full1.plan.cacheKey)
            cache.unpin(t0.plan.cacheKey)
            cache.unpin(t1.plan.cacheKey)
        }

    @Test
    fun `thumbnail entry and byte budgets evict least-recent unpinned values`() = runBlocking {
        val inspections = (0 until 30).associate { index ->
            "t$index.jpg" to LocalPhotoSourceInfo(64, 64, LocalPhotoOrientation.NORMAL)
        }
        val source = RecordingPhotoSource(inspections = inspections)
        val released = mutableListOf<String>()
        val cache = LocalPhotoMemoryCache<String>(
            maxThumbnailEntries = 3,
            maxFullscreenEntries = 2,
            maxDecodedBytes = 64L * 64L * LocalPhotoCachePolicy.BYTES_PER_PIXEL * 3,
            release = { released += it },
        )
        val loader = BoundedLocalPhotoLoader(source, cache = cache)

        val keys = mutableListOf<LocalPhotoCacheKey>()
        for (index in 0 until 4) {
            val ready = loader.load(
                LocalPhotoDecodeRequest("t$index.jpg", LocalPhotoTarget.THUMBNAIL),
            ) as LocalPhotoLoadResult.Ready<String>
            keys += ready.plan.cacheKey
            cache.unpin(ready.plan.cacheKey)
        }
        assertEquals(4, keys.size)

        // Entry cap 3: loading the 4th unpinned thumbnail evicts the least-recent (t0).
        assertTrue(released.any { it.startsWith("t0.jpg@") })
        assertEquals(3, cache.thumbnailEntryCount)
        assertTrue(cache.decodedByteSize <= cache.maxDecodedBytes)

        // Fullscreen cap 2 with explicit tiny byte room still recycles on third.
        val fullSource = RecordingPhotoSource(
            inspections = mapOf(
                "f0.jpg" to LocalPhotoSourceInfo(100, 100, LocalPhotoOrientation.NORMAL),
                "f1.jpg" to LocalPhotoSourceInfo(100, 100, LocalPhotoOrientation.NORMAL),
                "f2.jpg" to LocalPhotoSourceInfo(100, 100, LocalPhotoOrientation.NORMAL),
            ),
        )
        val fullReleased = mutableListOf<String>()
        val fullCache = LocalPhotoMemoryCache<String>(
            maxThumbnailEntries = 24,
            maxFullscreenEntries = 2,
            maxDecodedBytes = 32L * 1024 * 1024,
            release = { fullReleased += it },
        )
        val fullLoader = BoundedLocalPhotoLoader(fullSource, cache = fullCache)
        val fullKeys = (0 until 3).map { index ->
            val ready = fullLoader.load(
                LocalPhotoDecodeRequest("f$index.jpg", LocalPhotoTarget.FULLSCREEN),
            ) as LocalPhotoLoadResult.Ready<String>
            fullCache.unpin(ready.plan.cacheKey)
            ready.plan.cacheKey
        }
        assertTrue(fullReleased.any { it.startsWith("f0.jpg@") })
        assertEquals(2, fullCache.fullscreenEntryCount)
        assertEquals(3, fullKeys.size)

        // Pure byte budget (entry cap high): third 64×64 ARGB thumb forces LRU release.
        val byteSource = RecordingPhotoSource(
            inspections = mapOf(
                "b0.jpg" to LocalPhotoSourceInfo(64, 64, LocalPhotoOrientation.NORMAL),
                "b1.jpg" to LocalPhotoSourceInfo(64, 64, LocalPhotoOrientation.NORMAL),
                "b2.jpg" to LocalPhotoSourceInfo(64, 64, LocalPhotoOrientation.NORMAL),
            ),
        )
        val byteReleased = mutableListOf<String>()
        val oneThumbBytes = 64L * 64L * LocalPhotoCachePolicy.BYTES_PER_PIXEL
        val byteCache = LocalPhotoMemoryCache<String>(
            maxThumbnailEntries = LocalPhotoCachePolicy.MAX_THUMBNAIL_ENTRIES,
            maxFullscreenEntries = LocalPhotoCachePolicy.MAX_FULLSCREEN_ENTRIES,
            maxDecodedBytes = oneThumbBytes * 2,
            release = { byteReleased += it },
        )
        val byteLoader = BoundedLocalPhotoLoader(byteSource, cache = byteCache)
        repeat(3) { index ->
            val ready = byteLoader.load(
                LocalPhotoDecodeRequest("b$index.jpg", LocalPhotoTarget.THUMBNAIL),
            ) as LocalPhotoLoadResult.Ready<String>
            byteCache.unpin(ready.plan.cacheKey)
        }
        assertTrue(byteReleased.any { it.startsWith("b0.jpg@") })
        assertTrue(byteCache.decodedByteSize <= oneThumbBytes * 2)
        assertEquals(2, byteCache.thumbnailEntryCount)
    }

    @Test
    fun `cancelled decode does not populate cache and does not poison a later load`() = runBlocking {
        val source = SwitchingPhotoSource()
        val cache = LocalPhotoMemoryCache<String>(release = { })
        val loader = BoundedLocalPhotoLoader(source, cache = cache)

        val stale = async {
            loader.load(LocalPhotoDecodeRequest("a.jpg", LocalPhotoTarget.FULLSCREEN))
        }
        source.staleOrientationStarted.await()
        stale.cancelAndJoin()

        assertTrue(stale.isCancelled)
        assertEquals(0, cache.totalEntryCount)
        assertEquals(listOf("a.jpg@1"), source.released)

        val ready = loader.load(
            LocalPhotoDecodeRequest("b.jpg", LocalPhotoTarget.FULLSCREEN),
        ) as LocalPhotoLoadResult.Ready<String>
        assertEquals("b.jpg@1", ready.value)
        assertEquals(1, cache.totalEntryCount)
        cache.unpin(ready.plan.cacheKey)
    }
}

private class RecordingPhotoSource(
    private val inspections: Map<String, LocalPhotoSourceInfo>,
) : LocalPhotoDecodeSource<String> {
    val requestedSampleSizes = mutableListOf<Int>()
    val appliedOrientations = mutableListOf<LocalPhotoOrientation>()
    private val decodeCounts = mutableMapOf<String, Int>()
    private val inspectCounts = mutableMapOf<String, Int>()

    fun decodeCountFor(path: String): Int = decodeCounts[path] ?: 0

    fun inspectCountFor(path: String): Int = inspectCounts[path] ?: 0

    override suspend fun inspect(path: String): LocalPhotoSourceInfo? {
        inspectCounts[path] = (inspectCounts[path] ?: 0) + 1
        return inspections[path]
    }

    override suspend fun decode(path: String, sampleSize: Int): LocalPhotoDecoded<String>? {
        requestedSampleSizes += sampleSize
        decodeCounts[path] = (decodeCounts[path] ?: 0) + 1
        val info = inspections[path] ?: return null
        // Distinct instance per decode so identity-miss tests can assert !== reuse.
        return LocalPhotoDecoded(
            value = "$path@$sampleSize#${decodeCounts[path]}",
            width = (info.width + sampleSize - 1) / sampleSize,
            height = (info.height + sampleSize - 1) / sampleSize,
        )
    }

    override suspend fun applyOrientation(
        decoded: LocalPhotoDecoded<String>,
        orientation: LocalPhotoOrientation,
    ): LocalPhotoDecoded<String> {
        appliedOrientations += orientation
        return if (orientation.swapsAxes) {
            decoded.copy(width = decoded.height, height = decoded.width)
        } else {
            decoded
        }
    }

    override fun release(decoded: String) = Unit
}

/**
 * One path with a versioned stamp and inspect pair. [advance] publishes the next pair.
 * A stamp match is not required for an orientation or size miss.
 */
private class MutatingInspectPhotoSource(
    private val sequence: List<Pair<LocalPhotoFileStamp, LocalPhotoSourceInfo>>,
) : LocalPhotoDecodeSource<String> {
    private var version = 0
    private var lastInspected: LocalPhotoSourceInfo = sequence.first().second
    var decodeCount = 0
        private set
    var inspectCount = 0
        private set

    fun advance() {
        if (version < sequence.lastIndex) version += 1
    }

    override suspend fun fileStamp(path: String): LocalPhotoFileStamp =
        sequence[version.coerceAtMost(sequence.lastIndex)].first

    override suspend fun inspect(path: String): LocalPhotoSourceInfo {
        inspectCount += 1
        val info = sequence[version.coerceAtMost(sequence.lastIndex)].second
        lastInspected = info
        return info
    }

    override suspend fun decode(path: String, sampleSize: Int): LocalPhotoDecoded<String> {
        decodeCount += 1
        val info = lastInspected
        return LocalPhotoDecoded(
            value = "$path@$sampleSize#decode$decodeCount",
            width = (info.width + sampleSize - 1) / sampleSize,
            height = (info.height + sampleSize - 1) / sampleSize,
        )
    }

    override suspend fun applyOrientation(
        decoded: LocalPhotoDecoded<String>,
        orientation: LocalPhotoOrientation,
    ): LocalPhotoDecoded<String> = if (orientation.swapsAxes) {
        decoded.copy(width = decoded.height, height = decoded.width)
    } else {
        decoded
    }

    override fun release(decoded: String) = Unit
}

private class BoundaryPhotoSource(
    private val inspectBlock: suspend (String) -> LocalPhotoSourceInfo? = {
        LocalPhotoSourceInfo(100, 100, LocalPhotoOrientation.NORMAL)
    },
    private val decodeBlock: suspend (String, Int) -> String? = { path, sample -> "$path@$sample" },
    private val orientationBlock: suspend (String, LocalPhotoOrientation) -> String =
        { decoded, _ -> decoded },
) : LocalPhotoDecodeSource<String> {
    var decodeCount = 0

    override suspend fun inspect(path: String): LocalPhotoSourceInfo? = inspectBlock(path)

    override suspend fun decode(path: String, sampleSize: Int): LocalPhotoDecoded<String>? {
        decodeCount += 1
        return decodeBlock(path, sampleSize)?.let { value ->
            LocalPhotoDecoded(value = value, width = 100, height = 100)
        }
    }

    override suspend fun applyOrientation(
        decoded: LocalPhotoDecoded<String>,
        orientation: LocalPhotoOrientation,
    ): LocalPhotoDecoded<String> = decoded.copy(
        value = orientationBlock(decoded.value, orientation),
        width = if (orientation.swapsAxes) decoded.height else decoded.width,
        height = if (orientation.swapsAxes) decoded.width else decoded.height,
    )

    override fun release(decoded: String) = Unit
}

private class SwitchingPhotoSource : LocalPhotoDecodeSource<String> {
    val staleOrientationStarted = CompletableDeferred<Unit>()
    val released = mutableListOf<String>()

    override suspend fun inspect(path: String): LocalPhotoSourceInfo =
        LocalPhotoSourceInfo(100, 100, LocalPhotoOrientation.NORMAL)

    override suspend fun decode(
        path: String,
        sampleSize: Int,
    ): LocalPhotoDecoded<String> = LocalPhotoDecoded("$path@$sampleSize", 100, 100)

    override suspend fun applyOrientation(
        decoded: LocalPhotoDecoded<String>,
        orientation: LocalPhotoOrientation,
    ): LocalPhotoDecoded<String> {
        if (decoded.value.startsWith("a.jpg")) {
            staleOrientationStarted.complete(Unit)
            awaitCancellation()
        }
        return decoded
    }

    override fun release(decoded: String) {
        released += decoded
    }
}

private class OversizedDecodedPhotoSource : LocalPhotoDecodeSource<String> {
    val released = mutableListOf<String>()

    override suspend fun inspect(path: String): LocalPhotoSourceInfo =
        LocalPhotoSourceInfo(100, 100, LocalPhotoOrientation.ROTATE_90)

    override suspend fun decode(
        path: String,
        sampleSize: Int,
    ): LocalPhotoDecoded<String> = LocalPhotoDecoded(
        value = "oversized-bitmap",
        width = 4_096,
        height = 4_096,
    )

    override suspend fun applyOrientation(
        decoded: LocalPhotoDecoded<String>,
        orientation: LocalPhotoOrientation,
    ): LocalPhotoDecoded<String> = decoded.copy(
        width = decoded.height,
        height = decoded.width,
    )

    override fun release(decoded: String) {
        released += decoded
    }
}
