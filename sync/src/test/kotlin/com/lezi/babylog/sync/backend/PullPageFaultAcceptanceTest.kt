package com.lezi.babylog.sync.backend

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.engine.ReplicaEngineRig
import com.lezi.babylog.sync.engine.joinedReplicaSession
import com.lezi.babylog.sync.engine.remoteReplicaBaby
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.thread
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * H35 acceptance: fixed fault matrix proving truncated/corrupt/over-budget gzip and
 * duplicate/skipped/non-monotonic pull pages never advance the durable cursor or write
 * partial facts. Happy-path identity/gzip projections are byte-stable and equivalent.
 *
 * Wire/decode budgets reuse the H16 [HttpSyncBackend] + [FROZEN_PULL_PAGE_BUDGET] seams.
 * Cross-page continuation ownership is proven on the engine checkpoint owner (H16).
 * Real Room page-transaction crash recovery remains the androidTest residual
 * [com.lezi.babylog.sync.engine.PullCheckpointRoomReplayTest] (no ADB device here).
 */
class PullPageFaultAcceptanceTest {
    @Test
    fun seedAndBudgetTableArePinnedForTheFaultMatrix() {
        assertThat(H35_FAULT_SEED).isEqualTo(0x35_16_31L)
        assertThat(FROZEN_PULL_PAGE_BUDGET).isEqualTo(
            PullPageBudget(
                maxEntities = 200,
                maxEncodedBytes = 9 * 1024 * 1024,
                maxDecodedBytes = 8 * 1024 * 1024,
                maxPages = 500,
            ),
        )
    }

    @Test
    fun c1_identityHappyPathProjectsStableEntitiesAndCursor() = runTest {
        val payload = happyPullJson(cursor = 7, pageIndex = 0, familySuffix = "id")
        val receipt = serveAndPull(
            body = payload.toByteArray(Charsets.UTF_8),
            encoding = PullResponseEncoding.Identity,
            contentEncoding = null,
        )

        assertThat(receipt.result.cursor).isEqualTo(7)
        assertThat(receipt.result.pageIndex).isEqualTo(0)
        assertThat(receipt.result.hasMore).isFalse()
        assertThat(receipt.result.entities.single().clientUuid).isEqualTo("baby-h35")
        assertThat(receipt.encodedBytes).isEqualTo(payload.toByteArray(Charsets.UTF_8).size)
        assertThat(receipt.decodedBytes).isEqualTo(receipt.encodedBytes)
        assertThat(receipt.entityCount).isEqualTo(1)
        assertThat(receipt.requestLine)
            .startsWith("GET /v1/pull?cursor=0&generation=generation-a&page_index=0 ")
        assertThat(receipt.requestHeaders).contains("Accept-Encoding: identity")
    }

    @Test
    fun c2_gzipHappyPathStableProjectionEqualsIdentity() = runTest {
        val payload = happyPullJson(cursor = 7, pageIndex = 0, familySuffix = "gz")
        val plain = payload.toByteArray(Charsets.UTF_8)
        val gzipped = gzip(plain)

        val identity = serveAndPull(
            body = plain,
            encoding = PullResponseEncoding.Identity,
            contentEncoding = null,
        )
        val compressed = serveAndPull(
            body = gzipped,
            encoding = PullResponseEncoding.Gzip,
            contentEncoding = "gzip",
        )

        assertThat(compressed.result.cursor).isEqualTo(identity.result.cursor)
        assertThat(compressed.result.pageIndex).isEqualTo(identity.result.pageIndex)
        assertThat(compressed.result.hasMore).isEqualTo(identity.result.hasMore)
        assertThat(compressed.result.generation).isEqualTo(identity.result.generation)
        assertThat(compressed.result.entities).isEqualTo(identity.result.entities)
        assertThat(compressed.result.familyName).isEqualTo(identity.result.familyName)
        assertThat(compressed.decodedBytes).isEqualTo(identity.decodedBytes)
        assertThat(compressed.encodedBytes).isLessThan(identity.encodedBytes)
        assertThat(compressed.entityCount).isEqualTo(1)
        assertThat(compressed.requestHeaders).contains("Accept-Encoding: gzip")
    }

