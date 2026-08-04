package com.lezi.babylog.sync.session
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.inject.Inject
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

const val SETUP_PROTOCOL_VERSION = 1
const val CAPABILITY_TRUSTED_HTTPS_ENDPOINT = "trusted_https_endpoint_v1"
const val CAPABILITY_DEVICE_SESSIONS = "device_sessions_v1"
const val CAPABILITY_MEMBERSHIP_DEVICES = "membership_devices_v1"
const val CAPABILITY_ATOMIC_BUNDLE = "atomic_bundle"
const val CAPABILITY_AUTHORITATIVE_RECONCILE = "authoritative_reconcile_v1"
const val CAPABILITY_RECORD_MEMBERSHIP_AUTHOR = "record_membership_author"
const val CAPABILITY_DISASTER_RESTORE = "device_disaster_restore_v1"

enum class EndpointTrustMode {
    SystemPki,
    TofuSpki,
}

/** A normalized HTTPS origin whose transport identity was established independently of login. */
class TrustedEndpointProfile private constructor(
    val origin: String,
    val trustMode: EndpointTrustMode,
    val spkiSha256: String?,
) {
    /** Human-readable TOFU fingerprint. Null for platform-PKI endpoints. */
    val fingerprint: String?
        get() = spkiSha256?.let { encoded ->
            runCatching { Base64.getDecoder().decode(encoded) }
                .getOrNull()
                ?.joinToString(":") { byte -> "%02X".format(byte.toInt() and 0xff) }
        }
    internal val host: String
        get() = URI(origin).host
    internal val port: Int
        get() = URI(origin).port.takeUnless { it == -1 } ?: 443

    override fun equals(other: Any?): Boolean =
        other is TrustedEndpointProfile &&
            origin == other.origin &&
            trustMode == other.trustMode &&
            spkiSha256 == other.spkiSha256

    override fun hashCode(): Int =
        31 * (31 * origin.hashCode() + trustMode.hashCode()) + spkiSha256.hashCode()

    override fun toString(): String =
        "TrustedEndpointProfile(origin=$origin, trustMode=$trustMode)"

    companion object {
        fun systemPki(rawOrigin: String): TrustedEndpointProfile = TrustedEndpointProfile(
            origin = normalizeHttpsOrigin(rawOrigin),
            trustMode = EndpointTrustMode.SystemPki,
            spkiSha256 = null,
        )

        fun tofuSpki(rawOrigin: String, spkiSha256: String): TrustedEndpointProfile {
            val pin = spkiSha256.trim()
            val decoded = runCatching { Base64.getDecoder().decode(pin) }.getOrNull()
            require(decoded?.size == SHA_256_BYTES) { "SPKI pin must be a SHA-256 value" }
            return TrustedEndpointProfile(
                origin = normalizeHttpsOrigin(rawOrigin),
                trustMode = EndpointTrustMode.TofuSpki,
                spkiSha256 = pin,
            )
        }
    }
}

internal fun TrustedEndpointProfile.matchesOrigin(rawOrigin: String): Boolean =
    runCatching { TrustedEndpointProfile.systemPki(rawOrigin).origin == origin }.getOrDefault(false)

private fun normalizeHttpsOrigin(rawOrigin: String): String {
    val raw = rawOrigin.trim()
    val uri = try {
        URI(raw)
    } catch (error: Exception) {
        throw IllegalArgumentException("请输入完整的 HTTPS 地址", error)
    }
    require(uri.scheme.equals("https", ignoreCase = true)) {
        "家庭服务器仅支持 HTTPS 地址"
    }
    require(uri.rawUserInfo == null) { "服务器地址不能包含用户名或密码" }
    val host = uri.host?.trim()?.takeIf(String::isNotEmpty)
        ?: throw IllegalArgumentException("请输入完整的 HTTPS 地址")
    require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") {
        "服务器地址不能包含路径"
    }
    require(uri.rawQuery == null && uri.rawFragment == null) {
        "服务器地址不能包含查询参数或片段"
    }
    require(uri.port == -1 || uri.port in 1..65535) {
        "服务器端口需为 1–65535"
    }
    val renderedHost = if (host.contains(':')) "[$host]" else host.lowercase()
    val port = uri.port.takeUnless { it == -1 || it == 443 }?.let { ":$it" }.orEmpty()
    return "https://$renderedHost$port"
}

