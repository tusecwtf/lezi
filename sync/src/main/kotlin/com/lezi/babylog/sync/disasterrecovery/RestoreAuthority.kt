package com.lezi.babylog.sync.disasterrecovery

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/** Identifiers only. Callers require the authenticated committed batch and durable snapshot. */
internal object RestoreAuthority {
    private val baselineNamespace = UUID.fromString("300c6a8b-5aab-51b6-bef0-53a13885188b")
    private val operationNamespace = UUID.fromString("b823b4c7-7ccf-5ded-b7f7-d190cbb05d69")
    val rootTypes = setOf("baby", "record", "care_plan", "custom_item", "wake_observation")

    fun baseline(batch: String, type: String, entity: String): String =
        identifier(baselineNamespace, batch, type, entity)

    fun operation(batch: String, type: String, entity: String): String =
        identifier(operationNamespace, batch, type, entity)

    private fun identifier(namespace: UUID, batch: String, type: String, entity: String): String {
        require(type in rootTypes)
        listOf(batch, entity).forEach { require(UUID.fromString(it).toString() == it) }
        val tuple = listOf(batch, type, entity).joinToString("") {
            "${it.toByteArray(Charsets.UTF_8).size}:$it"
        }
        val hash = MessageDigest.getInstance("SHA-1").apply {
            update(ByteBuffer.allocate(16).putLong(namespace.mostSignificantBits)
                .putLong(namespace.leastSignificantBits).array())
            update(tuple.toByteArray(Charsets.UTF_8))
        }.digest()
        hash[6] = ((hash[6].toInt() and 0x0f) or 0x50).toByte()
        hash[8] = ((hash[8].toInt() and 0x3f) or 0x80).toByte()
        val bytes = ByteBuffer.wrap(hash)
        return UUID(bytes.long, bytes.long).toString()
    }
}
