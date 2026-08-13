package com.lezi.babylog.sync.backend

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.lezi.babylog.sync.session.FamilyRole
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class HttpSyncBackendHandshakeTest {
    @Test
    fun handshakeAcceptsTheFrozenGzipPullNegotiation() {
        val decoded = decodeAuthenticatedSyncHandshake(
            validHandshakeJson().withRaw(
                "compression",
                """{"pull_response":["gzip","identity"]}""",
            ),
        )

        assertThat(decoded.compression.pullResponse)
            .containsExactly("gzip", "identity")
    }

    @Test
    fun authenticatedHandshakeAndDirectoryUseFrozenWireContracts() = runTest {
        val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        val requests = mutableListOf<String>()
        val responder = thread(name = "lezi-handshake-contract-server") {
            val bodies = listOf(
                validHandshakeJson().toString(),
                """{"directory_generation":"${"a".repeat(64)}","members":[{"membership_id":"membership-self","display_name":"管理员","role":"owner","is_self":true,"devices":[],"last_sync_at":null}]}""",
            )
            bodies.forEach { response ->
                server.accept().use { socket ->
                    requests += readRequest(socket)
                    val body = response.toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().use { output ->
                        output.write(
                            ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n")
                                .toByteArray(Charsets.US_ASCII),
                        )
                        output.write(body)
                    }
                }
            }
        }
        val session = testSession(server).copy(membershipId = "membership-self")
        val backend = loopbackBackend()

        try {
            val handshake = backend.authenticatedHandshake(session)
            val directory = backend.memberDirectory(session)

            assertThat(handshake.principal).isEqualTo(
                SyncHandshakePrincipal("membership-self", "device", FamilyRole.Owner),
            )
            assertThat(handshake.directoryGeneration).isEqualTo("a".repeat(64))
            assertThat(handshake.capabilities).containsAtLeastElementsIn(
                REQUIRED_CAUSAL_WIRE_CAPABILITIES,
            )
            assertThat(backend.supportsCausalWire()).isTrue()
            assertThat(directory.generation).isEqualTo("a".repeat(64))
            assertThat(directory.members.single().isSelf).isTrue()
            assertThat(requests[0].lineSequence().first())
                .startsWith("POST /v1/sync/handshake ")
            assertThat(requests[0]).contains("Authorization: Bearer family-token")
            assertThat(requests[0].substringAfter("\n\n")).isEqualTo(
                """{"protocol_version":1,"required_capabilities":["causal_sync_v2"]}""",
            )
            assertThat(requests[1].lineSequence().first())
                .startsWith("GET /v1/family/members ")
        } finally {
            server.close()
            responder.join(2_000)
        }
    }

    @Test
    fun terminalHandshakeRejectionMapsWithoutAnyFollowupRequest() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val responder = thread(name = "lezi-handshake-rejection-server") {
            server.accept().use { socket ->
                readRequest(socket)
                val body = """{"status":"rejected","error":{"code":"not_ready","retryable":false}}"""
                    .toByteArray(Charsets.UTF_8)
                socket.getOutputStream().use { output ->
                    output.write(
                        ("HTTP/1.1 503 Service Unavailable\r\nContent-Type: application/json\r\n" +
                            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n")
                            .toByteArray(Charsets.US_ASCII),
                    )
                    output.write(body)
                }
            }
        }

        try {
            val failure = runCatching {
                loopbackBackend().authenticatedHandshake(
                    testSession(server).copy(membershipId = "membership-self"),
                )
            }.exceptionOrNull()
            assertThat(failure).isInstanceOf(SyncHandshakeRejectedException::class.java)
            assertThat((failure as SyncHandshakeRejectedException).code).isEqualTo("not_ready")
        } finally {
            server.close()
            responder.join(2_000)
        }
    }

    @Test
    fun successCodecRejectsUnknownMissingWrongDuplicateAndInvalidBounds() {
        val valid = validHandshakeJson()
        val principal = valid.getValue("principal").jsonObject
        val limits = valid.getValue("limits").jsonObject
        val cases = mapOf(
            "unknown top-level" to JsonObject(valid + ("future" to JsonPrimitive(true))),
            "missing field" to JsonObject(valid - "limits"),
            "wrong type" to JsonObject(valid + ("ready" to JsonPrimitive("true"))),
            "duplicate capability" to valid.withRaw(
                "capabilities",
                """["causal_sync_v2","causal_sync_v2"]""",
            ),
            "unexpected extra capability" to valid.withRaw(
                "capabilities",
                """["causal_sync_v2","future_extra"]""",
            ),
            "mixed source capabilities" to valid.withRaw(
                "capabilities",
                """["causal_versions","source_relations","wake_observation","causal_sync_v2"]""",
            ),
            "unknown nested" to JsonObject(
                valid + ("principal" to JsonObject(principal + ("future" to JsonPrimitive("x")))),
            ),
            "invalid pull bound" to JsonObject(
                valid + ("limits" to JsonObject(limits + ("pull_page_max_entities" to JsonPrimitive(0)))),
            ),
            "invalid encoded bound" to JsonObject(
                valid + ("limits" to JsonObject(limits + ("pull_page_max_encoded_bytes" to JsonPrimitive(0)))),
            ),
            "encoded below decoded" to JsonObject(
                valid + ("limits" to JsonObject(limits + ("pull_page_max_encoded_bytes" to JsonPrimitive(1024)))),
            ),
            "invalid page bound" to JsonObject(
                valid + ("limits" to JsonObject(limits + ("pull_max_pages" to JsonPrimitive(0)))),
            ),
            "invalid compression" to valid.withRaw(
                "compression",
                """{"pull_response":["br","identity"]}""",
            ),
        )

        cases.forEach { (name, body) ->
            val failure = runCatching { decodeAuthenticatedSyncHandshake(body) }.exceptionOrNull()
            assertWithMessage(name).that(failure)
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun terminalCodecRejectsWrongShapeRetryabilityAndHttpMapping() {
        val cases = mapOf(
            "wrong status" to SyncHttpException(
                503,
                """{"status":"pending","error":{"code":"not_ready","retryable":false}}""",
            ),
            "retryable" to SyncHttpException(
                503,
                """{"status":"rejected","error":{"code":"not_ready","retryable":true}}""",
            ),
            "wrong http" to SyncHttpException(
                409,
                """{"status":"rejected","error":{"code":"not_ready","retryable":false}}""",
            ),
            "unknown key" to SyncHttpException(
                503,
                """{"status":"rejected","error":{"code":"not_ready","retryable":false},"future":1}""",
            ),
        )

        cases.forEach { (name, failure) ->
            val decoded = runCatching { decodeSyncHandshakeFailure(failure) }.exceptionOrNull()
            assertWithMessage(name).that(decoded)
                .isNotInstanceOf(SyncHandshakeRejectedException::class.java)
        }
    }

    private fun validHandshakeJson(): JsonObject = Json.parseToJsonElement(
        """{"protocol_version":1,"server_version":"0.4.0","ready":true,"capabilities":["causal_sync_v2"],"principal":{"membership_id":"membership-self","device_id":"device","role":"owner"},"directory_generation":"${"a".repeat(64)}","limits":{"pull_page_max_entities":200,"pull_page_max_encoded_bytes":9437184,"pull_page_max_decoded_bytes":8388608,"pull_max_pages":500,"commit_batch_max_units":64,"media_max_bytes":10485760},"compression":{"pull_response":["gzip","identity"]},"retry_hints":{"retry_after":true}}""",
    ).jsonObject

    private fun JsonObject.withRaw(key: String, raw: String): JsonObject = JsonObject(
        this + (key to Json.parseToJsonElement(raw)),
    )
}
