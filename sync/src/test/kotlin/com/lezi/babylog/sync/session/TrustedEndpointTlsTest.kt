package com.lezi.babylog.sync.session
import com.google.common.truth.Truth.assertThat
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TrustedEndpointTlsTest {
    @Test
    fun realTlsHandshakeRequiresApprovalThenAcceptsThePinAndBlocksAChangedKey() = runTest {
        val root = Files.createTempDirectory("lezi-android-tls-")
        var server: Process? = null
        try {
            val port = freePort()
            val first = generateCertificate(root.resolve("first"))
            server = startServer(root, port, first)
            awaitServerStart()
            val probe = HttpSetupProbe(DefaultSetupHttpTransport(), DefaultTlsPeerInspector())
            val origin = "https://localhost:$port"

            check(
                DefaultTlsPeerInspector().inspect(TrustedEndpointProfile.systemPki(origin)) != null,
            ) { "TLS peer inspection rejected a matching self-signed localhost certificate" }

            val approval = awaitApproval(probe, origin)
            val trusted = approval.candidate.trustedEndpoint()
            assertThat(probe.probe(origin, trusted)).isEqualTo(
                SetupProbeResult.Ready(trusted, SetupFamilyState.Empty),
            )

            server.destroyForcibly().waitFor()
            server = null
            val second = generateCertificate(root.resolve("second"))
            server = startServer(root, port, second)
            awaitServerStart()

            assertThat(probe.probe(origin, trusted))
                .isEqualTo(SetupProbeResult.Failed.CertificateChanged)
            server.destroyForcibly().waitFor()
            server = null

            val wrongHost = generateCertificate(root.resolve("wrong-host"), "other.example")
            server = startServer(root, port, wrongHost)
            awaitServerStart()

            assertThat(probe.probe(origin))
                .isEqualTo(SetupProbeResult.Failed.Unreachable)
        } finally {
            server?.destroyForcibly()?.waitFor()
            Files.walk(root).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private suspend fun awaitApproval(
        probe: SetupProbe,
        origin: String,
    ): SetupProbeResult.CertificateApprovalRequired {
        var last: SetupProbeResult? = null
        repeat(40) {
            val result = probe.probe(origin)
            last = result
            if (result is SetupProbeResult.CertificateApprovalRequired) return result
            Thread.sleep(50)
        }
        error("self-signed TLS endpoint never produced a trust candidate; last=$last")
    }

    private fun generateCertificate(
        directory: Path,
        dnsName: String = "localhost",
    ): Pair<Path, Path> {
        Files.createDirectories(directory)
        val certificate = directory.resolve("server.crt")
        val privateKey = directory.resolve("server.key")
        val process = ProcessBuilder(
            "openssl",
            "req",
            "-x509",
            "-newkey",
            "rsa:2048",
            "-sha256",
            "-days",
            "30",
            "-nodes",
            "-subj",
            "/CN=$dnsName",
            "-addext",
            "subjectAltName=DNS:$dnsName",
            "-keyout",
            privateKey.toString(),
            "-out",
            certificate.toString(),
        ).redirectOutput(directory.resolve("openssl.out").toFile())
            .redirectError(directory.resolve("openssl.err").toFile())
            .start()
        check(process.waitFor() == 0) { "openssl certificate generation failed" }
        return certificate to privateKey
    }

    private fun startServer(
        root: Path,
        port: Int,
        identity: Pair<Path, Path>,
    ): Process {
        val response = root.resolve("v1/setup-status")
        Files.createDirectories(response.parent)
        Files.write(
            response,
            """{"protocol_version":1,"capabilities":["trusted_https_endpoint_v1","device_sessions_v1","membership_devices_v1","atomic_bundle","record_membership_author"],"family_state":"empty"}"""
                .toByteArray(),
        )
        return ProcessBuilder(
            "openssl",
            "s_server",
            "-accept",
            port.toString(),
            "-cert",
            identity.first.toString(),
            "-key",
            identity.second.toString(),
            "-WWW",
            "-quiet",
        ).directory(root.toFile())
            .redirectOutput(root.resolve("server.out").toFile())
            .redirectError(root.resolve("server.err").toFile())
            .start()
    }

    private fun awaitServerStart() {
        Thread.sleep(200)
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
}
