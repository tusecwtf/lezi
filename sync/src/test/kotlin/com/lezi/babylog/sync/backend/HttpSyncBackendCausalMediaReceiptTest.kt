package com.lezi.babylog.sync.backend

import com.google.common.truth.Truth.assertThat
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.test.runTest
import org.junit.Test

class HttpSyncBackendCausalMediaReceiptTest {
    @Test
    fun restoredMetadataIsNotUsedAsAnHttpHeader() = runTest {
        for (mime in listOf(null, "", "  ", "\r\nX-Injected: true", "😀".repeat(255))) {
            val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            val captured = CompletableFuture<String>()
            val responder = receiptResponder(server,
                """{"media_uuid":"$MEDIA_ID","status":"staged","byte_size":3,"sha256":"$SHA","expires_at":999}""",
                captured)
            try {
                loopbackBackend().putCausalMediaPreimage(testSession(server), MEDIA_ID,
                    TestMediaUploadSource(byteArrayOf(1, 2, 3), mime), SHA)
                val request = captured.get(2, TimeUnit.SECONDS)
                assertThat(request).contains("Content-Type: application/octet-stream")
                assertThat(request).doesNotContain("X-Injected")
                assertThat(request).contains("X-Lezi-Sync-Capabilities: nursing_plan_intent_v1")
                assertThat(request).doesNotContain(",restore_authority_v1")
            } finally {
                server.close()
                responder.join(2_000)
            }
        }
    }

    @Test
    fun preimageUploadReturnsOnlyAnExactClosedDurableReceipt() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val captured = CompletableFuture<String>()
        val responder = receiptResponder(
            server,
            """{"media_uuid":"$MEDIA_ID","status":"staged","byte_size":3,"sha256":"$SHA","expires_at":999} """,
            captured,
        )

        try {
            val receipt = loopbackBackend().putCausalMediaPreimage(
                testSession(server),
                MEDIA_ID,
                TestMediaUploadSource(byteArrayOf(1, 2, 3), "image/jpeg"),
                SHA,
            )

            assertThat(receipt).isEqualTo(
                CausalMediaPreimageReceipt(MEDIA_ID, "staged", 3, SHA, 999),
            )
            val request = captured.get(2, TimeUnit.SECONDS)
            assertThat(request.lineSequence().first())
                .startsWith("PUT /v1/causal/media/$MEDIA_ID ")
            assertThat(request).contains("X-Lezi-Media-Sha256: $SHA")
        } finally {
            server.close()
            responder.join(2_000)
        }
    }

    @Test
    fun emptyBindPutSendsZeroContentLengthAndAcceptsRealByteSizeReceipt() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val captured = CompletableFuture<String>()
        val responder = receiptResponder(
            server,
            """{"media_uuid":"$MEDIA_ID","status":"staged","byte_size":3,"sha256":"$SHA","expires_at":999}""",
            captured,
        )

        try {
            val receipt = loopbackBackend().putCausalMediaPreimage(
                testSession(server),
                MEDIA_ID,
                com.lezi.babylog.sync.media.EmptyCausalMediaBindSource(
                    declaredByteSize = 3,
                    mime = "image/jpeg",
                ),
                SHA,
            )

            assertThat(receipt).isEqualTo(
                CausalMediaPreimageReceipt(MEDIA_ID, "staged", 3, SHA, 999),
            )
            val request = captured.get(2, TimeUnit.SECONDS)
            assertThat(request).contains("Content-Length: 0")
            assertThat(request).contains("X-Lezi-Media-Sha256: $SHA")
            assertThat(request.substringAfter("\n\n", missingDelimiterValue = "missing")).isEmpty()
        } finally {
            server.close()
            responder.join(2_000)
        }
    }

    @Test
    fun preimageUploadRejectsEveryReceiptShapeAndIdentityDrift() = runTest {
        val responses = listOf(
            """{"media_uuid":"00000000-0000-4000-8000-000000000099","status":"staged","byte_size":3,"sha256":"$SHA","expires_at":999}""",
            """{"media_uuid":"$MEDIA_ID","status":"staged","byte_size":3,"sha256":"$SHA","expires_at":999,"extra":true}""",
            """{"media_uuid":"$MEDIA_ID","status":"unknown","byte_size":3,"sha256":"$SHA","expires_at":999}""",
            """{"media_uuid":"$MEDIA_ID","status":"staged","byte_size":4,"sha256":"$SHA","expires_at":999}""",
            """{"media_uuid":"$MEDIA_ID","status":"staged","byte_size":3,"sha256":"${"ff".repeat(32)}","expires_at":999}""",
            """{"media_uuid":"$MEDIA_ID","status":"staged","byte_size":3,"sha256":"$SHA","expires_at":0}""",
            """{"media_uuid":"$MEDIA_ID","status":"staged","byte_size":3,"sha256":"$SHA"}""",
            """{"media_uuid":"$MEDIA_ID","status":"staged","byte_size":"3","sha256":"$SHA","expires_at":999}""",
        )
        responses.forEach { response ->
            val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            val responder = receiptResponder(server, response)
            try {
                val failure = runCatching {
                    loopbackBackend().putCausalMediaPreimage(
                        testSession(server),
                        MEDIA_ID,
                        TestMediaUploadSource(byteArrayOf(1, 2, 3), "image/jpeg"),
                        SHA,
                    )
                }.exceptionOrNull()

                assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
            } finally {
                server.close()
                responder.join(2_000)
            }
        }
    }

    private fun receiptResponder(
        server: ServerSocket,
        response: String,
        captured: CompletableFuture<String>? = null,
    ) = thread(name = "lezi-causal-media-receipt-server") {
        server.accept().use { socket ->
            captured?.complete(readRequest(socket)) ?: readRequest(socket)
            val body = response.trim().toByteArray(Charsets.UTF_8)
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

    private companion object {
        const val MEDIA_ID = "00000000-0000-4000-8000-000000000020"
        val SHA = "20".repeat(32)
    }
}
