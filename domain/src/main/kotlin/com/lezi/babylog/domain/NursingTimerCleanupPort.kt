package com.lezi.babylog.domain

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
     * Idempotently stop the nursing timer FGS when its active session matches
     * [sessionToken]. No-op when [sessionToken] is null or a different session
     * is already active. Throws when the captured session still appears active
     * after a stop attempt so committed cleanup can be retried.
     */
    fun stopCapturedSession(sessionToken: String?)
}
