package com.lezi.babylog.sync.qr

import java.time.Clock

/** Typed decision for untrusted member-login QR text. */
sealed interface MemberLoginQrScanOutcome {
    data object Empty : MemberLoginQrScanOutcome

    data class Rejected(val reason: MemberLoginQrRejection) : MemberLoginQrScanOutcome

    data class Ready(val payload: MemberLoginQrPayload) : MemberLoginQrScanOutcome
}

enum class MemberLoginQrRejection {
    Unavailable,
    Expired,
}

/**
 * Single owner for member-login QR decoding and expiry semantics.
 *
 * Expiry is inclusive: a QR is unavailable at the exact `expires_at` instant.
 */
class MemberLoginQrScanPolicy(
    private val clock: Clock,
) {
    fun evaluate(raw: String): MemberLoginQrScanOutcome {
        val content = raw.trim()
        if (content.isEmpty()) return MemberLoginQrScanOutcome.Empty
        val payload = runCatching { MemberLoginQrContentCodec.decode(content).payload }
            .getOrNull()
            ?: return MemberLoginQrScanOutcome.Rejected(MemberLoginQrRejection.Unavailable)
        if (clock.instant().epochSecond >= payload.expiresAtEpochSeconds) {
            return MemberLoginQrScanOutcome.Rejected(MemberLoginQrRejection.Expired)
        }
        return MemberLoginQrScanOutcome.Ready(payload)
    }
}
