package com.lezi.babylog.sync.backend.deadline

import com.lezi.babylog.core.common.deadline.RealtimeDeadline

/**
 * Real-time elapsed disconnect. Delegates to [RealtimeDeadline] so virtual
 * `runTest` time cannot fire while [Dispatchers.IO] does real I/O.
 */
internal class FamilyHttpDisconnectWatchdog(
    timeoutMillis: Long,
    threadName: String = "lezi-family-http-deadline",
    disconnect: () -> Unit,
) : AutoCloseable {
    private val deadline = RealtimeDeadline(timeoutMillis, threadName, disconnect)

    override fun close() = deadline.close()
}
