package com.lezi.babylog.sync.backend
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.Socket
import java.net.URL
import java.security.cert.Certificate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLHandshakeException
import kotlin.concurrent.thread
import kotlinx.coroutines.test.runTest
import org.junit.Test
import com.lezi.babylog.sync.AppUpdateMetadata
import com.lezi.babylog.sync.FamilyDevice
import com.lezi.babylog.sync.FamilyMember
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.SyncSession
import com.lezi.babylog.sync.session.TrustedEndpointProfile
import com.lezi.babylog.sync.session.normalizeFamilyNameForWire
import com.lezi.babylog.sync.session.requireDeviceName
import com.lezi.babylog.sync.session.requireMemberDisplayName

class HttpSyncBackendReconcileAtomicTest {
    @Test
    fun reconcilePostsFrozenUnitsAndParsesCompleteTypedVerdicts() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val captured = CompletableFuture<String>()
        val responder = thread(name = "lezi-reconcile-test-server") {
            server.accept().use { socket ->
                captured.complete(readRequest(socket))
                val body =
                    """{"generation":"generation-a","cursor":9,"results":[{"entity_type":"record","client_uuid":"r1","request_content_hash":"hash-r1","disposition":"adopt_remote","reason":"server_lww_winner","remote_content_hash":"remote-hash","remote_root":{"type":"record","client_uuid":"r1","updated_at":2,"deleted_at":null,"payload":{"baby_client_uuid":"b1"}},"remote_media":[]}]}"""
                        .toByteArray(Charsets.UTF_8)
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
            val result = loopbackBackend().reconcile(
                testSession(server),
                listOf(
                    ReconcileUnitDraft(
                        contentHash = "hash-r1",
                        root = SyncEntity(
                            type = "record",
                            clientUuid = "r1",
                            payloadJson = """{"baby_client_uuid":"b1"}""",
                            updatedAt = 1,
                        ),
                    ),
                ),
            )

            assertThat(result.cursor).isEqualTo(9)
            assertThat(result.results.single().disposition)
                .isEqualTo(AuthorityDisposition.AdoptRemote)
            assertThat(result.results.single().remoteRoot?.updatedAt).isEqualTo(2)
            val request = captured.get(2, TimeUnit.SECONDS)
            assertThat(request).startsWith("POST /v1/reconcile ")
            assertThat(request).contains("Authorization: Bearer family-token")
            assertThat(request.substringAfter("\n\n")).contains("\"generation\":\"generation-a\"")
            assertThat(request.substringAfter("\n\n")).doesNotContain("media_bytes")
        } finally {
            server.close()
            responder.join(2_000)
        }
    }

