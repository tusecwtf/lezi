package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.test.runTest
import org.junit.Test

class HomeNetworkPolicyTest {
    private fun config(
        host: String = "nas",
        port: Int = 8765,
        ssids: List<String> = listOf("Home"),
    ) = HomeLanServerConfig(host, port, ssids)

    @Test
    fun httpHealthProbeRejectsRedirectsWithoutFollowing() = runTest {
        val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).apply {
            soTimeout = 1_000
        }
        val requestCount = AtomicInteger()
        val responder = thread(name = "lezi-health-redirect-test-server") {
            runCatching {
                server.accept().use { socket ->
                    requestCount.incrementAndGet()
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) Unit
                    socket.getOutputStream().use { output ->
                        output.write(
                            (
                                "HTTP/1.1 302 Found\r\n" +
                                    "Location: http://${server.inetAddress.hostAddress}:${server.localPort}/health\r\n" +
                                    "Content-Length: 0\r\n" +
                                    "Connection: close\r\n\r\n"
                            ).toByteArray(Charsets.US_ASCII),
                        )
                    }
                }
                server.accept().use { socket ->
                    requestCount.incrementAndGet()
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) Unit
                    val body = """{"status":"ok"}""".toByteArray()
                    socket.getOutputStream().use { output ->
                        output.write(
                            (
                                "HTTP/1.1 200 OK\r\n" +
                                    "Content-Length: ${body.size}\r\n" +
                                    "Connection: close\r\n\r\n"
                            ).toByteArray(Charsets.US_ASCII),
                        )
                        output.write(body)
                    }
                }
            }.onFailure { error ->
                if (error !is SocketTimeoutException) throw error
            }
        }

        try {
            val healthy = HttpHealthProbe().isHealthy(
                "http://${server.inetAddress.hostAddress}:${server.localPort}",
            )

            assertThat(healthy).isFalse()
            responder.join(2_500)
            assertThat(requestCount.get()).isEqualTo(1)
        } finally {
            server.close()
            responder.join(2_000)
        }
    }

    @Test
    fun httpHealthProbeRejectsOversizedSuccessfulBody() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val responder = thread(name = "lezi-health-oversized-test-server") {
            runCatching {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) Unit
                    socket.getOutputStream().use { output ->
                        output.write(
                            (
                                "HTTP/1.1 200 OK\r\n" +
                                    "Content-Length: ${65 * 1024}\r\n" +
                                    "Connection: close\r\n\r\n"
                            ).toByteArray(Charsets.US_ASCII),
                        )
                    }
                }
            }
        }

        try {
            val healthy = HttpHealthProbe().isHealthy(
                "http://${server.inetAddress.hostAddress}:${server.localPort}",
            )

            assertThat(healthy).isFalse()
        } finally {
            server.close()
            responder.join(2_000)
        }
    }

    @Test
    fun httpHealthProbeAcceptsSmallSuccessfulJsonBody() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val responder = thread(name = "lezi-health-success-test-server") {
            runCatching {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) Unit
                    val body =
                        """{"ok":true,"version":"0.3.0","capabilities":["atomic_bundle","record_membership_author"]}"""
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
            val status = HttpHealthProbe().probe(
                "http://${server.inetAddress.hostAddress}:${server.localPort}",
            )
            assertThat(status.ok).isTrue()
            assertThat(status.isCurrentServerContract()).isTrue()
            assertThat(status.version).isEqualTo("0.3.0")
        } finally {
            server.close()
            responder.join(2_000)
        }
    }

    @Test
    fun httpHealthProbeRejectsBodyWithoutCurrentCapabilities() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val responder = thread(name = "lezi-health-incompatible-test-server") {
            runCatching {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) Unit
                    val body = """{"ok":true,"version":"0.2.3"}""".toByteArray(Charsets.UTF_8)
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
            val status = HttpHealthProbe().probe(
                "http://${server.inetAddress.hostAddress}:${server.localPort}",
            )
            assertThat(status.ok).isFalse()
            assertThat(status.isCurrentServerContract()).isFalse()
        } finally {
            server.close()
            responder.join(2_000)
        }
    }

    @Test
    fun policyCachesCurrentServerContractFromSuccessfulProbe() = runTest {
        val probe = RecordingHealthProbe(
            result = true,
            capabilities = setOf(
                CAPABILITY_ATOMIC_BUNDLE,
                CAPABILITY_RECORD_MEMBERSHIP_AUTHOR,
            ),
        )
        val policy = HomeNetworkPolicy(
            networkState = FakeNetworkState(isWifi = true, ssid = "Home"),
            healthProbe = probe,
            clock = FakePolicyClock(),
        )
        assertThat(policy.evaluate(config(), isForeground = true))
            .isEqualTo(HomeNetworkDecision.Allowed)
        assertThat(policy.lastHealthStatus.isCurrentServerContract()).isTrue()
    }

    @Test
    fun currentServerContractAllowsDifferentVersionAndAdditiveCapabilities() {
        val status = HealthStatus(
            ok = true,
            version = "99.7.3",
            capabilities = REQUIRED_SYNC_SERVER_CAPABILITIES + "future_optional_feature",
        )

        assertThat(status.isCurrentServerContract()).isTrue()
    }

    @Test
    fun currentServerContractRejectsMissingRequiredCapability() {
        val status = HealthStatus(
            ok = true,
            version = "99.7.3",
            capabilities = setOf(CAPABILITY_ATOMIC_BUNDLE, "future_optional_feature"),
        )

        assertThat(status.isCurrentServerContract()).isFalse()
    }

    @Test
    fun emptyAddressAndNonWifiNeverProbeServer() = runTest {
        val probe = RecordingHealthProbe(result = true)
        val policy = HomeNetworkPolicy(
            networkState = FakeNetworkState(isWifi = false, ssid = null),
            healthProbe = probe,
            clock = FakePolicyClock(),
        )

        assertThat(policy.evaluate(config(host = ""), isForeground = true))
            .isEqualTo(HomeNetworkDecision.MissingServer)
        assertThat(policy.evaluate(config(ssids = emptyList()), isForeground = true))
            .isEqualTo(HomeNetworkDecision.MissingSsidAllowlist)
        assertThat(policy.evaluate(config(), isForeground = true))
            .isEqualTo(HomeNetworkDecision.NotOnWifi)
        assertThat(probe.calls).isEmpty()
    }

    @Test
    fun ssidNotMatchedBlocksWithoutProbe() = runTest {
        val probe = RecordingHealthProbe(result = true)
        val policy = HomeNetworkPolicy(
            networkState = FakeNetworkState(isWifi = true, ssid = "Cafe"),
            healthProbe = probe,
            clock = FakePolicyClock(),
        )
        assertThat(policy.evaluate(config(ssids = listOf("Home", "Home-5G")), isForeground = true))
            .isEqualTo(HomeNetworkDecision.SsidNotMatched)
        assertThat(probe.calls).isEmpty()
    }

    @Test
    fun unknownSsidBlocksWithoutProbe() = runTest {
        val probe = RecordingHealthProbe(result = true)
        val policy = HomeNetworkPolicy(
            networkState = FakeNetworkState(isWifi = true, ssid = "<unknown ssid>"),
            healthProbe = probe,
            clock = FakePolicyClock(),
        )
        assertThat(policy.evaluate(config(), isForeground = true))
            .isEqualTo(HomeNetworkDecision.SsidUnavailable)
        assertThat(probe.calls).isEmpty()
    }

    @Test
    fun matchedSsidUsesSingleHostPort() = runTest {
        val probe = RecordingHealthProbe(result = true)
        val policy = HomeNetworkPolicy(
            networkState = FakeNetworkState(isWifi = true, ssid = "Home-5G"),
            healthProbe = probe,
            clock = FakePolicyClock(),
        )
        assertThat(
            policy.evaluate(
                config(host = "192.168.50.4", ssids = listOf("Home", "Home-5G")),
                isForeground = true,
            ),
        ).isEqualTo(HomeNetworkDecision.Allowed)
        assertThat(probe.calls).containsExactly("http://192.168.50.4:8765")
    }

    @Test
    fun failedHealthProbeBacksOffBeforeTryingAgain() = runTest {
        val clock = FakePolicyClock(now = 1_000)
        val probe = RecordingHealthProbe(result = false)
        val policy = HomeNetworkPolicy(
            networkState = FakeNetworkState(isWifi = true, ssid = "Home"),
            healthProbe = probe,
            clock = clock,
        )

        assertThat(policy.evaluate(config(host = "nas"), isForeground = true))
            .isEqualTo(HomeNetworkDecision.ServerUnavailable)
        assertThat(policy.evaluate(config(host = "nas"), isForeground = true))
            .isEqualTo(HomeNetworkDecision.BackingOff)
        assertThat(probe.calls).containsExactly("http://nas:8765")

        clock.now += 30_000
        probe.result = true
        assertThat(policy.evaluate(config(host = "nas"), isForeground = true))
            .isEqualTo(HomeNetworkDecision.Allowed)
    }

    @Test
    fun backgroundAutomaticTriggerIsBlocked() = runTest {
        val probe = RecordingHealthProbe(result = true)
        val policy = HomeNetworkPolicy(
            networkState = FakeNetworkState(isWifi = true, ssid = "Home"),
            healthProbe = probe,
            clock = FakePolicyClock(),
        )

        assertThat(policy.evaluate(config(), isForeground = false))
            .isEqualTo(HomeNetworkDecision.Background)
        assertThat(probe.calls).isEmpty()
    }

    @Test
    fun changingServerDoesNotReusePreviousServersBackoff() = runTest {
        val probe = RecordingHealthProbe(result = false)
        val policy = HomeNetworkPolicy(
            networkState = FakeNetworkState(isWifi = true, ssid = "Home"),
            healthProbe = probe,
            clock = FakePolicyClock(now = 1_000),
        )

        assertThat(policy.evaluate(config(host = "old-nas"), isForeground = true))
            .isEqualTo(HomeNetworkDecision.ServerUnavailable)
        probe.result = true

        assertThat(policy.evaluate(config(host = "new-nas"), isForeground = true))
            .isEqualTo(HomeNetworkDecision.Allowed)
        assertThat(probe.calls).containsExactly(
            "http://old-nas:8765",
            "http://new-nas:8765",
        ).inOrder()
    }

    @Test
    fun parseHostPortAcceptsFullUrl() {
        val (h, p) = HomeLanServerConfig.parseHostPort("http://192.168.50.4:8765")
        assertThat(h).isEqualTo("192.168.50.4")
        assertThat(p).isEqualTo(8765)
    }

    @Test
    fun userInputPreservesHttpsAndEmbeddedPort() {
        val config = HomeLanServerConfig.fromUserInput(
            rawHostOrUrl = "https://lezi.home:9443",
            explicitPort = 8765,
            allowedSsids = listOf("Home"),
        )

        assertThat(config.baseUrl).isEqualTo("https://lezi.home:9443")
    }

    @Test
    fun bareHostKeepsExistingHttpsWhenOnlyWifiChanges() {
        val config = HomeLanServerConfig.fromUserInput(
            rawHostOrUrl = "lezi.home",
            explicitPort = 443,
            allowedSsids = listOf("Home-5G"),
            fallbackScheme = "https",
        )

        assertThat(config.baseUrl).isEqualTo("https://lezi.home:443")
    }

    @Test
    fun bareNewHostDefaultsToHttp() {
        val config = HomeLanServerConfig.fromUserInput(
            rawHostOrUrl = "192.168.50.4",
            explicitPort = 8765,
            allowedSsids = emptyList(),
        )

        assertThat(config.baseUrl).isEqualTo("http://192.168.50.4:8765")
    }

    @Test
    fun explicitUnsupportedSchemeIsRejectedInsteadOfDowngraded() {
        val error = runCatching {
            HomeLanServerConfig.fromUserInput(
                rawHostOrUrl = "ftp://lezi.home",
                explicitPort = 8765,
                allowedSsids = emptyList(),
            )
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error).hasMessageThat().contains("HTTP")
    }

    @Test
    fun explicitMalformedOrOutOfRangePortIsRejected() {
        listOf(
            "http://lezi.home:bad",
            "http://lezi.home:",
            "http://lezi.home:0",
            "http://lezi.home:65536",
        ).forEach { address ->
            val error = runCatching {
                HomeLanServerConfig.fromUserInput(
                    rawHostOrUrl = address,
                    explicitPort = 8765,
                    allowedSsids = listOf("Home"),
                )
            }.exceptionOrNull()

            assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun malformedBareHostPortCannotBecomeBracketedPseudoHost() {
        val error = runCatching {
            HomeLanServerConfig.fromUserInput(
                rawHostOrUrl = "lezi.home:bad",
                explicitPort = null,
                allowedSsids = listOf("Home"),
            )
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error).hasMessageThat().doesNotContain("[lezi.home:bad]")
    }

}

private class FakeNetworkState(
    private val isWifi: Boolean,
    private val ssid: String?,
) : NetworkState {
    override fun isWifiConnected(): Boolean = isWifi
    override fun currentWifiSsid(): String? = ssid
}

private class RecordingHealthProbe(
    var result: Boolean,
    var capabilities: Set<String> = REQUIRED_SYNC_SERVER_CAPABILITIES,
) : HealthProbe {
    val calls = mutableListOf<String>()
    override suspend fun probe(baseUrl: String): HealthStatus {
        calls += baseUrl
        return HealthStatus(
            ok = result,
            version = if (result) "test-version" else null,
            capabilities = if (result) capabilities else emptySet(),
        )
    }
}

private class FakePolicyClock(var now: Long = 0) : PolicyClock {
    override fun nowMillis(): Long = now
}