class CertificateTrustCandidate private constructor(
    val endpointOrigin: String,
    val spkiSha256: String,
    val fingerprint: String,
) {
    override fun equals(other: Any?): Boolean =
        other is CertificateTrustCandidate &&
            endpointOrigin == other.endpointOrigin &&
            spkiSha256 == other.spkiSha256 &&
            fingerprint == other.fingerprint

    override fun hashCode(): Int =
        31 * (31 * endpointOrigin.hashCode() + spkiSha256.hashCode()) + fingerprint.hashCode()

    override fun toString(): String =
        "CertificateTrustCandidate(endpointOrigin=$endpointOrigin, fingerprint=$fingerprint)"

    fun trustedEndpoint(): TrustedEndpointProfile =
        TrustedEndpointProfile.tofuSpki(endpointOrigin, spkiSha256)

    companion object {
        fun fromSpki(
            endpoint: TrustedEndpointProfile,
            subjectPublicKeyInfo: ByteArray,
        ): CertificateTrustCandidate {
            require(endpoint.trustMode == EndpointTrustMode.SystemPki)
            require(subjectPublicKeyInfo.isNotEmpty())
            val digest = MessageDigest.getInstance("SHA-256").digest(subjectPublicKeyInfo)
            return CertificateTrustCandidate(
                endpointOrigin = endpoint.origin,
                spkiSha256 = Base64.getEncoder().encodeToString(digest),
                fingerprint = digest.joinToString(":") { byte -> "%02X".format(byte.toInt() and 0xff) },
            )
        }
    }
}

enum class SetupFamilyState {
    Empty,
    Configured,
}

sealed interface SetupProbeResult {
    data class CertificateApprovalRequired(
        val candidate: CertificateTrustCandidate,
    ) : SetupProbeResult

    data class Ready(
        val endpoint: TrustedEndpointProfile,
        val familyState: SetupFamilyState,
    ) : SetupProbeResult

    sealed interface Failed : SetupProbeResult {
        data object InvalidAddress : Failed
        data object Unreachable : Failed
        data object NotLezi : Failed
        data object Incompatible : Failed
        data object Maintenance : Failed
        data object CertificateChanged : Failed
    }
}

fun interface SetupProbe {
    suspend fun probe(
        endpointDraft: String,
        trustedEndpoint: TrustedEndpointProfile?,
    ): SetupProbeResult
}

suspend fun SetupProbe.probe(endpointDraft: String): SetupProbeResult = probe(endpointDraft, null)

internal data class SetupHttpRequest(
    val endpoint: TrustedEndpointProfile,
    val path: String,
)

internal data class SetupHttpResponse(
    val statusCode: Int,
    val body: ByteArray,
)

internal fun interface SetupHttpTransport {
    suspend fun get(request: SetupHttpRequest): SetupHttpResponse
}

internal class UntrustedServerCertificateException(
    cause: Throwable? = null,
) : Exception(cause)

internal class SpkiPinMismatchException : CertificateException("Server SPKI changed")

