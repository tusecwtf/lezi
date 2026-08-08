package com.lezi.babylog.domain.carelog

import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import java.security.MessageDigest

/**
 * Client-side soft **疑似重复组** projection (ADR-0021 / CONTEXT).
 *
 * Pure function of live care records: exact wire-type whitelist, same baby,
 * different author membership, inclusive 30-minute connected components.
 * Never mutates Record rows, dirty flags, CarePlan fulfillment, or photos.
 */
object SuspectedDuplicateGrouping {
    /** Inclusive window on event main timestamp (milliseconds). */
    const val WINDOW_MS: Long = 30L * 60L * 1000L

    /**
     * Exact wire keys that participate in suspected-duplicate grouping.
     * Subtypes never merge by UI super-category.
     */
    val WHITELIST_TYPES: Set<RecordType> = setOf(
        RecordType.NURSING,
        RecordType.FORMULA,
        RecordType.PUMPED_FEED,
        RecordType.PUMP_EXPRESS,
        RecordType.BABY_FOOD,
        RecordType.SNACK,
        RecordType.DRINK,
        RecordType.PEE,
        RecordType.POOP,
        RecordType.BOTH_DIAPER,
        RecordType.TEMPERATURE,
        RecordType.BATH,
        RecordType.MEDICINE,
    )

    fun isWhitelistType(type: RecordType): Boolean = type in WHITELIST_TYPES

    fun isWhitelistWireKey(key: String): Boolean =
        RecordType.fromKey(key)?.let { isWhitelistType(it) } == true

    /**
     * Compute open suspected-duplicate groups.
     *
     * @param records live (and optionally deleted) care records in scope
     * @param excludedClientUuids records already bound in a source relation
     *   (display or source) — they do not form a new open group together
     */
    fun group(
        records: List<Record>,
        excludedClientUuids: Set<String> = emptySet(),
    ): List<SuspectedDuplicateGroup> {
        val candidates = records.asSequence()
            .filter { it.deletedAt == null }
            .filter { isWhitelistType(it.type) }
            .filter { it.createdByMembershipId.isNotBlank() }
            .filter { it.clientUuid !in excludedClientUuids }
            .toList()
        if (candidates.size < 2) return emptyList()

        val byShard = candidates.groupBy { ShardKey(it.babyId, it.type) }
        val groups = mutableListOf<SuspectedDuplicateGroup>()
        for ((shard, rows) in byShard) {
            if (rows.size < 2) continue
            for (component in connectedComponents(rows)) {
                if (component.size < 2) continue
                val memberUuids = component.map { it.clientUuid }.sorted()
                groups += SuspectedDuplicateGroup(
                    groupId = deterministicGroupId(memberUuids),
                    babyId = shard.babyId,
                    recordType = shard.recordType,
                    memberClientUuids = memberUuids,
                )
            }
        }
        return groups.sortedWith(
            compareBy(
                { it.babyId },
                { it.recordType.key },
                { it.groupId },
            ),
        )
    }

    private data class ShardKey(val babyId: Long, val recordType: RecordType)

    private fun connectedComponents(rows: List<Record>): List<List<Record>> {
        val n = rows.size
        val adj = Array(n) { mutableListOf<Int>() }
        for (i in 0 until n) {
            for (j in (i + 1) until n) {
                val a = rows[i]
                val b = rows[j]
                if (a.createdByMembershipId == b.createdByMembershipId) continue
                val delta = kotlin.math.abs(a.timestamp - b.timestamp)
                if (delta <= WINDOW_MS) {
                    adj[i].add(j)
                    adj[j].add(i)
                }
            }
        }
        val seen = BooleanArray(n)
        val out = mutableListOf<List<Record>>()
        for (start in 0 until n) {
            if (seen[start]) continue
            val queue = ArrayDeque<Int>()
            val component = mutableListOf<Record>()
            queue.add(start)
            seen[start] = true
            while (queue.isNotEmpty()) {
                val i = queue.removeFirst()
                component += rows[i]
                for (next in adj[i]) {
                    if (!seen[next]) {
                        seen[next] = true
                        queue.add(next)
                    }
                }
            }
            out += component
        }
        return out
    }

    /** Stable opaque id from sorted member UUIDs (local projection only). */
    fun deterministicGroupId(sortedMemberUuids: List<String>): String {
        require(sortedMemberUuids == sortedMemberUuids.sorted()) {
            "member UUIDs must be sorted for deterministic group id"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val material = sortedMemberUuids.joinToString("\u0000")
        val hash = digest.digest(material.toByteArray(Charsets.UTF_8))
        return hash.take(16).joinToString("") { b -> "%02x".format(b) }
    }
}

/**
 * Soft suspected-duplicate group. Presentation-only; not a server verdict.
 */
data class SuspectedDuplicateGroup(
    val groupId: String,
    val babyId: Long,
    val recordType: RecordType,
    /** Sorted member record client UUIDs. */
    val memberClientUuids: List<String>,
) {
    val memberCount: Int get() = memberClientUuids.size
}
