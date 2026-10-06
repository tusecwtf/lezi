package com.lezi.babylog.core.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Eviction contract behind the process-level decoded-avatar cache: same key
 * returns the memoized instance, distinct keys (mtime/length change) decode
 * independently, and insertion order (not last access) decides eviction.
 */
class BoundedMemoCacheTest {

    @Test
    fun sameKeyReturnsMemoizedInstance() {
        val cache = BoundedMemoCache<String, Any>(capacity = 2)
        val first = Any()

        cache.put("a", first)

        assertThat(cache.get("a")).isSameInstanceAs(first)
        assertThat(cache.get("a")).isSameInstanceAs(first)
    }

    @Test
    fun replacementKeyDecodesIndependentlyOfTheOriginalEntry() {
        val cache = BoundedMemoCache<String, Any>(capacity = 2)
        val original = Any()
        val replaced = Any()

        cache.put("avatar|100|10", original)
        cache.put("avatar|200|10", replaced)

        assertThat(cache.get("avatar|100|10")).isSameInstanceAs(original)
        assertThat(cache.get("avatar|200|10")).isSameInstanceAs(replaced)
    }

    @Test
    fun capacityEvictsOldestInsertionFirst() {
        val cache = BoundedMemoCache<String, Any>(capacity = 2)
        val first = Any()
        val second = Any()
        val third = Any()

        cache.put("1", first)
        cache.put("2", second)
        cache.put("3", third)

        assertThat(cache.get("1")).isNull()
        assertThat(cache.get("2")).isSameInstanceAs(second)
        assertThat(cache.get("3")).isSameInstanceAs(third)
    }

    @Test
    fun reinsertingAnExistingKeyKeepsItsOriginalEvictionPosition() {
        val cache = BoundedMemoCache<String, Any>(capacity = 2)
        val refreshed = Any()
        val second = Any()

        cache.put("1", Any())
        cache.put("2", second)
        cache.put("1", refreshed)
        cache.put("3", Any())

        // Insertion order is stable: "1" stays the oldest insertion, so the
        // capacity overflow evicts it even though it was just re-put.
        assertThat(cache.get("1")).isNull()
        assertThat(cache.get("2")).isSameInstanceAs(second)
    }
}
