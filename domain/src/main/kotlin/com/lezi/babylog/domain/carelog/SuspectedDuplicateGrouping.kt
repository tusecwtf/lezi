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
                isWhitelistType(record.type) &&
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
            byShard.getOrPut(ShardKey(record.babyId, record.type), ::mutableListOf) += record
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

    private data class ShardKey(val babyId: Long, val recordType: RecordType)

    /**
     * Preserve the exact pair-graph components without materializing its O(n^2)
     * adjacency.  Within one author pair the time-window graph is convex: linking
     * every vertex to the first opposite-author vertex in its inclusive window
     * preserves each bipartite component.  Taking that sparse spanning graph for
     * every author pair therefore preserves the union of all pair graphs.
     *
     * For n rows and `a` distinct authors, this performs at most n*(a-1) binary
     * searches/unions: O(n*a*log n) time and O(n+a) auxiliary space (a <= n).
     * It never allocates the potentially quadratic edge set.
     */
    private fun connectedComponents(
        rows: List<Record>,
        checkActive: () -> Unit,
    ): List<List<Record>> {
        checkActive()
        val sorted = rows.sortedWith(compareBy(Record::timestamp, Record::clientUuid))
            .also { checkActive() }
        val indicesByAuthor = linkedMapOf<String, MutableList<Int>>()
        sorted.indices.forEach { index ->
            checkActive()
            indicesByAuthor.getOrPut(
                sorted[index].createdByMembershipId,
                ::mutableListOf,
            ) += index
        }
        val union = DisjointSet(sorted.size)

        sorted.indices.forEach { rowIndex ->
            checkActive()
            val row = sorted[rowIndex]
            val lower = saturatingSubtract(row.timestamp, WINDOW_MS)
            val upper = saturatingAdd(row.timestamp, WINDOW_MS)
            indicesByAuthor.forEach { (author, authorIndices) ->
                checkActive()
                if (author == row.createdByMembershipId) return@forEach
                val neighborOffset = authorIndices.lowerBoundByTimestamp(sorted, lower)
                if (neighborOffset < authorIndices.size) {
                    val neighborIndex = authorIndices[neighborOffset]
                    if (sorted[neighborIndex].timestamp <= upper) {
                        union.union(rowIndex, neighborIndex)
                    }
                }
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

    private fun List<Int>.lowerBoundByTimestamp(rows: List<Record>, timestamp: Long): Int {
        var low = 0
        var high = size
        while (low < high) {
            val mid = (low + high).ushr(1)
            if (rows[this[mid]].timestamp < timestamp) low = mid + 1 else high = mid
        }
        return low
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
