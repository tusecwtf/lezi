package com.lezi.babylog.core.common.validation

import kotlinx.coroutines.flow.Flow

/** Release has no counters, snapshots, callbacks, or instrumentation control. */
object StartupBoundaryObservation {
    @Suppress("UNUSED_PARAMETER")
    fun record(boundary: String) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun <T> observeCollection(flow: Flow<T>, boundary: String): Flow<T> = flow
}
