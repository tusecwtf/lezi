package com.lezi.babylog.sync.session

import java.util.UUID

private const val RECEIPT_PREFIX = "lezi-sync:"

/**
 * Deterministic local receipt URI for a media clientUuid under this family
 * session. Used by atomic media publish and pull ack paths so commit success is
 * durable without a second remote media identity.
 */
internal fun SyncSession.receiptFor(clientUuid: String): String {
    val namespace = UUID.nameUUIDFromBytes(
        "${baseUrl.trimEnd('/')}\n$familyId".toByteArray(Charsets.UTF_8),
    )
    return "$RECEIPT_PREFIX$namespace:$clientUuid"
}