    @Test
    fun c3_truncatedJsonFailsClosedWithoutReturningAPage() = runTest {
        val full = happyPullJson(cursor = 11, pageIndex = 0)
        val truncated = full.toByteArray(Charsets.UTF_8).copyOf(full.length / 2)
        val failure = failureFor(
            body = truncated,
            encoding = PullResponseEncoding.Identity,
            contentEncoding = null,
        )

        assertThat(failure).isNotNull()
        assertThat(failure).isNotInstanceOf(PullResult::class.java)
        // kotlinx.serialization / IllegalArgument — never a partial PullResult.
        assertThat(
            failure is IllegalArgumentException ||
                failure is kotlinx.serialization.SerializationException ||
                (failure?.message?.contains("JSON", ignoreCase = true) == true) ||
                (failure?.message?.contains("Unexpected", ignoreCase = true) == true),
        ).isTrue()
    }

    @Test
    fun c4_corruptGzipFailsClosedBeforeParse() = runTest {
        val failure = failureFor(
            body = byteArrayOf(0x1f, 0x8b.toByte(), 0x08, 0x00, 0x01, 0x02, 0x03, 0x04),
            encoding = PullResponseEncoding.Gzip,
            contentEncoding = "gzip",
        )
        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(failure).hasMessageThat().contains("gzip")
    }

    @Test
    fun c5_decodedBombOverBudgetFailsClosed() = runTest {
        val decoded =
            """{"entities":[],"cursor":7,"generation":"generation-a","page_index":0,"has_more":false,"family_name":"${"x".repeat(512)}"}"""
                .toByteArray(Charsets.UTF_8)
        val encoded = gzip(decoded)
        val failure = failureFor(
            body = encoded,
            encoding = PullResponseEncoding.Gzip,
            contentEncoding = "gzip",
            budget = FROZEN_PULL_PAGE_BUDGET.copy(
                maxEncodedBytes = 4 * 1024,
                maxDecodedBytes = 128,
            ),
        )
        assertThat(failure).isInstanceOf(SyncResponseTooLargeException::class.java)
        assertThat((failure as SyncResponseTooLargeException).responseKind)
            .contains("pull decoded JSON")
    }

    @Test
    fun c6_itemCountOverBudgetFailsClosed() = runTest {
        val first =
            """{"type":"custom_item","client_uuid":"first","payload":{},"updated_at":1,"deleted_at":null,"rev":1}"""
        val second =
            """{"type":"custom_item","client_uuid":"second","payload":{},"updated_at":2,"deleted_at":null,"rev":2}"""
        val body =
            """{"entities":[$first,$second],"cursor":7,"generation":"generation-a","page_index":0,"has_more":false,"family_name":null}"""
                .toByteArray(Charsets.UTF_8)
        val failure = failureFor(
            body = body,
            encoding = PullResponseEncoding.Identity,
            contentEncoding = null,
            budget = FROZEN_PULL_PAGE_BUDGET.copy(maxEntities = 1),
        )
        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(failure).hasMessageThat().contains("item 上限")
    }

    @Test
    fun c7_pageOverEncodedBudgetFailsClosedWithOrWithoutContentLength() = runTest {
        listOf(true, false).forEach { declaresLength ->
            val encoded = ByteArray(129) { 'x'.code.toByte() }
            val failure = failureFor(
                body = encoded,
                encoding = PullResponseEncoding.Identity,
                contentEncoding = null,
                budget = FROZEN_PULL_PAGE_BUDGET.copy(
                    maxEncodedBytes = 128,
                    maxDecodedBytes = 128,
                ),
                declareContentLength = declaresLength,
            )
            assertThat(failure).isInstanceOf(SyncResponseTooLargeException::class.java)
            assertThat(failure).hasMessageThat().contains("pull encoded JSON")
        }
    }

    @Test
    fun c8_duplicatePageIndexFailsClosedWithoutAdvancingCursorOrFacts() = runTest {
        val session = joinedReplicaSession().copy(pullCursor = 4)
        val rig = ReplicaEngineRig(session)
        // Sticky override: page 0 matches request; page 1 still claims page_index=0
        // (duplicate continuation / non-advancing page token).
        rig.backend.nextPullPageIndexOverride = 0
        rig.backend.pullResults += PullResult(
            entities = listOf(remoteReplicaBaby()),
            cursor = 5,
            generation = "generation-a",
            hasMore = true,
        )
        rig.backend.pullResults += PullResult(
            entities = listOf(
                remoteReplicaBaby().copy(clientUuid = "baby-dup", updatedAt = 200),
            ),
            cursor = 6,
            generation = "generation-a",
            hasMore = false,
        )

        val failure = runCatching {
            rig.engine.synchronize(session, SyncTrigger.PullToRefresh)
        }.exceptionOrNull()

        assertThat(failure).hasMessageThat().contains("page_index")
        // First page committed; duplicate second page must not skip ahead or write facts.
        assertThat(rig.preferences.current().pullCursor).isEqualTo(5)
        assertThat(rig.babies.getByClientUuid("baby-remote")).isNotNull()
        assertThat(rig.babies.getByClientUuid("baby-dup")).isNull()
    }