    @Test
    fun reconcileRejectsACursorOlderThanThePulledCheckpoint() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val responder = thread(name = "lezi-reconcile-stale-cursor-test-server") {
            server.accept().use { socket ->
                readRequest(socket)
                val body =
                    """{"generation":"generation-a","cursor":8,"results":[{"entity_type":"record","client_uuid":"r1","request_content_hash":"hash-r1","disposition":"confirmed","reason":"canonical_equivalent","remote_root":null,"remote_media":[]}]}"""
                        .toByteArray(Charsets.UTF_8)
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
                loopbackBackend().reconcile(
                    testSession(server).copy(pullCursor = 9),
                    listOf(
                        ReconcileUnitDraft(
                            contentHash = "hash-r1",
                            root = SyncEntity(
                                type = "record",
                                clientUuid = "r1",
                                payloadJson = """{"baby_client_uuid":"b1"}""",
                                updatedAt = 1,
                            ),
                        ),
                    ),
                )
            }.exceptionOrNull()

            assertThat(failure).isInstanceOf(AuthorityProofException::class.java)
            assertThat(failure).hasCauseThat().hasMessageThat().contains("游标")
        } finally {
            server.close()
            responder.join(2_000)
        }
    }

    @Test
    fun reconcileRejectsANonMediaEntitySmuggledInsideRemoteMedia() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val responder = thread(name = "lezi-reconcile-smuggled-media-test-server") {
            server.accept().use { socket ->
                readRequest(socket)
                val body =
                    """{"generation":"generation-a","cursor":9,"results":[{"entity_type":"record","client_uuid":"r1","request_content_hash":"hash-r1","disposition":"adopt_remote","reason":"server_lww_winner","remote_root":{"type":"record","client_uuid":"r1","updated_at":2,"deleted_at":null,"payload":{"baby_client_uuid":"b1"}},"remote_media":[{"type":"record","client_uuid":"r2","updated_at":2,"deleted_at":null,"payload":{"baby_client_uuid":"b1"}}]}]}"""
                        .toByteArray(Charsets.UTF_8)
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
                loopbackBackend().reconcile(
                    testSession(server),
                    listOf(
                        ReconcileUnitDraft(
                            contentHash = "hash-r1",
                            root = SyncEntity(
                                type = "record",
                                clientUuid = "r1",
                                payloadJson = """{"baby_client_uuid":"b1"}""",
                                updatedAt = 1,
                            ),
                        ),
                    ),
                )
            }.exceptionOrNull()

            assertThat(failure).isInstanceOf(AuthorityProofException::class.java)
            assertThat(failure).hasCauseThat().hasMessageThat().contains("必须是 media")
        } finally {
            server.close()
            responder.join(2_000)
        }
    }

    @Test
    fun reconcileRejectsConfirmedWithoutCompleteCanonicalEvidence() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val responder = thread(name = "lezi-reconcile-incomplete-confirmed-test-server") {
            server.accept().use { socket ->
                readRequest(socket)
                val body =
                    """{"generation":"generation-a","cursor":9,"results":[{"entity_type":"record","client_uuid":"r1","request_content_hash":"hash-r1","disposition":"confirmed","reason":"canonical_equivalent","remote_root":null,"remote_media":[]}]}"""
                        .toByteArray(Charsets.UTF_8)
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
                loopbackBackend().reconcile(
                    testSession(server),
                    listOf(
                        ReconcileUnitDraft(
                            contentHash = "hash-r1",
                            root = SyncEntity(
                                type = "record",
                                clientUuid = "r1",
                                payloadJson = """{"baby_client_uuid":"b1"}""",
                                updatedAt = 1,
                            ),
                        ),
                    ),
                )
            }.exceptionOrNull()

            assertThat(failure).isInstanceOf(AuthorityProofException::class.java)
            assertThat(failure).hasCauseThat().hasMessageThat().contains("canonical root")
        } finally {
            server.close()
            responder.join(2_000)
        }
    }

    @Test
    fun reconcileRejectsBabyManifestThatDoesNotMatchTheAvatarPointer() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val responder = thread(name = "lezi-reconcile-avatar-pointer-test-server") {
            server.accept().use { socket ->
                readRequest(socket)
                val body =
                    """{"generation":"generation-a","cursor":9,"results":[{"entity_type":"baby","client_uuid":"b1","request_content_hash":"hash-b1","disposition":"adopt_remote","reason":"server_lww_winner","remote_content_hash":"remote-hash","remote_root":{"type":"baby","client_uuid":"b1","updated_at":2,"deleted_at":null,"payload":{"avatar_media_uuid":"a1"}},"remote_media":[{"type":"media","client_uuid":"a2","updated_at":2,"deleted_at":null,"payload":{"kind":"avatar","baby_client_uuid":"b1"}}]}]}"""
                        .toByteArray(Charsets.UTF_8)
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
                loopbackBackend().reconcile(
                    testSession(server),
                    listOf(
                        ReconcileUnitDraft(
                            contentHash = "hash-b1",
                            root = SyncEntity(
                                type = "baby",
                                clientUuid = "b1",
                                payloadJson = """{"avatar_media_uuid":null}""",
                                updatedAt = 1,
                            ),
                        ),
                    ),
                )
            }.exceptionOrNull()

            assertThat(failure).isInstanceOf(AuthorityProofException::class.java)
            assertThat(failure).hasCauseThat().hasMessageThat().contains("avatar_media_uuid")
        } finally {
            server.close()
            responder.join(2_000)
        }
    }

    @Test
    fun stalledMediaUploadDisconnectsAndFailsWithIOException() = runTest {
        val connection = BlockingUploadConnection()
        val backend = HttpSyncBackend(
            connectionFactory = SyncHttpConnectionFactory { connection },
            uploadWriteStallTimeoutMillis = 25,
        )
        val session = SyncSession(
            serverHost = "family.example.com",
            serverPort = 8765,
            serverScheme = "https",
            familyId = "family",
            membershipId = "membership",
            deviceId = "device",
            accessToken = "access-token",
            pullGeneration = "generation",
        )

        val failure = runCatching {
            backend.putBundleMedia(
                session = session,
                bundleId = "bundle",
                clientUuid = "media",
                source = TestMediaUploadSource(byteArrayOf(1)),
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(SocketTimeoutException::class.java)
        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(connection.disconnected.get()).isTrue()
    }

    @Test
    fun commitRejectsMalformedCanonicalRecordAuthors() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val responder = thread(name = "lezi-push-author-test-server") {
            runCatching {
                server.accept().use { socket ->
                    readRequest(socket)
                    val body =
                        """{"bundle_id":"b1","status":"committed","applied":1,"cursor":1,"record_authors":[{"client_uuid":"r1","created_by_membership_id":"membership-a"},{"client_uuid":"","created_by_membership_id":"forged"},{"client_uuid":"r2"}]}"""
                            .toByteArray(Charsets.UTF_8)
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
        }

        try {
            val failure = runCatching {
                loopbackBackend().commitBundle(testSession(server), "b1")
            }.exceptionOrNull()

            assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(failure).hasMessageThat().contains("record_authors")
        } finally {
            server.close()
            responder.join(2_000)
        }
    }

    @Test
    fun stagePutAndCommitBundleFollowAtomicEndpoints() = runTest {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val seen = mutableListOf<String>()
        val responder = thread(name = "lezi-bundle-test-server") {
            repeat(3) {
                runCatching {
                    server.accept().use { socket ->
                        val request = readRequest(socket)
                        seen += request
                        val body = when {
                            request.startsWith("POST /v1/bundles ") ->
                                """{"bundle_id":"b1","status":"staging","missing_media":["m1"],"staged_media":[]}"""
                            request.startsWith("PUT /v1/bundles/b1/media/m1 ") ->
                                """{"bundle_id":"b1","status":"staging","missing_media":[],"staged_media":["m1"]}"""
                            else ->
                                """{"bundle_id":"b1","status":"committed","applied":2,"cursor":9,"record_authors":[{"client_uuid":"r1","created_by_membership_id":"membership-a"}]}"""
                        }.toByteArray(Charsets.UTF_8)
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
            }
        }

        try {
            val backend = loopbackBackend()
            val session = testSession(server)
            val draft = AtomicBundleDraft(
                bundleId = "b1",
                root = SyncEntity(
                    type = "record",
                    clientUuid = "r1",
                    payloadJson = """{"baby_client_uuid":"baby"}""",
                    updatedAt = 1,
                ),
                media = listOf(
                    SyncEntity(
                        type = "media",
                        clientUuid = "m1",
                        payloadJson = """{"kind":"log","record_client_uuid":"r1","byte_size":2}""",
                        updatedAt = 1,
                    ),
                ),
            )
            val staged = backend.stageBundle(session, draft)
            assertThat(staged.missingMedia).containsExactly("m1")
            val afterPut = backend.putBundleMedia(
                session,
                "b1",
                "m1",
                TestMediaUploadSource(byteArrayOf(1, 2)),
            )
            assertThat(afterPut.missingMedia).isEmpty()
            val committed = backend.commitBundle(session, "b1")
            assertThat(committed.status).isEqualTo("committed")
            assertThat(committed.cursor).isEqualTo(9)
            assertThat(committed.recordAuthors).containsExactly(
                CanonicalRecordAuthor("r1", "membership-a"),
            )
            assertThat(seen[0].lineSequence().first()).startsWith("POST /v1/bundles ")
            assertThat(seen[1].lineSequence().first())
                .startsWith("PUT /v1/bundles/b1/media/m1 ")
            assertThat(seen[1]).contains("Content-Length: 2")
            assertThat(seen[1].lowercase()).doesNotContain("transfer-encoding")
            assertThat(seen[2].lineSequence().first())
                .startsWith("POST /v1/bundles/b1/commit ")
        } finally {
            server.close()
            responder.join(2_000)
        }
    }
}
