package com.lezi.babylog.domain.localdata

/** Clears device-local launcher widget bindings and cached family summaries. */
interface WidgetCleanupPort {
    suspend fun clearAllWidgetState()
}
