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
            assertEquals(LocalPhotoOrientation.ROTATE_90, result.plan.cacheKey.orientation)
            assertNotEquals(
                result.plan.cacheKey,
                result.plan.cacheKey.copy(orientation = LocalPhotoOrientation.NORMAL),
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
    fun `cache hit reuses decoded value and identity miss re-decodes`() = runBlocking {
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
        val orientationMiss = loader.load(
            LocalPhotoDecodeRequest("a.jpg", LocalPhotoTarget.THUMBNAIL),
        )
        // Force identity miss by loading a path whose plan key differs via target.
        val targetMiss = loader.load(
            LocalPhotoDecodeRequest("a.jpg", LocalPhotoTarget.FULLSCREEN),
        ) as LocalPhotoLoadResult.Ready<String>
        val pathMiss = loader.load(
            LocalPhotoDecodeRequest("b.jpg", LocalPhotoTarget.THUMBNAIL),
        ) as LocalPhotoLoadResult.Ready<String>

        assertSame(first.value, second.value)
        assertSame(
            first.value,
            (orientationMiss as LocalPhotoLoadResult.Ready<String>).value,
        )
        // Same path is decoded once per target identity (thumb hit + fullscreen miss).
        assertEquals(2, source.decodeCountFor("a.jpg"))
        assertEquals(1, source.decodeCountFor("b.jpg"))
        assertNotEquals(first.plan.cacheKey, targetMiss.plan.cacheKey)
        assertNotEquals(first.plan.cacheKey, pathMiss.plan.cacheKey)
        assertEquals(LocalPhotoTarget.FULLSCREEN, targetMiss.plan.cacheKey.target)
        // orientation identity is part of the key even when value would otherwise match
        assertNotEquals(
            first.plan.cacheKey,
            first.plan.cacheKey.copy(orientation = LocalPhotoOrientation.ROTATE_90),
        )
        assertTrue(released.isEmpty())
        cache.unpin(first.plan.cacheKey)
        cache.unpin(second.plan.cacheKey)
        cache.unpin(orientationMiss.plan.cacheKey)
        cache.unpin(targetMiss.plan.cacheKey)
        cache.unpin(pathMiss.plan.cacheKey)
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

    fun decodeCountFor(path: String): Int = decodeCounts[path] ?: 0

    override suspend fun inspect(path: String): LocalPhotoSourceInfo? = inspections[path]

    override suspend fun decode(path: String, sampleSize: Int): LocalPhotoDecoded<String>? {
        requestedSampleSizes += sampleSize
        decodeCounts[path] = (decodeCounts[path] ?: 0) + 1
        val info = inspections[path] ?: return null
        return LocalPhotoDecoded(
            value = "$path@$sampleSize",
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
