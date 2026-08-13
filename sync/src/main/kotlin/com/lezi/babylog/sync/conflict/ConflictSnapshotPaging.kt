package com.lezi.babylog.sync.conflict

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

object ConflictSnapshotPaging {
    const val MAX_BRANCHES_PER_SNAPSHOT = 64
    // A byte-heavy branch may be the only item on a page; the 64-branch
    // admission cap therefore also defines the worst-case page count.
    const val MAX_PAGES_PER_SNAPSHOT = MAX_BRANCHES_PER_SNAPSHOT
    const val MAX_ENCODED_PAGE_BYTES = 128 * 1024
    const val MAX_ENCODED_SNAPSHOT_BYTES =
        MAX_PAGES_PER_SNAPSHOT * MAX_ENCODED_PAGE_BYTES
}

/** Serializes only loads for the same conflict; unrelated network paging stays concurrent. */
internal class ConflictSnapshotLoadLocks {
    private data class Entry(
        val mutex: kotlinx.coroutines.sync.Mutex = kotlinx.coroutines.sync.Mutex(),
        var users: Int = 0,
    )

    private val monitor = Any()
    private val entries = mutableMapOf<String, Entry>()

    suspend fun <T> withLock(conflictId: String, block: suspend () -> T): T {
        val entry = synchronized(monitor) {
            entries.getOrPut(conflictId, ::Entry).also { it.users += 1 }
        }
        return try {
            entry.mutex.lock()
            try {
                block()
            } finally {
                entry.mutex.unlock()
            }
        } finally {
            synchronized(monitor) {
                entry.users -= 1
                if (entry.users == 0) entries.remove(conflictId, entry)
            }
        }
    }
}

sealed interface ConflictSnapshotPageRequest {
    data object First : ConflictSnapshotPageRequest

    data class Continuation(
        val snapshotToken: String,
        val continuation: String,
    ) : ConflictSnapshotPageRequest
}

/** A parsed page plus the exact encoded HTTP body size observed by the transport. */
data class FetchedConflictSnapshotPage(
    val snapshot: ConflictSnapshot,
    val encodedBytes: Int,
)

internal data class ConflictSnapshotPageEvidence(
    val snapshot: ConflictSnapshot,
    val encodedBytes: Int,
)

internal data class StagedConflictSnapshot(
    val pages: List<ConflictSnapshotPageEvidence>,
) {
    val first: ConflictSnapshot get() = pages.first().snapshot
    val last: ConflictSnapshot get() = pages.last().snapshot
    val totalBranchCount: Int get() = pages.sumOf { it.snapshot.branches.size }
    val totalEncodedBytes: Int get() = pages.sumOf { it.encodedBytes }
    val nextRequest: ConflictSnapshotPageRequest.Continuation
        get() = ConflictSnapshotPageRequest.Continuation(
            snapshotToken = first.snapshotToken,
            continuation = requireNotNull(last.continuation),
        )
}

/** Closed persistence codec for resumable page evidence. */
internal object ConflictSnapshotStageCodec {
    private const val CONTRACT = "conflict_snapshot_page_stage_v1"
    private val json = Json

    fun encode(stage: StagedConflictSnapshot): String = buildJsonObject {
        put("contract", JsonPrimitive(CONTRACT))
        put("snapshot_token", JsonPrimitive(stage.first.snapshotToken))
        put("next_continuation", JsonPrimitive(requireNotNull(stage.last.continuation)))
        put("next_page_index", JsonPrimitive(stage.pages.size))
        put("total_branch_count", JsonPrimitive(stage.totalBranchCount))
        put("total_encoded_bytes", JsonPrimitive(stage.totalEncodedBytes))
        put(
            "pages",
            buildJsonArray {
                stage.pages.forEach { evidence ->
                    add(
                        buildJsonObject {
                            put(
                                "snapshot",
                                json.parseToJsonElement(
                                    ConflictSnapshotCodec.encode(evidence.snapshot),
                                ),
                            )
                            put("page_index", JsonPrimitive(evidence.snapshot.pageIndex))
                            put("branch_count", JsonPrimitive(evidence.snapshot.branches.size))
                            put("encoded_bytes", JsonPrimitive(evidence.encodedBytes))
                        },
                    )
                }
            },
        )
    }.toString()

    fun decode(raw: String): StagedConflictSnapshot {
        val root = runCatching { json.parseToJsonElement(raw) }.getOrElse {
            throw IllegalArgumentException("conflict snapshot page stage 不是 JSON", it)
        } as? JsonObject
            ?: throw IllegalArgumentException("conflict snapshot page stage 不是对象")
        root.requireKeys(
            "contract",
            "snapshot_token",
            "next_continuation",
            "next_page_index",
            "total_branch_count",
            "total_encoded_bytes",
            "pages",
        )
        require(root.string("contract") == CONTRACT) { "conflict snapshot page stage 合同无效" }
        val pages = (root["pages"] as? JsonArray)
            ?.mapIndexed { index, value ->
                val evidence = value as? JsonObject
                    ?: throw IllegalArgumentException("conflict snapshot page stage.pages[$index] 不是对象")
                evidence.requireKeys("snapshot", "page_index", "branch_count", "encoded_bytes")
                val snapshotObject = evidence["snapshot"] as? JsonObject
                    ?: throw IllegalArgumentException(
                        "conflict snapshot page stage.pages[$index].snapshot 不是对象",
                    )
                val snapshot = ConflictSnapshotCodec.decode(snapshotObject.toString())
                require(evidence.int("page_index") == snapshot.pageIndex) {
                    "conflict snapshot page stage page_index evidence 不一致"
                }
                require(evidence.int("branch_count") == snapshot.branches.size) {
                    "conflict snapshot page stage branch_count evidence 不一致"
                }
                ConflictSnapshotPageEvidence(
                    snapshot = snapshot,
                    encodedBytes = evidence.int("encoded_bytes"),
                )
            }
            ?: throw IllegalArgumentException("conflict snapshot page stage.pages 不是数组")
        val stage = StagedConflictSnapshot(pages)
        require(pages.isNotEmpty()) { "conflict snapshot page stage 不能为空" }
        require(root.string("snapshot_token") == stage.first.snapshotToken) {
            "conflict snapshot page stage receipt evidence 不一致"
        }
        require(root.string("next_continuation") == stage.last.continuation) {
            "conflict snapshot page stage continuation evidence 不一致"
        }
        require(root.int("next_page_index") == pages.size) {
            "conflict snapshot page stage ordinal evidence 不一致"
        }
        require(root.int("total_branch_count") == stage.totalBranchCount) {
            "conflict snapshot page stage count evidence 不一致"
        }
        require(root.int("total_encoded_bytes") == stage.totalEncodedBytes) {
            "conflict snapshot page stage byte evidence 不一致"
        }
        return stage
    }

    private fun JsonObject.requireKeys(vararg expected: String) {
        require(keys == expected.toSet()) { "conflict snapshot page stage keys 不闭合" }
    }

    private fun JsonObject.string(key: String): String =
        (get(key) as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.contentOrNull
            ?: throw IllegalArgumentException("conflict snapshot page stage.$key 不是字符串")

    private fun JsonObject.int(key: String): Int =
        (get(key) as? JsonPrimitive)
            ?.intOrNull
            ?: throw IllegalArgumentException("conflict snapshot page stage.$key 不是整数")
}
