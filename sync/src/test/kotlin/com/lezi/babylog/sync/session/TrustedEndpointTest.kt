package com.lezi.babylog.sync.session
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TrustedEndpointTest {
    @Test
    fun boundedResponseReadWorksOnTheMinimumAndroidApiAndPreservesOverflowSignal() {
        val exact = ByteArray(4) { it.toByte() }
        val oversized = ByteArray(7) { it.toByte() }

        assertThat(ByteArrayInputStream(exact).readAtMost(5)).isEqualTo(exact)
        assertThat(ByteArrayInputStream(oversized).readAtMost(5))
            .isEqualTo(oversized.copyOf(5))
    }

    @Test
    fun selfSignedHandshakeReturnsACompleteSpkiFingerprintWithoutSendingHttpBeforeApproval() =
        runTest {
            val endpoint = TrustedEndpointProfile.systemPki("https://192.168.50.4:8765")
            val candidate = CertificateTrustCandidate.fromSpki(
                endpoint = endpoint,
                subjectPublicKeyInfo = ByteArray(32) { it.toByte() },
            )
            val transport = RecordingSetupHttpTransport(
                throwable = UntrustedServerCertificateException(),
            )
            val inspector = RecordingTlsPeerInspector(candidate)
            val probe = HttpSetupProbe(transport, inspector)

            val result = probe.probe(endpoint.origin)

            assertThat(result).isEqualTo(
                SetupProbeResult.CertificateApprovalRequired(candidate),
            )
            assertThat(candidate.fingerprint.split(":"))
                .hasSize(32)
            assertThat(inspector.endpoints).containsExactly(endpoint)
            assertThat(transport.requests).containsExactly(
                SetupHttpRequest(endpoint, "/v1/setup-status"),
            )
        }

    @Test
    fun acceptedSpkiUsesOnlyThePinnedTransportAndMismatchHardBlocksWithoutRediscovery() = runTest {
        val candidate = CertificateTrustCandidate.fromSpki(
            endpoint = TrustedEndpointProfile.systemPki("https://192.168.50.4:8765"),
            subjectPublicKeyInfo = "stable-public-key".toByteArray(),
        )
        val pinned = candidate.trustedEndpoint()
        val transport = RecordingSetupHttpTransport(
            throwable = SpkiPinMismatchException(),
        )
        val inspector = RecordingTlsPeerInspector(candidate)
        val probe = HttpSetupProbe(transport, inspector)

        val result = probe.probe(pinned.origin, pinned)

        assertThat(result).isEqualTo(SetupProbeResult.Failed.CertificateChanged)
        assertThat(transport.requests).containsExactly(
            SetupHttpRequest(pinned, "/v1/setup-status"),
        )
        assertThat(inspector.endpoints).isEmpty()
    }

    @Test
    fun explicitHttpIsRejectedBeforeAnyNetworkRequest() = runTest {
        val transport = RecordingSetupHttpTransport()
        val probe = HttpSetupProbe(transport)

        val result = probe.probe("http://family.example.com")

        assertThat(result).isEqualTo(SetupProbeResult.Failed.InvalidAddress)
        assertThat(transport.requests).isEmpty()
    }

    @Test
    fun validMinimalSetupStatusRoutesConfiguredServerWithoutCredentialFields() = runTest {
        val transport = RecordingSetupHttpTransport(
            response = SetupHttpResponse(
                statusCode = 200,
                body = (
                    currentSetupStatus("configured")
                    ).toByteArray(),
            ),
        )
        val probe = HttpSetupProbe(transport)

        val result = probe.probe(" https://Family.Example.com:443/ ")

        assertThat(result).isEqualTo(
            SetupProbeResult.Ready(
                endpoint = TrustedEndpointProfile.systemPki("https://family.example.com"),
                familyState = SetupFamilyState.Configured,
            ),
        )
        assertThat(transport.requests).containsExactly(
            SetupHttpRequest(
                endpoint = TrustedEndpointProfile.systemPki("https://family.example.com"),
                path = "/v1/setup-status",
            ),
        )
    }

    @Test
    fun setupProbeSeparatesNonLeziIncompatibleAndMaintenanceResponses() = runTest {
        val transport = RecordingSetupHttpTransport()
        val probe = HttpSetupProbe(transport)

        val cases = listOf(
            SetupHttpResponse(302, byteArrayOf()) to SetupProbeResult.Failed.NotLezi,
            SetupHttpResponse(404, byteArrayOf()) to SetupProbeResult.Failed.NotLezi,
            SetupHttpResponse(503, byteArrayOf()) to SetupProbeResult.Failed.Maintenance,
            SetupHttpResponse(
                200,
                currentSetupStatus("empty", protocolVersion = 2)
                    .toByteArray(),
            ) to SetupProbeResult.Failed.Incompatible,
            SetupHttpResponse(
                200,
                """{"protocol_version":1,"capabilities":[],"family_state":"empty"}"""
                    .toByteArray(),
            ) to SetupProbeResult.Failed.Incompatible,
            SetupHttpResponse(
                200,
                (currentSetupStatus("empty").dropLast(1) + ",\"family_name\":\"secret\"}")
                    .toByteArray(),
            ) to SetupProbeResult.Failed.NotLezi,
            SetupHttpResponse(
                200,
                currentSetupStatus("empty").replace(
                    "\"record_membership_author\"",
                    "\"record_membership_author\",7",
                )
                    .toByteArray(),
            ) to SetupProbeResult.Failed.NotLezi,
        )

        cases.forEach { (response, expected) ->
            transport.response = response
            assertThat(probe.probe("https://family.example.com")).isEqualTo(expected)
        }

        assertThat(transport.requests).hasSize(cases.size)
    }
}

private fun currentSetupStatus(
    familyState: String,
    protocolVersion: Int = SETUP_PROTOCOL_VERSION,
): String =
    """{"protocol_version":$protocolVersion,"capabilities":["trusted_https_endpoint_v1","device_sessions_v1","membership_devices_v1","atomic_bundle","record_membership_author"],"family_state":"$familyState"}"""

private class RecordingSetupHttpTransport(
    var response: SetupHttpResponse = SetupHttpResponse(statusCode = 500, body = byteArrayOf()),
    var throwable: Exception? = null,
) : SetupHttpTransport {
    val requests = mutableListOf<SetupHttpRequest>()

    override suspend fun get(request: SetupHttpRequest): SetupHttpResponse {
        requests += request
        throwable?.let { throw it }
        return response
    }
}

private class RecordingTlsPeerInspector(
    private val candidate: CertificateTrustCandidate?,
) : TlsPeerInspector {
    val endpoints = mutableListOf<TrustedEndpointProfile>()

    override suspend fun inspect(endpoint: TrustedEndpointProfile): CertificateTrustCandidate? {
        endpoints += endpoint
        return candidate
    }
}
