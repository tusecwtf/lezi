package com.lezi.babylog.validation

/**
 * Opt-in instrumentation seam at the real environment's I/O boundary. It may delay inspection,
 * but cannot supply an inspection result, mark data Ready, replace DI, or bypass verification.
 * The ordinary debug app never installs a callback. No corresponding callback exists in release.
 */
internal object LocalDataInspectionControl {
    @Volatile var beforeInspect: (suspend () -> Unit)? = null

    suspend fun awaitInspection() {
        beforeInspect?.invoke()
    }
}
