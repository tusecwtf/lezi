package com.lezi.babylog.sync.backend

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.sync.conflict.ConflictOutcome
import com.lezi.babylog.sync.conflict.ConflictRoot
import com.lezi.babylog.sync.conflict.ConflictRootType
import com.lezi.babylog.sync.conflict.ConflictSnapshotPageRequest
import com.lezi.babylog.sync.conflict.ConflictSnapshotPaging
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import org.junit.Test

/**
 * Wire §8.1/§8.2 golden paths on [HttpSyncBackend]: GET detail and POST resolve
 * against the accepted/rejected closed terminals, not only NoOp defaults.
 */
class HttpSyncBackendConflictWireTest {
    @Test
    fun fetchConflictSnapshotPageRejectsTransportBodyAboveReceiptBudget() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val responder = thread(name = "lezi-conflict-budget-test") {
            server.accept().use { socket ->
                readRequest(socket)
                socket.getOutputStream().use { output ->
                    output.write(
                        (
                            "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: application/json\r\n" +
                                "Content-Length: ${ConflictSnapshotPaging.MAX_ENCODED_PAGE_BYTES + 1}\r\n" +
                                "Connection: close\r\n\r\n"
                            ).toByteArray(Charsets.US_ASCII),
                    )
                }
            }
        }
        try {
            val failure = runCatching {
                loopbackBackend().fetchConflictSnapshotPage(
                    testSession(server),
                    "00000000-0000-0000-0000-000000000010",
                    ConflictSnapshotPageRequest.First,
                )
            }.exceptionOrNull()
            assertThat(failure).isInstanceOf(SyncResponseTooLargeException::class.java)
            assertThat((failure as SyncResponseTooLargeException).limitBytes)
                .isEqualTo(ConflictSnapshotPaging.MAX_ENCODED_PAGE_BYTES)
        } finally {
            server.close()
            responder.join(2_000)
        }
    }

    @Test
    fun fetchConflictSnapshotPageSendsOpaqueReceiptAndContinuationTogether() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val captured = mutableListOf<String>()
        val token = "t".repeat(43)
        val continuation = "c".repeat(43)
        val body = """
            {"contract":"conflict_snapshot_v2","conflict_id":"00000000-0000-0000-0000-000000000010","entity_type":"custom_item","client_uuid":"00000000-0000-0000-0000-000000000001","snapshot_token":"$token","expires_at":2000000,"stable":{"version_id":"v1","base_version":null,"root":{"name":"散步","icon_slot":1,"updated_at":100,"created_by_membership_id":"member-a"},"media":[],"deleted":false,"mutation_id":"00000000-0000-0000-0000-000000000003","actor_id":"member-a","device_id":"device-a","received_at":100},"branches":[],"conflicting":[{"path":"/name","candidates":[{"choice_id":"${"a".repeat(43)}","outcome":{"op":"set","value":"散步"},"sources":[{"version_id":"v1","mutation_id":"00000000-0000-0000-0000-000000000003","actor_id":"member-a","device_id":"device-a","received_at":100}]},{"choice_id":"${"b".repeat(43)}","outcome":{"op":"set","value":"晒太阳"},"sources":[{"version_id":"v2","mutation_id":"00000000-0000-0000-0000-000000000004","actor_id":"member-b","device_id":"device-b","received_at":110}]}]}],"auto_merged":[],"page_index":1,"continuation":null,"complete":true}
        """.trimIndent().toByteArray(Charsets.UTF_8)
        val responder = thread(name = "lezi-conflict-continuation-test") {
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
            val fetched = loopbackBackend().fetchConflictSnapshotPage(
                testSession(server),
                "00000000-0000-0000-0000-000000000010",
                ConflictSnapshotPageRequest.Continuation(token, continuation),
            )
            assertThat(fetched.snapshot.pageIndex).isEqualTo(1)
            assertThat(fetched.encodedBytes).isEqualTo(body.size)
            assertThat(captured.single().lineSequence().first()).startsWith(
                "GET /v1/conflicts/00000000-0000-0000-0000-000000000010" +
                    "?snapshot_token=$token&continuation=$continuation ",
            )
        } finally {
            server.close()
            responder.join(2_000)
        }
    }

    @Test
    fun fetchConflictSnapshot_parsesCompleteRecordSnapshotWithoutLoss() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val captured = mutableListOf<String>()
        val body = """
            {
              "contract":"conflict_snapshot_v2",
              "conflict_id":"00000000-0000-0000-0000-000000000010",
              "entity_type":"record",
              "client_uuid":"00000000-0000-0000-0000-000000000001",
              "snapshot_token":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
              "expires_at":2000000,
              "stable":{
                "version_id":"v-stable",
                "base_version":null,
                "root":{
                  "baby_client_uuid":"00000000-0000-0000-0000-000000000002",
                  "type":"formula",
                  "custom_item_client_uuid":null,
                  "timestamp":100,
                  "end_timestamp":null,
                  "note":"a",
                  "payload_json":{"amount_ml":60},
                  "schema_version":2,
                  "updated_at":100,
                  "created_by_membership_id":"member-a"
                },
                "media":[],
                "deleted":false,
                "mutation_id":"00000000-0000-0000-0000-000000000003",
                "actor_id":"member-a",
                "device_id":"device-a",
                "received_at":100
              },
              "branches":[
                {
                  "version_id":"b-1",
                  "base_version":"v-stable",
                  "root":{
                    "baby_client_uuid":"00000000-0000-0000-0000-000000000002",
                    "type":"formula",
                    "custom_item_client_uuid":null,
                    "timestamp":100,
                    "end_timestamp":null,
                    "note":null,
                    "payload_json":{"amount_ml":60},
                    "schema_version":2,
                    "updated_at":110,
                    "created_by_membership_id":"member-b"
                  },
                  "media":[{
                    "media_uuid":"00000000-0000-0000-0000-000000000004",
                    "role":"log",
                    "sha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                    "byte_size":12,
                    "mime":"image/jpeg",
                    "width":1,
                    "height":1
                  }],
                  "deleted":false,
                  "mutation_id":"00000000-0000-0000-0000-000000000005",
                  "actor_id":"member-b",
                  "device_id":"device-b",
                  "received_at":110
                }
              ],
              "conflicting":[{
                "path":"/note",
                "candidates":[{
                  "choice_id":"choice-note-null",
                  "outcome":{"op":"set","value":null},
                  "sources":[{
                    "version_id":"b-1",
                    "mutation_id":"00000000-0000-0000-0000-000000000005",
                    "actor_id":"member-b",
                    "device_id":"device-b",
                    "received_at":110
                  }]
                },{
                  "choice_id":"choice-note-text",
                  "outcome":{"op":"set","value":"branch"},
                  "sources":[{
                    "version_id":"v-stable",
                    "mutation_id":"00000000-0000-0000-0000-000000000003",
                    "actor_id":"member-a",
                    "device_id":"device-a",
                    "received_at":100
                  }]
                }]
              }],
              "auto_merged":[{
                "path":"/_mutation.deleted",
                "outcome":{"op":"set","value":false},
                "sources":[{
                  "version_id":"v-stable",
                  "mutation_id":"00000000-0000-0000-0000-000000000003",
                  "actor_id":"member-a",
                  "device_id":"device-a",
                  "received_at":100
                }]
              }],
              "page_index":0,
              "continuation":null,
              "complete":true
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
            val fetched = loopbackBackend().fetchConflictSnapshotPage(
                testSession(server),
                "00000000-0000-0000-0000-000000000010",
                ConflictSnapshotPageRequest.First,
            )
            val detail = fetched.snapshot
            assertThat(detail.conflictId)
                .isEqualTo("00000000-0000-0000-0000-000000000010")
            assertThat(detail.snapshotToken)
                .isEqualTo("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
            assertThat(detail.entityType).isEqualTo(ConflictRootType.Record)
            assertThat(detail.stable.versionId).isEqualTo("v-stable")
            assertThat(detail.stable.root).isInstanceOf(ConflictRoot.Record::class.java)
            assertThat(detail.branches.single().media.single().role).isEqualTo("log")
            assertThat(detail.conflicting.single().path).isEqualTo("/note")
            assertThat(detail.conflicting.single().candidates.first().choiceId)
                .isEqualTo("choice-note-null")
            assertThat(
                (detail.conflicting.single().candidates.first().outcome as ConflictOutcome.Set)
                    .value,
            ).isEqualTo(JsonNull)
            assertThat(detail.autoMerged.single().sources.single().actorId)
                .isEqualTo("member-a")
            assertThat(detail.complete).isTrue()
            assertThat(detail.continuation).isNull()
            assertThat(fetched.encodedBytes).isEqualTo(body.size)
            assertThat(captured.single().lineSequence().first())
                .startsWith("GET /v1/conflicts/00000000-0000-0000-0000-000000000010 ")
            assertThat(captured.single()).contains("Authorization: Bearer family-token")
        } finally {
            server.close()
            responder.join(2_000)
        }
    }

    @Test
    fun resolveConflict_sendsChoiceOnlyCommandAndParsesAcceptedTerminal() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val captured = mutableListOf<String>()
        val body = """
            {
              "status":"accepted",
              "resolution_mutation_id":"00000000-0000-0000-0000-000000000020",
              "stable_version_id":"v-resolved",
              "stable_root":{
                "baby_client_uuid":"00000000-0000-0000-0000-000000000002",
                "type":"formula",
                "custom_item_client_uuid":null,
                "timestamp":100,
                "end_timestamp":null,
                "note":"chosen",
                "payload_json":{"amount_ml":60},
                "schema_version":2,
                "updated_at":120,
                "created_by_membership_id":"member-a"
              },
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
              ],
              "replay":false
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
                    snapshotToken = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    resolutionMutationId = "00000000-0000-0000-0000-000000000020",
                    choices = listOf(
                        ConflictResolutionChoice("/note", NOTE_CHOICE_ID),
                        ConflictResolutionChoice("/timestamp", TIMESTAMP_CHOICE_ID),
                    ),
                ),
            )
            assertThat(result).isInstanceOf(ConflictResolveResult.Accepted::class.java)
            val accepted = result as ConflictResolveResult.Accepted
            assertThat(accepted.stableVersionId).isEqualTo("v-resolved")
            assertThat(accepted.stableRootJson).contains("chosen")
            assertThat(accepted.stableMedia).hasSize(1)
            assertThat(accepted.stableMedia.single().mediaUuid)
                .isEqualTo("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee")
            assertThat(accepted.resolutionMutationId)
                .isEqualTo("00000000-0000-0000-0000-000000000020")
            assertThat(accepted.replay).isFalse()
            val request = captured.single()
            assertThat(request.lineSequence().first())
                .startsWith("POST /v1/conflicts/c-1/resolve ")
            assertThat(request).contains(
                """"snapshot_token":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"""",
            )
            assertThat(request).contains(
                """"resolution_mutation_id":"00000000-0000-0000-0000-000000000020"""",
            )
            assertThat(request).contains(
                """"choices":[{"path":"/note","choice_id":"$NOTE_CHOICE_ID"},{"path":"/timestamp","choice_id":"$TIMESTAMP_CHOICE_ID"}]""",
            )
            assertThat(request).doesNotContain("expected_stable_version")
            assertThat(request).doesNotContain("expected_branch_versions")
            assertThat(request).doesNotContain("resolved_root")
            assertThat(request).doesNotContain("resolved_media")
            assertThat(request).doesNotContain("conflict_choices")
        } finally {
            server.close()
            responder.join(2_000)
        }
    }

    @Test
    fun resolveConflict_parsesClosedRejectedTerminal() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val body = """
            {
              "status":"rejected",
              "resolution_mutation_id":"00000000-0000-0000-0000-000000000021",
              "error":{"code":"snapshot_stale","retryable":false}
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
                    snapshotToken = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    resolutionMutationId = "00000000-0000-0000-0000-000000000021",
                    choices = listOf(ConflictResolutionChoice("/note", NOTE_CHOICE_ID)),
                ),
            )
            assertThat(result).isInstanceOf(ConflictResolveResult.Rejected::class.java)
            val rejected = result as ConflictResolveResult.Rejected
            assertThat(rejected.code).isEqualTo("snapshot_stale")
            assertThat(rejected.resolutionMutationId)
                .isEqualTo("00000000-0000-0000-0000-000000000021")
            assertThat(rejected.retryable).isFalse()
        } finally {
            server.close()
            responder.join(2_000)
        }
    }

    @Test
    fun resolveConflict_failsClosedForUnknownOrRetryableRejectedTerminal() = runTest {
        val cases = listOf(
            """{"code":"future_code","retryable":false}""",
            """{"code":"snapshot_stale","retryable":true}""",
        )
        cases.forEach { errorJson ->
            val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            val body = """
                {
                  "status":"rejected",
                  "resolution_mutation_id":"00000000-0000-0000-0000-000000000021",
                  "error":$errorJson
                }
            """.trimIndent().toByteArray(Charsets.UTF_8)
            val responder = thread(name = "lezi-conflict-resolve-invalid-rejection") {
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
                val failure = runCatching {
                    loopbackBackend().resolveConflict(
                        session = testSession(server),
                        conflictId = "c-1",
                        request = ConflictResolveRequest(
                            snapshotToken = "a".repeat(43),
                            resolutionMutationId = "00000000-0000-0000-0000-000000000021",
                            choices = listOf(
                                ConflictResolutionChoice("/note", NOTE_CHOICE_ID),
                            ),
                        ),
                    )
                }.exceptionOrNull()
                assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
            } finally {
                server.close()
                responder.join(2_000)
            }
        }
    }

    @Test
    fun resolveConflict_rejectsNonCanonicalChoiceCommandBeforeNetwork() = runTest {
        val valid = ConflictResolveRequest(
            snapshotToken = "a".repeat(43),
            resolutionMutationId = "00000000-0000-0000-0000-000000000021",
            choices = listOf(ConflictResolutionChoice("/note", NOTE_CHOICE_ID)),
        )
        val cases = listOf(
            valid.copy(snapshotToken = "a".repeat(42)),
            valid.copy(snapshotToken = "f".repeat(64)),
            valid.copy(resolutionMutationId = "00000000-0000-0000-0000-0000000000AB"),
            valid.copy(choices = emptyList()),
            valid.copy(
                choices = (0..64).map { index ->
                    ConflictResolutionChoice("/field${index.toString().padStart(2, '0')}", NOTE_CHOICE_ID)
                },
            ),
            valid.copy(choices = listOf(ConflictResolutionChoice("note", NOTE_CHOICE_ID))),
            valid.copy(choices = listOf(ConflictResolutionChoice("/bad~pointer", NOTE_CHOICE_ID))),
            valid.copy(
                choices = listOf(ConflictResolutionChoice("/${"界".repeat(342)}", NOTE_CHOICE_ID)),
            ),
            valid.copy(choices = listOf(ConflictResolutionChoice("/note", "choice-note"))),
            valid.copy(
                choices = listOf(
                    ConflictResolutionChoice("/timestamp", TIMESTAMP_CHOICE_ID),
                    ConflictResolutionChoice("/note", NOTE_CHOICE_ID),
                ),
            ),
            valid.copy(
                choices = listOf(
                    ConflictResolutionChoice("/note", NOTE_CHOICE_ID),
                    ConflictResolutionChoice("/note", TIMESTAMP_CHOICE_ID),
                ),
            ),
        )
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        try {
            cases.forEach { request ->
                assertThat(
                    runCatching {
                        loopbackBackend().resolveConflict(testSession(server), "c-1", request)
                    }.exceptionOrNull(),
                ).isInstanceOf(IllegalArgumentException::class.java)
            }
        } finally {
            server.close()
        }
    }
}

private val NOTE_CHOICE_ID = "b".repeat(43)
private val TIMESTAMP_CHOICE_ID = "c".repeat(43)