internal class DefaultSetupHttpTransport @Inject constructor() : SetupHttpTransport {
    override suspend fun get(request: SetupHttpRequest): SetupHttpResponse =
        withContext(Dispatchers.IO) {
            val connection = URL(request.endpoint.origin + request.path)
                .openConnection() as HttpsURLConnection
            request.endpoint.spkiSha256?.let { pin ->
                connection.sslSocketFactory = pinnedSslContext(pin).socketFactory
            }
            connection.requestMethod = "GET"
            connection.connectTimeout = TLS_TIMEOUT_MILLIS
            connection.readTimeout = TLS_TIMEOUT_MILLIS
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            try {
                val statusCode = connection.responseCode
                val stream = if (statusCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
                SetupHttpResponse(
                    statusCode = statusCode,
                    body = stream?.use { it.readAtMost(MAX_SETUP_RESPONSE_BYTES + 1) }
                        ?: byteArrayOf(),
                )
            } finally {
                connection.disconnect()
            }
        }
}

internal fun InputStream.readAtMost(limit: Int): ByteArray {
    require(limit >= 0)
    val result = ByteArray(limit)
    var offset = 0
    while (offset < limit) {
        val count = read(result, offset, limit - offset)
        if (count < 0) break
        if (count == 0) {
            val next = read()
            if (next < 0) break
            result[offset++] = next.toByte()
        } else {
            offset += count
        }
    }
    return result.copyOf(offset)
}

internal fun interface TlsPeerInspector {
    /** Performs a TLS handshake only. It never writes HTTP or application data. */
    suspend fun inspect(endpoint: TrustedEndpointProfile): CertificateTrustCandidate?
}

internal class DefaultTlsPeerInspector @Inject constructor() : TlsPeerInspector {
    override suspend fun inspect(endpoint: TrustedEndpointProfile): CertificateTrustCandidate? =
        withContext(Dispatchers.IO) {
            require(endpoint.trustMode == EndpointTrustMode.SystemPki)
            val context = SSLContext.getInstance("TLS")
            context.init(null, arrayOf(InspectionTrustManager), SecureRandom())
            Socket().use { rawSocket ->
                rawSocket.connect(
                    InetSocketAddress(endpoint.host, endpoint.port),
                    TLS_TIMEOUT_MILLIS,
                )
                rawSocket.soTimeout = TLS_TIMEOUT_MILLIS
                val socket = context.socketFactory.createSocket(
                    rawSocket,
                    endpoint.host,
                    endpoint.port,
                    true,
                ) as SSLSocket
                socket.use {
                    val parameters = socket.sslParameters
                    parameters.endpointIdentificationAlgorithm = "HTTPS"
                    socket.sslParameters = parameters
                    socket.startHandshake()
                    val certificate = socket.session.peerCertificates.firstOrNull() as? X509Certificate
                        ?: return@withContext null
                    certificate.checkValidity()
                    if (certificate.subjectX500Principal != certificate.issuerX500Principal) {
                        return@withContext null
                    }
                    runCatching { certificate.verify(certificate.publicKey) }
                        .getOrElse { return@withContext null }
                    CertificateTrustCandidate.fromSpki(endpoint, certificate.publicKey.encoded)
                }
            }
        }
}

class HttpSetupProbe internal constructor(
    private val transport: SetupHttpTransport,
    private val peerInspector: TlsPeerInspector,
) : SetupProbe {
    internal constructor(transport: SetupHttpTransport) : this(
        transport = transport,
        peerInspector = TlsPeerInspector { null },
    )

    @Inject
    internal constructor(
        transport: DefaultSetupHttpTransport,
        peerInspector: DefaultTlsPeerInspector,
    ) : this(transport as SetupHttpTransport, peerInspector as TlsPeerInspector)

    override suspend fun probe(
        endpointDraft: String,
        trustedEndpoint: TrustedEndpointProfile?,
    ): SetupProbeResult {
        val systemEndpoint = try {
            TrustedEndpointProfile.systemPki(endpointDraft)
        } catch (_: IllegalArgumentException) {
            return SetupProbeResult.Failed.InvalidAddress
        }
        val endpoint = trustedEndpoint
            ?.takeIf { it.origin == systemEndpoint.origin }
            ?: systemEndpoint
        return try {
            val response = transport.get(SetupHttpRequest(endpoint, "/v1/setup-status"))
            when {
                response.statusCode in 500..599 -> SetupProbeResult.Failed.Maintenance
                response.statusCode != 200 -> SetupProbeResult.Failed.NotLezi
                response.body.size > MAX_SETUP_RESPONSE_BYTES -> SetupProbeResult.Failed.NotLezi
                else -> parseSetupStatus(endpoint, response.body)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            when {
                error.hasCause<SpkiPinMismatchException>() ->
                    SetupProbeResult.Failed.CertificateChanged
                endpoint.trustMode == EndpointTrustMode.TofuSpki ->
                    SetupProbeResult.Failed.Unreachable
                error.isUntrustedCertificateFailure() -> inspectUntrusted(systemEndpoint)
                else -> SetupProbeResult.Failed.Unreachable
            }
        }
    }

    private suspend fun inspectUntrusted(
        endpoint: TrustedEndpointProfile,
    ): SetupProbeResult = try {
        peerInspector.inspect(endpoint)
            ?.let(SetupProbeResult::CertificateApprovalRequired)
            ?: SetupProbeResult.Failed.Unreachable
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        SetupProbeResult.Failed.Unreachable
    }
}

private fun Exception.isUntrustedCertificateFailure(): Boolean =
    this is UntrustedServerCertificateException || hasCause<SSLHandshakeException>() ||
        causes().any { it is CertificateException }

private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean =
    causes().any { it is T }

private fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause }

internal fun pinnedSslContext(pin: String): SSLContext = SSLContext.getInstance("TLS").apply {
    init(null, arrayOf(PinnedSpkiTrustManager(pin)), SecureRandom())
}

private class PinnedSpkiTrustManager(pin: String) : X509TrustManager {
    private val expectedPin = Base64.getDecoder().decode(pin)

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Client certificates are not accepted")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val certificate = chain?.firstOrNull()
            ?: throw CertificateException("Server certificate missing")
        certificate.checkValidity()
        val actual = MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded)
        if (!MessageDigest.isEqual(expectedPin, actual)) throw SpkiPinMismatchException()
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

private object InspectionTrustManager : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Client certificates are not accepted")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        chain?.firstOrNull()?.checkValidity()
            ?: throw CertificateException("Server certificate missing")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

private fun parseSetupStatus(
    endpoint: TrustedEndpointProfile,
    body: ByteArray,
): SetupProbeResult {
    val json = runCatching {
        Json.parseToJsonElement(body.toString(Charsets.UTF_8)) as? JsonObject
    }.getOrNull() ?: return SetupProbeResult.Failed.NotLezi
    // Required known fields must be present; extra unknown keys are ignored so the
    // server may grow setup-status without bricking existing clients as NotLezi.
    if (!json.keys.containsAll(SETUP_STATUS_FIELDS)) return SetupProbeResult.Failed.NotLezi
    val protocolVersion = (json["protocol_version"] as? JsonPrimitive)?.intOrNull
        ?: return SetupProbeResult.Failed.NotLezi
    val capabilityValues = json["capabilities"] as? JsonArray
        ?: return SetupProbeResult.Failed.NotLezi
    if (capabilityValues.any { it !is JsonPrimitive || !it.isString || it.content.isBlank() }) {
        return SetupProbeResult.Failed.NotLezi
    }
    val capabilities = capabilityValues.map { (it as JsonPrimitive).content }.toSet()
    if (protocolVersion != SETUP_PROTOCOL_VERSION || !capabilities.containsAll(
            REQUIRED_SETUP_CAPABILITIES,
        )
    ) {
        return SetupProbeResult.Failed.Incompatible
    }
    val familyState = when ((json["family_state"] as? JsonPrimitive)?.contentOrNull) {
        "empty" -> SetupFamilyState.Empty
        "configured" -> SetupFamilyState.Configured
        else -> return SetupProbeResult.Failed.NotLezi
    }
    return SetupProbeResult.Ready(endpoint, familyState)
}

private const val SHA_256_BYTES = 32
private const val TLS_TIMEOUT_MILLIS = 8_000
private const val MAX_SETUP_RESPONSE_BYTES = 64 * 1024
private val SETUP_STATUS_FIELDS = setOf("protocol_version", "capabilities", "family_state")
private val REQUIRED_SETUP_CAPABILITIES = setOf(
    CAPABILITY_TRUSTED_HTTPS_ENDPOINT,
    CAPABILITY_DEVICE_SESSIONS,
    CAPABILITY_MEMBERSHIP_DEVICES,
    CAPABILITY_ATOMIC_BUNDLE,
    CAPABILITY_RECORD_MEMBERSHIP_AUTHOR,
    CAPABILITY_DISASTER_RESTORE,
    CAPABILITY_AUTHORITATIVE_RECONCILE,
)
