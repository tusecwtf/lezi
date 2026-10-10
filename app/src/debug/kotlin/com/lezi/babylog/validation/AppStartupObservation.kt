package com.lezi.babylog.validation

/** Debug-only, bounded observation of production startup boundaries. Never controls startup. */
internal object AppStartupObservation {
    private val markers = linkedSetOf<String>()

    @Synchronized fun record(boundary: String) { markers += boundary }
    @Synchronized fun snapshot(): List<String> = markers.toList()
}
