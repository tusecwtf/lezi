package com.lezi.babylog.sync.backend

import com.google.common.truth.Truth.assertThat
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

/**
 * Wire §8.1/§8.2 golden paths on [HttpSyncBackend]: GET detail and POST resolve
 * against closed keys (resolved + cas_mismatch), not only NoOp defaults.
 */
class HttpSyncBackendConflictWireTest {
    @Test
    fun fetchConflictDetail_parsesClosedKeysIncludingBranchesAndPaths() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val captured = mutableListOf<String>()
        val body = """
            {
              "conflict_id":"c-1",
              "stable_version_id":"v-stable",
              "stable_root":{"note":"a","timestamp":100},
              "stable_media":[],
              "branches":[
                {
                  "branch_version_id":"b-1",
                  "root":{"note":"b","timestamp":100},
                  "media":[],
                  "mutation_id":"m-1"
                }
              ],
              "conflicting_paths":["/note"],
              "auto_merged":{"/timestamp":100}
            }
        """.trimIndent().toByteArray(Charsets.UTF_8)
        val responder = thread(name = "lezi-conflict-detail-test") {
            server.accept().use { socket ->
                captured += readRequest(socket)
                socket.getOutputStream().use { output ->
                    output.write(
                        (
                            "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: application/json\r\n" +
                                "Content-Length: ${body.size}\r\n" +
                                "Connection: close\r\n\r\n"
                            ).toByteArray(Charsets.US_ASCII),
                    )
                    output.write(body)
                }
            }
        }
        try {
            val detail = loopbackBackend().fetchConflictDetail(testSession(server), "c-1")
            assertThat(detail.conflictId).isEqualTo("c-1")
            assertThat(detail.stableVersionId).isEqualTo("v-stable")
            assertThat(detail.stableRootJson).contains("\"note\"")
            assertThat(detail.conflictingPaths).containsExactly("/note")
            assertThat(detail.branchVersionIds).containsExactly("b-1")
            assertThat(detail.autoMergedJson).contains("/timestamp")
            assertThat(captured.single().lineSequence().first())
                .startsWith("GET /v1/conflicts/c-1 ")
            assertThat(captured.single()).contains("Authorization: Bearer family-token")
        } finally {
            server.close()
            responder.join(2_000)
        }
    }

    @Test
    fun resolveConflict_acceptedParsesStableRootAndMedia() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val captured = mutableListOf<String>()
        val body = """
            {
              "status":"resolved",
              "stable_version_id":"v-resolved",
              "stable_root":{"note":"chosen","timestamp":100},
              "stable_media":[
                {
                  "media_uuid":"aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
                  "role":"log",
                  "sha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                  "byte_size":12,
                  "mime":"image/jpeg",
                  "width":1,
                  "height":1
                }
              ]
            }
        """.trimIndent().toByteArray(Charsets.UTF_8)
        val responder = thread(name = "lezi-conflict-resolve-ok") {
            server.accept().use { socket ->
                captured += readRequest(socket)
                socket.getOutputStream().use { output ->
                    output.write(
                        (
                            "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: application/json\r\n" +
                                "Content-Length: ${body.size}\r\n" +
                                "Connection: close\r\n\r\n"
                            ).toByteArray(Charsets.US_ASCII),
                    )
                    output.write(body)
                }
            }
        }
        try {
            val result = loopbackBackend().resolveConflict(
                session = testSession(server),
                conflictId = "c-1",
                request = ConflictResolveRequest(
                    expectedStableVersion = "v-stable",
                    expectedBranchVersions = listOf("b-2", "b-1"),
                    resolvedRootJson = """{"note":"chosen","timestamp":100}""",
                    resolvedMedia = emptyList(),
                    resolutionMutationId = "res-1",
                    conflictChoices = mapOf("/note" to JsonPrimitive("chosen")),
                ),
            )
            assertThat(result).isInstanceOf(ConflictResolveResult.Accepted::class.java)
            val accepted = result as ConflictResolveResult.Accepted
            assertThat(accepted.stableVersionId).isEqualTo("v-resolved")
            assertThat(accepted.stableRootJson).contains("chosen")
            assertThat(accepted.stableMedia).hasSize(1)
            assertThat(accepted.stableMedia.single().mediaUuid)
                .isEqualTo("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee")
            val request = captured.single()
            assertThat(request.lineSequence().first())
                .startsWith("POST /v1/conflicts/c-1/resolve ")
            // Branch set is sorted on the wire.
            assertThat(request).contains(""""expected_branch_versions":["b-1","b-2"]""")
            assertThat(request).contains(""""resolution_mutation_id":"res-1"""")
        } finally {
            server.close()
            responder.join(2_000)
        }
    }

    @Test
    fun resolveConflict_casMismatchParsesSummary() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val body = """
            {
              "status":"cas_mismatch",
              "code":"cas_mismatch",
              "conflict_summary":{
                "conflict_id":"c-1",
                "entity_type":"record",
                "client_uuid":"r-1",
                "stable_version_id":"v-new",
                "branch_version_ids":["b-9"]
              }
            }
        """.trimIndent().toByteArray(Charsets.UTF_8)
        val responder = thread(name = "lezi-conflict-resolve-cas") {
            server.accept().use { socket ->
                readRequest(socket)
                socket.getOutputStream().use { output ->
                    output.write(
                        (
                            "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: application/json\r\n" +
                                "Content-Length: ${body.size}\r\n" +
                                "Connection: close\r\n\r\n"
                            ).toByteArray(Charsets.US_ASCII),
                    )
                    output.write(body)
                }
            }
        }
        try {
            val result = loopbackBackend().resolveConflict(
                session = testSession(server),
                conflictId = "c-1",
                request = ConflictResolveRequest(
                    expectedStableVersion = "v-old",
                    expectedBranchVersions = listOf("b-1"),
                    resolvedRootJson = """{"note":"x"}""",
                    resolutionMutationId = "res-cas",
                ),
            )
            assertThat(result).isInstanceOf(ConflictResolveResult.CasMismatch::class.java)
            val cas = result as ConflictResolveResult.CasMismatch
            assertThat(cas.summary!!.stableVersionId).isEqualTo("v-new")
            assertThat(cas.summary!!.branchVersionIds).containsExactly("b-9")
            assertThat(cas.summary!!.entityType).isEqualTo("record")
        } finally {
            server.close()
            responder.join(2_000)
        }
    }
}
