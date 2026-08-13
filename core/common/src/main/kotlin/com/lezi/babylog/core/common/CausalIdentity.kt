package com.lezi.babylog.core.common

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/**
 * Frozen wire §11 namespace for historical closed-sleep → WakeObservation IDs.
 * Must match lezi-sync `WAKE_MIGRATION_NAMESPACE`.
 */
val WAKE_MIGRATION_NAMESPACE: UUID =
    UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7")

/**
 * Frozen namespace for migration base `version_id` values (server offline only;
 * kept here so Android can verify the same formula when needed).
 */
val VERSION_MIGRATION_NAMESPACE: UUID =
    UUID.fromString("3d4f8a21-6b9c-4e11-9f2a-0cd4e87155b0")

/** RFC 4122 UUIDv5 (SHA-1 name-based). */
fun uuidV5(namespace: UUID, name: String): String =
    uuidV5(namespace, name.toByteArray(Charsets.UTF_8))

/** RFC 4122 UUIDv5 (SHA-1 name-based) over raw name bytes. */
fun uuidV5(namespace: UUID, name: ByteArray): String {
    val md = MessageDigest.getInstance("SHA-1")
    val nsBytes = ByteBuffer.allocate(16)
        .putLong(namespace.mostSignificantBits)
        .putLong(namespace.leastSignificantBits)
        .array()
    md.update(nsBytes)
    md.update(name)
    val digest = md.digest()
    digest[6] = ((digest[6].toInt() and 0x0f) or 0x50).toByte() // version 5
    digest[8] = ((digest[8].toInt() and 0x3f) or 0x80).toByte() // IETF variant
    val msb = ByteBuffer.wrap(digest, 0, 8).long
    val lsb = ByteBuffer.wrap(digest, 8, 8).long
    return UUID(msb, lsb).toString()
}

/**
 * Deterministic WakeObservation `client_uuid` from a legacy closed sleep row (wire §11).
 *
 * NAME = "wake_obs_v1:" + sleep_client_uuid + ":" + decimal(legacy_updated_at)
 */
fun wakeObservationClientUuid(sleepClientUuid: String, legacyUpdatedAt: Long): String =
    uuidV5(WAKE_MIGRATION_NAMESPACE, "wake_obs_v1:$sleepClientUuid:$legacyUpdatedAt")

/**
 * Deterministic wake media `media_uuid` from legacy sleep log media (wire §11).
 *
 * NAME = "wake_media_v1:" + sleep + ":" + legacy_media_uuid + ":" + sha256_hex + ":wake"
 */
fun wakeMediaUuid(
    sleepClientUuid: String,
    legacyMediaUuid: String,
    lowercaseHexSha256: String,
): String =
    uuidV5(
        WAKE_MIGRATION_NAMESPACE,
        "wake_media_v1:$sleepClientUuid:$legacyMediaUuid:$lowercaseHexSha256:wake",
    )
