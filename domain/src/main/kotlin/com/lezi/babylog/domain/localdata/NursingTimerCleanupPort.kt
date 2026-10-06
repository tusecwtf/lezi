package com.lezi.babylog.domain.localdata
/**
 * Stops the device-local nursing timer foreground service after a durable local
 * clear commits.
 *
 * The Android adapter owns FGS / notification details. Callers only pass the
 * session token captured under exclusion so a newer post-commit session is not
 * stopped (ABA protection).
 */
fun interface NursingTimerCleanupPort {
    /**
     * Idempotently stop the nursing timer FGS when its active session **exactly**
     * matches [sessionToken].
     *
     * No-op when [sessionToken] is null/blank or a different STARTING/RUNNING
     * session owns the process witness. The Android adapter retains a
     * token-scoped pending stop until a captured STARTING service materializes.
     */
    fun stopCapturedSession(sessionToken: String?)
}
