package com.lezi.babylog.core.common.cancellation

import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException

/**
 * Returns the first structured-cancellation signal in this throwable's cause chain.
 *
 * Traversal is identity-bounded. A broken cause accessor ends inspection without
 * replacing the original business failure that callers still own.
 */
fun Throwable.cancellationCauseOrNull(): CancellationException? {
    val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = this
    while (current != null && visited.add(current)) {
        if (current is CancellationException) return current
        current = try {
            current.cause
        } catch (_: Throwable) {
            null
        }
    }
    return null
}
