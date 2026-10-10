package com.lezi.babylog.sync.backend

import com.google.common.truth.Truth.assertThat
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class HttpSyncBackendCurrentSourceRelationsTest {
    @Test
    fun readsAuthenticatedBoundedCurrentProjectionWithExactAuthorityAndScope() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val captured = mutableListOf<String>()
        val responder = thread {
            server.accept().use { socket ->
                captured += readRequest(socket)
                respondCurrent(socket, """{"protocol_version":1,"family_id":"family","generation":"generation-a","head_rev":42,"requested_record_client_uuids":["a","b"],"records":[{"record_client_uuid":"a","record_state":"live","relation_id":"relation"},{"record_client_uuid":"b","record_state":"deleted","relation_id":"relation"}],"source_relations":[{"relation_id":"relation","display_client_uuid":"a","source_client_uuids":["b"],"media_retained":true,"auto_aligned":false}]}""")
            }
        }
        try {
            val session = testSession(server)
            val result = loopbackBackend().readCurrentSourceRelations(session,
                CurrentSourceRelationsRequest(session.familyId, session.pullGeneration, listOf("a", "b")))
            assertThat(result.headRev).isEqualTo(42)
            assertThat(result.records.last().recordState).isEqualTo("deleted")
            assertThat(session.pullCursor).isEqualTo(0)
        } finally {
            server.close()
            responder.join(2_000)
        }
        assertThat(captured.single()).startsWith("POST /v1/source-relations/current ")
        assertThat(captured.single()).contains("Authorization: Bearer family-token")
        val body = Json.parseToJsonElement(captured.single().substringAfter("\n\n")).jsonObject
        assertThat(body.keys).containsExactly("protocol_version", "family_id", "generation", "record_client_uuids")
    }

    @Test
    fun rejectsDeclaredCurrentProjectionLargerThanTwoMiB() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val responder = thread {
            server.accept().use { socket ->
                readRequest(socket)
                socket.getOutputStream().use { output ->
                    output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                        "Content-Length: ${MAX_CURRENT_SOURCE_RELATION_RESPONSE_BYTES + 1}\r\nConnection: close\r\n\r\n")
                        .toByteArray(Charsets.US_ASCII))
                }
            }
        }
        try {
            val session = testSession(server)
            val failure = runCatching { loopbackBackend().readCurrentSourceRelations(session,
                CurrentSourceRelationsRequest(session.familyId, session.pullGeneration, listOf("a"))) }.exceptionOrNull()
            assertThat(failure).isInstanceOf(SyncResponseTooLargeException::class.java)
        } finally {
            server.close()
            responder.join(2_000)
        }
    }

    @Test
    fun oldServer404StaysExplicitAndDoesNotFallBackToWriteReceipt() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val responder = thread {
            server.accept().use { socket ->
                readRequest(socket)
                respondCurrent(socket, """{"detail":"Not Found"}""", "404 Not Found")
            }
        }
        try {
            val session = testSession(server)
            val failure = runCatching { loopbackBackend().readCurrentSourceRelations(session,
                CurrentSourceRelationsRequest(session.familyId, session.pullGeneration, listOf("a"))) }.exceptionOrNull()
            assertThat(failure).isInstanceOf(SyncHttpException::class.java)
            assertThat((failure as SyncHttpException).statusCode).isEqualTo(404)
        } finally {
            server.close()
            responder.join(2_000)
        }
    }
}

private fun respondCurrent(socket: Socket, body: String, status: String = "200 OK") {
    val bytes = body.toByteArray(Charsets.UTF_8)
    socket.getOutputStream().use { output ->
        output.write(("HTTP/1.1 $status\r\nContent-Type: application/json\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
        output.write(bytes)
    }
}