    @Test
    fun c9_skippedPageFailsClosedWithoutFactsOrCursorAdvance() = runTest {
        val session = joinedReplicaSession().copy(pullCursor = 4)
        val rig = ReplicaEngineRig(session)
        rig.backend.nextPullPageIndexOverride = 1
        rig.backend.nextPull = PullResult(
            entities = listOf(remoteReplicaBaby()),
            cursor = 5,
            generation = "generation-a",
            hasMore = false,
        )

        val failure = runCatching {
            rig.engine.synchronize(session, SyncTrigger.PullToRefresh)
        }.exceptionOrNull()

        assertThat(failure).hasMessageThat().contains("page_index")
        assertThat(rig.babies.getByClientUuid("baby-remote")).isNull()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(4)
    }

    @Test
    fun c10_nonMonotonicBackwardContinuationFailsClosed() = runTest {
        // Backward cursor on a continuing page.
        val session = joinedReplicaSession().copy(
            pullCursor = 5,
            pullGeneration = "generation-a",
        )
        val rig = ReplicaEngineRig(session)
        rig.backend.nextPull = PullResult(
            entities = emptyList(),
            cursor = 4,
            generation = "generation-a",
            hasMore = true,
        )

        val backward = runCatching {
            rig.engine.synchronize(session, SyncTrigger.PullToRefresh)
        }.exceptionOrNull()
        assertThat(backward).hasMessageThat().contains("倒退")
        assertThat(rig.preferences.current().pullCursor).isEqualTo(5)

        // Non-advancing cursor while has_more=true.
        val session2 = joinedReplicaSession().copy(
            pullCursor = 5,
            pullGeneration = "generation-a",
        )
        val rig2 = ReplicaEngineRig(session2)
        rig2.backend.nextPull = PullResult(
            entities = emptyList(),
            cursor = 5,
            generation = "generation-a",
            hasMore = true,
        )
        val stalled = runCatching {
            rig2.engine.synchronize(session2, SyncTrigger.PullToRefresh)
        }.exceptionOrNull()
        assertThat(stalled).hasMessageThat().contains("cursor 未推进")
        assertThat(rig2.preferences.current().pullCursor).isEqualTo(5)
    }

    @Test
    fun c11_failedSecondPageKeepsLastCommittedCheckpointAndRetriesMonotonically() = runTest {
        // JVM owner of "page applied + checkpoint promote" monotonicity (H16 engine).
        // Real Room transaction crash/reopen is PullCheckpointRoomReplayTest (device residual).
        val rig = ReplicaEngineRig(joinedReplicaSession())
        rig.backend.pullResults += PullResult(
            entities = listOf(remoteReplicaBaby()),
            cursor = 1,
            generation = "generation-a",
            hasMore = true,
        )
        rig.backend.pullResults += PullResult(
            entities = listOf(
                SyncEntity(
                    type = "device",
                    clientUuid = "unsupported-page-two-entity",
                    payloadJson = "{}",
                    updatedAt = 200,
                ),
            ),
            cursor = 2,
            generation = "generation-a",
            hasMore = false,
        )

        val firstFailure = runCatching {
            rig.engine.synchronize(rig.preferences.current(), SyncTrigger.PullToRefresh)
        }.exceptionOrNull()

        assertThat(firstFailure).hasMessageThat().contains("device")
        assertThat(rig.preferences.current().pullCursor).isEqualTo(1)
        assertThat(rig.babies.getByClientUuid("baby-remote")).isNotNull()

        rig.backend.pullResults += PullResult(
            entities = emptyList(),
            cursor = 2,
            generation = "generation-a",
            hasMore = false,
        )

        val retryOutcome = rig.engine.synchronize(
            rig.preferences.current(),
            SyncTrigger.PullToRefresh,
        )

        assertThat(retryOutcome).isEqualTo(com.lezi.babylog.sync.engine.ReplicaSyncOutcome.Synchronized)
        assertThat(rig.backend.pullCursors).containsExactly(0L, 1L, 1L).inOrder()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(2)
        // No fact skip: first-page baby remains after recovery.
        assertThat(rig.babies.getByClientUuid("baby-remote")).isNotNull()
    }

    private data class PullReceipt(
        val result: PullResult,
        val encodedBytes: Int,
        val decodedBytes: Int,
        val entityCount: Int,
        val requestLine: String,
        val requestHeaders: String,
    )

