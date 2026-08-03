package com.lezi.babylog.core.common

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A tiny, lifecycle-agnostic gate for user actions that must not overlap.
 *
 * [run] returns false when another invocation already owns the gate. The busy
 * state is always released, including when the action fails or is cancelled.
 */
class SingleFlightAction {
    private val mutableBusy = MutableStateFlow(false)

    val busy: StateFlow<Boolean> = mutableBusy.asStateFlow()

    suspend fun run(action: suspend () -> Unit): Boolean {
        if (!mutableBusy.compareAndSet(expect = false, update = true)) return false
        return try {
            action()
            true
        } finally {
            mutableBusy.value = false
        }
    }
}
