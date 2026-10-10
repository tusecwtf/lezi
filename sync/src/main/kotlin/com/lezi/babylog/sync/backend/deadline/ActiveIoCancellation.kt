package com.lezi.babylog.sync.backend.deadline

import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job

/** Close blocking platform I/O when cancellation starts, not after its body completes. */
@OptIn(InternalCoroutinesApi::class)
internal fun Job.cancelActiveIo(close: () -> Unit): DisposableHandle =
    invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
        if (cause != null) runCatching(close)
    }
