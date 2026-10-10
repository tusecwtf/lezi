package com.lezi.babylog.core.common.validation

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onStart

/** Debug-only counts, with no dependency resolution, payloads, or startup control. */
object StartupBoundaryObservation {
    private val counts = linkedMapOf<String, Int>()

    @Synchronized fun record(boundary: String) {
        counts[boundary] = (counts[boundary] ?: 0) + 1
    }

    @Synchronized fun snapshot(): Map<String, Int> = counts.toMap()

    fun <T> observeCollection(flow: Flow<T>, boundary: String): Flow<T> =
        flow.onStart { record(boundary) }
}
