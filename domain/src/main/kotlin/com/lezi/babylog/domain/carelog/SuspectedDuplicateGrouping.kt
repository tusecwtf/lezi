package com.lezi.babylog.domain.carelog

import com.lezi.babylog.core.model.CustomPayload
import com.lezi.babylog.core.model.MedicinePayload
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.VaccinePayload
import java.security.MessageDigest
import java.text.Normalizer

/**
 * Client-side near-neighbor grouping (ADR-0023 / CONTEXT).
 *
 * Pure function of live care records: every Record type, same baby, exact type,
 * optional name/item shard, inclusive 30-minute connected components. Same
 * author / same device still connect. Never mutates Record rows.
 *
 * Open groups are an offline preview until the family server auto-aligns.
 */
object SuspectedDuplicateGrouping {
    /** Inclusive window on event main timestamp (milliseconds). */
    const val WINDOW_MS: Long = 30L * 60L * 1000L

    private val NAME_SHARD_WHITESPACE = Regex("\\s+")

    fun participates(type: RecordType): Boolean = true

    fun participatesWireKey(key: String): Boolean = RecordType.fromKey(key) != null

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
    ): List<SuspectedDuplicateGroup> = group(
        records = records,
        excludedClientUuids = excludedClientUuids,
        checkActive = {},
    )

    internal fun group(
        records: List<Record>,
        excludedClientUuids: Set<String>,
        checkActive: () -> Unit,
    ): List<SuspectedDuplicateGroup> {
        val candidates = ArrayList<Record>(records.size)
        records.forEach { record ->
            checkActive()
            if (
                record.deletedAt == null &&
                record.createdByMembershipId.isNotBlank() &&
                record.clientUuid !in excludedClientUuids
            ) {
                candidates += record
            }
        }
        if (candidates.size < 2) return emptyList()

        val byShard = linkedMapOf<ShardKey, MutableList<Record>>()
        candidates.forEach { record ->
            checkActive()
            byShard.getOrPut(
                ShardKey(record.babyId, record.type, payloadShard(record)),
                ::mutableListOf,
            ) += record
        }
        val groups = mutableListOf<SuspectedDuplicateGroup>()
        for ((shard, rows) in byShard) {
            checkActive()
            if (rows.size < 2) continue
            for (component in connectedComponents(rows, checkActive)) {
                checkActive()
                if (component.size < 2) continue
                val memberUuids = component.map { record ->
                    checkActive()
                    record.clientUuid
                }.also { checkActive() }.sorted().also { checkActive() }
                groups += SuspectedDuplicateGroup(
                    groupId = deterministicGroupId(memberUuids),
                    babyId = shard.babyId,
                    recordType = shard.recordType,
                    memberClientUuids = memberUuids,
                )
            }
        }
        checkActive()
        return groups.sortedWith(
            compareBy(
                { it.babyId },
                { it.recordType.key },
                { it.groupId },
            ),
        ).also { checkActive() }
    }

    internal fun payloadShard(record: Record): String = when (record.type) {
        RecordType.MEDICINE, RecordType.VACCINE -> {
            val raw = when (val payload = record.payload.payload) {
                is MedicinePayload -> payload.name
                is VaccinePayload -> payload.name
                else -> ""
            }
            normalizeNameShard(raw)?.let { name -> "name:$name" }
                ?: "empty:${record.clientUuid}"
        }
        RecordType.CUSTOM -> {
            val itemId = (record.payload.payload as? CustomPayload)?.customItemId
            if (itemId != null) "item:$itemId" else "empty:${record.clientUuid}"
        }
        else -> ""
    }

    internal fun normalizeNameShard(raw: String): String? {
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
            .lowercase()
            .split(NAME_SHARD_WHITESPACE)
            .filter(String::isNotEmpty)
            .joinToString(" ")
        return normalized.takeIf(String::isNotEmpty)
    }

    private data class ShardKey(
        val babyId: Long,
        val recordType: RecordType,
        val payloadShard: String,
    )

    /**
     * Time-window graph on a line: union consecutive-in-window pairs. Transitive
     * chains stay one component without materializing O(n^2) edges.
     */
    private fun connectedComponents(
        rows: List<Record>,
        checkActive: () -> Unit,
    ): List<List<Record>> {
        checkActive()
        val sorted = rows.sortedWith(compareBy(Record::timestamp, Record::clientUuid))
            .also { checkActive() }
        val union = DisjointSet(sorted.size)
        for (index in 1 until sorted.size) {
            checkActive()
            if (sorted[index].timestamp - sorted[index - 1].timestamp <= WINDOW_MS) {
                union.union(index, index - 1)
            }
        }
        val components = linkedMapOf<Int, MutableList<Record>>()
        sorted.indices.forEach { index ->
            checkActive()
            components.getOrPut(union.find(index), ::mutableListOf) += sorted[index]
        }
        return components.values.map { component ->
            checkActive()
            component
        }
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

internal fun saturatingSubtract(value: Long, delta: Long): Long =
    if (value < Long.MIN_VALUE + delta) Long.MIN_VALUE else value - delta

internal fun saturatingAdd(value: Long, delta: Long): Long =
    if (value > Long.MAX_VALUE - delta) Long.MAX_VALUE else value + delta

private class DisjointSet(size: Int) {
    private val parent = IntArray(size) { it }
    private val rank = ByteArray(size)

    fun find(value: Int): Int {
        var root = value
        while (parent[root] != root) root = parent[root]
        var cursor = value
        while (parent[cursor] != cursor) {
            val next = parent[cursor]
            parent[cursor] = root
            cursor = next
        }
        return root
    }

    fun union(left: Int, right: Int) {
        var leftRoot = find(left)
        var rightRoot = find(right)
        if (leftRoot == rightRoot) return
        if (rank[leftRoot] < rank[rightRoot]) {
            val swap = leftRoot
            leftRoot = rightRoot
            rightRoot = swap
        }
        parent[rightRoot] = leftRoot
        if (rank[leftRoot] == rank[rightRoot]) rank[leftRoot]++
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
