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
}

private class RecordingPhotoSource(
    private val inspections: Map<String, LocalPhotoSourceInfo>,
) : LocalPhotoDecodeSource<String> {
    val requestedSampleSizes = mutableListOf<Int>()
    val appliedOrientations = mutableListOf<LocalPhotoOrientation>()

    override suspend fun inspect(path: String): LocalPhotoSourceInfo? = inspections[path]

    override suspend fun decode(path: String, sampleSize: Int): LocalPhotoDecoded<String>? {
        requestedSampleSizes += sampleSize
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
