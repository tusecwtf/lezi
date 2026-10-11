package com.lezi.babylog.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPhotoCacheSnapshotTest {
    @Test
    fun emptyCacheHasNoEntriesPinsOrDecodedBytes() {
        val cache = LocalPhotoMemoryCache<String>(release = {})

        assertEquals(LocalPhotoCacheSnapshot(0, 0, 0, 0L), cache.snapshot())
    }

    @Test
    fun repeatedConsumersCountAsPinsOfOneEntryAndPriorSnapshotStaysUnchanged() {
        val cache = LocalPhotoMemoryCache<String>(release = {})
        val thumbnail = plan("thumbnail", LocalPhotoTarget.THUMBNAIL, edge = 2)
        val fullscreen = plan("fullscreen", LocalPhotoTarget.FULLSCREEN)
        cache.put("thumbnail", thumbnail)
        cache.put("fullscreen", fullscreen)
        val beforeAnotherConsumer = cache.snapshot()

        assertSame("fullscreen", requireNotNull(cache.get(fullscreen.cacheKey)).value)
        assertEquals(LocalPhotoCacheSnapshot(2, 2, 3, 80L), cache.snapshot())
        assertEquals(LocalPhotoCacheSnapshot(2, 2, 2, 80L), beforeAnotherConsumer)

        cache.unpin(fullscreen.cacheKey)
        cache.unpin(thumbnail.cacheKey)
        assertEquals(LocalPhotoCacheSnapshot(2, 1, 1, 80L), cache.snapshot())
        cache.unpin(fullscreen.cacheKey)
        assertEquals(LocalPhotoCacheSnapshot(2, 0, 0, 80L), cache.snapshot())
    }

    @Test
    fun observingPinnedOverflowPreservesValuesUntilTheirConsumersUnpin() {
        val released = mutableListOf<String>()
        val cache = LocalPhotoMemoryCache<String>(
            maxFullscreenEntries = 2,
            maxDecodedBytes = 128L,
            release = { released += it },
        )
        val plans = (0 until 3).map { plan("fullscreen-$it", LocalPhotoTarget.FULLSCREEN) }
        plans.forEachIndexed { index, plan -> cache.put("value-$index", plan) }

        repeat(3) {
            assertEquals(LocalPhotoCacheSnapshot(3, 3, 3, 192L), cache.snapshot())
        }
        assertTrue("Observation must not recycle a still displayed value", released.isEmpty())

        cache.unpin(plans[0].cacheKey)
        assertEquals(listOf("value-0"), released)
        assertNull(cache.get(plans[0].cacheKey))
        assertEquals(LocalPhotoCacheSnapshot(2, 2, 2, 128L), cache.snapshot())

        cache.unpin(plans[1].cacheKey)
        cache.unpin(plans[2].cacheKey)
        assertEquals(LocalPhotoCacheSnapshot(2, 0, 0, 128L), cache.snapshot())
        assertEquals(listOf("value-0"), released)
    }

    @Test
    fun observingUnpinnedCacheDoesNotRetainEntriesOrChangeEvictionOrder() {
        val released = mutableListOf<String>()
        val cache = LocalPhotoMemoryCache<String>(
            maxFullscreenEntries = 2,
            release = { released += it },
        )
        val oldest = plan("oldest", LocalPhotoTarget.FULLSCREEN)
        val newer = plan("newer", LocalPhotoTarget.FULLSCREEN)
        cache.put("oldest", oldest)
        cache.put("newer", newer)
        cache.unpin(oldest.cacheKey)
        cache.unpin(newer.cacheKey)

        repeat(3) {
            assertEquals(LocalPhotoCacheSnapshot(2, 0, 0, 128L), cache.snapshot())
        }
        assertTrue(released.isEmpty())
        cache.put("newest", plan("newest", LocalPhotoTarget.FULLSCREEN))

        assertEquals(listOf("oldest"), released)
        assertNull(cache.get(oldest.cacheKey))
        assertEquals(LocalPhotoCacheSnapshot(2, 1, 1, 128L), cache.snapshot())
    }

    private fun plan(
        path: String,
        target: LocalPhotoTarget,
        edge: Int = 4,
    ): LocalPhotoDecodePlan = LocalPhotoDecodePlan(
        sampleSize = 1,
        decodedWidth = edge,
        decodedHeight = edge,
        decodedPixels = edge.toLong() * edge,
        cacheKey = LocalPhotoCacheKey(
            path = path,
            target = target,
            width = edge,
            height = edge,
            orientation = LocalPhotoOrientation.NORMAL,
            length = 1L,
            lastModifiedEpochMillis = 0L,
        ),
    )
}