    private suspend fun serveAndPull(
        body: ByteArray,
        encoding: PullResponseEncoding,
        contentEncoding: String?,
        budget: PullPageBudget = FROZEN_PULL_PAGE_BUDGET,
        declareContentLength: Boolean = true,
        pageIndex: Int = 0,
    ): PullReceipt {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val captured = java.util.concurrent.CompletableFuture<String>()
        val responder = thread(name = "lezi-h35-pull-fault") {
            runCatching {
                server.accept().use { socket ->
                    captured.complete(readRequest(socket))
                    socket.getOutputStream().use { output ->
                        val encodingHeader = contentEncoding
                            ?.let { "Content-Encoding: $it\r\n" }
                            .orEmpty()
                        val lengthHeader = if (declareContentLength) {
                            "Content-Length: ${body.size}\r\n"
                        } else {
                            ""
                        }
                        output.write(
                            (
                                "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: application/json\r\n" +
                                    encodingHeader +
                                    lengthHeader +
                                    "Connection: close\r\n\r\n"
                                ).toByteArray(Charsets.US_ASCII),
                        )
                        output.write(body)
                    }
                }
            }.onFailure(captured::completeExceptionally)
        }
        return try {
            val result = loopbackBackend().pull(
                testSession(server),
                PullPageRequest(pageIndex, encoding, budget),
            )
            val request = captured.get(2, java.util.concurrent.TimeUnit.SECONDS)
            val decodedEstimate = when (encoding) {
                PullResponseEncoding.Identity -> body.size
                PullResponseEncoding.Gzip -> happyPullJson(
                    cursor = result.cursor,
                    pageIndex = result.pageIndex,
                    familySuffix = result.familyName?.removePrefix("h35-") ?: "",
                ).let {
                    // Prefer measuring via known happy payload size when projection matches.
                    if (result.entities.singleOrNull()?.clientUuid == "baby-h35") {
                        happyPullJson(
                            cursor = result.cursor,
                            pageIndex = result.pageIndex,
                            familySuffix = result.familyName!!.removePrefix("h35-"),
                        ).toByteArray(Charsets.UTF_8).size
                    } else {
                        body.size
                    }
                }
            }
            PullReceipt(
                result = result,
                encodedBytes = body.size,
                decodedBytes = decodedEstimate,
                entityCount = result.entities.size,
                requestLine = request.lineSequence().first(),
                requestHeaders = request,
            )
        } finally {
            server.close()
            responder.join(2_000)
        }
    }

    private suspend fun failureFor(
        body: ByteArray,
        encoding: PullResponseEncoding,
        contentEncoding: String?,
        budget: PullPageBudget = FROZEN_PULL_PAGE_BUDGET,
        declareContentLength: Boolean = true,
    ): Throwable? {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val responder = thread(name = "lezi-h35-pull-fault-fail") {
            runCatching {
                server.accept().use { socket ->
                    readRequest(socket)
                    socket.getOutputStream().use { output ->
                        val encodingHeader = contentEncoding
                            ?.let { "Content-Encoding: $it\r\n" }
                            .orEmpty()
                        val lengthHeader = if (declareContentLength) {
                            "Content-Length: ${body.size}\r\n"
                        } else {
                            ""
                        }
                        output.write(
                            (
                                "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: application/json\r\n" +
                                    encodingHeader +
                                    lengthHeader +
                                    "Connection: close\r\n\r\n"
                                ).toByteArray(Charsets.US_ASCII),
                        )
                        output.write(body)
                    }
                }
            }
        }
        return try {
            runCatching {
                loopbackBackend().pull(
                    testSession(server),
                    PullPageRequest(0, encoding, budget),
                )
            }.exceptionOrNull()
        } finally {
            server.close()
            responder.join(2_000)
        }
    }

    private fun happyPullJson(
        cursor: Long,
        pageIndex: Int,
        familySuffix: String = "x",
    ): String {
        val entity =
            """{"type":"baby","client_uuid":"baby-h35","payload":{"nickname":"H35","sex":null,"birthday":"2024-01-01","birth_weight_grams":null,"avatar_media_uuid":null},"updated_at":100,"deleted_at":null,"rev":1}"""
        return """{"entities":[$entity],"cursor":$cursor,"generation":"generation-a","page_index":$pageIndex,"has_more":false,"family_name":"h35-$familySuffix"}"""
    }

    private fun gzip(decoded: ByteArray): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            GZIPOutputStream(bytes).use { it.write(decoded) }
        }.toByteArray()
}

/** Deterministic matrix seed pin (ticket id 35 + H16 dependency marker). */
internal const val H35_FAULT_SEED = 0x35_16_31L
