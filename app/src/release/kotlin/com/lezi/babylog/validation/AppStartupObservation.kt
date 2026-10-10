package com.lezi.babylog.validation

/** The release variant retains no startup observation state or diagnostic endpoint. */
internal object AppStartupObservation {
    @Suppress("UNUSED_PARAMETER")
    fun record(boundary: String) = Unit
}
