package com.lezi.babylog.sync.backend

import com.google.common.truth.Truth.assertThat
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.test.runTest
import org.junit.Test

class HttpSyncBackendMediaIdentityTest {
    @Test
    fun pullNegotiatesByteIdentityWithoutRewritingLegacyNullableMime() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val captured = CompletableFuture<String>()
        val responder = thread {
            runCatching {
                server.accept().use { socket ->
                    captured.complete(readRequest(socket))
                    val response = """{"entities":[{"type":"media","client_uuid":"$MEDIA","updated_at":100,"deleted_at":null,"payload":{"kind":"wake","record_client_uuid":"22222222-2222-2222-2222-222222222222","care_plan_client_uuid":null,"baby_client_uuid":null,"mime":null,"width":null,"height":null,"byte_size":3},"rev":1,"media_identity":{"media_uuid":"$MEDIA","role":"wake","sha256":"$SHA","byte_size":3}}],"cursor":1,"generation":"generation-a","page_index":0,"has_more":false,"family_name":null}""".toByteArray()
                    socket.getOutputStream().use { output ->
                        output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                            "Content-Length: ${response.size}\r\nConnection: close\r\n\r\n").toByteArray())
                        output.write(response)
                    }
                }
            }.onFailure(captured::completeExceptionally)
        }
        try {
            val result = loopbackBackend().pull(testSession(server), testPullPage())

            assertThat(captured.get(2, TimeUnit.SECONDS)).contains("X-Lezi-Media-Identity: v1")
            assertThat(result.entities.single().mediaIdentity)
                .isEqualTo(PullMediaIdentity(MEDIA, "wake", SHA, 3))
            assertThat(result.entities.single().payloadJson).contains("\"mime\":null")
        } finally {
            server.close()
            responder.join(2_000)
        }
    }

    @Test
    fun negotiatedIdentityRejectsUnknownKeysNullAndMalformedDigest() = runTest {
        for (identity in listOf(
            "null",
            """{"media_uuid":"$MEDIA","role":"wake","sha256":"$SHA","byte_size":3,"extra":true}""",
            """{"media_uuid":"$MEDIA","role":"wake","sha256":"WRONG","byte_size":3}""",
            """{"media_uuid":"$MEDIA","role":"wake","sha256":"$SHA","byte_size":"3"}""",
        )) {
            val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            val responder = thread {
                runCatching {
                    server.accept().use { socket ->
                        readRequest(socket)
                        val response = """{"entities":[{"type":"media","client_uuid":"$MEDIA","updated_at":100,"deleted_at":null,"payload":{},"rev":1,"media_identity":$identity}],"cursor":1,"generation":"generation-a","page_index":0,"has_more":false,"family_name":null}""".toByteArray()
                        socket.getOutputStream().use { output ->
                            output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                                "Content-Length: ${response.size}\r\nConnection: close\r\n\r\n").toByteArray())
                            output.write(response)
                        }
                    }
                }
            }
            try {
                val failure = runCatching {
                    loopbackBackend().pull(testSession(server), testPullPage())
                }.exceptionOrNull()
                assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
            } finally {
                server.close()
                responder.join(2_000)
            }
        }
    }

    private companion object {
        const val MEDIA = "11111111-1111-1111-1111-111111111111"
        const val SHA = "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81"
    }
}
