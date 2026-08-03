package com.lezi.babylog.sync.engine

/** One same-cycle wire snapshot derived from the reconciled Room replica. */
internal data class PublishCandidate(
    val planId: Long,
    val entityType: String,
    val clientUuid: String,
    val payloadJson: String,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

/** In-memory only. Process death discards it; the next cycle rebuilds from Room. */
internal class EphemeralPublishPlan(candidates: List<PublishCandidate>) {
    private val remaining = LinkedHashMap<Long, PublishCandidate>().apply {
        candidates.forEach { candidate -> put(candidate.planId, candidate) }
    }

    val isEmpty: Boolean get() = remaining.isEmpty()

    fun peek(limit: Int): List<PublishCandidate> = remaining.values.take(limit)

    fun find(entityType: String, clientUuid: String): PublishCandidate? =
        remaining.values.firstOrNull { candidate ->
            candidate.entityType == entityType && candidate.clientUuid == clientUuid
        }

    fun consume(candidates: Collection<PublishCandidate>) {
        candidates.forEach { candidate -> remaining.remove(candidate.planId) }
    }
}
