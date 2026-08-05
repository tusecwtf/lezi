package com.lezi.gf.syncsession

import com.lezi.gf.kernel.GfError
import com.lezi.gf.kernel.GfResult
import com.lezi.gf.kernel.ProductVersion
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Sync session vertical — sole owner of wire HTTP client, TOFU/SPKI, reconcile, updates.
 */
class SyncSessionService(
    private val http: WireTransport = OkHttpWireTransport(),
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    @Volatile
    var endpoint: String = ProductVersion.DEFAULT_ENDPOINT
        private set

    @Volatile
    var trustedSpkiSha256: String? = null
        private set

    @Volatile
    private var token: String? = null

    val accessToken: String? get() = token

    @Volatile
    var revision: Long = 0
        private set

    @Volatile
    private var pendingAtomicUnits: Int = 0

    @Volatile
    private var lastStatus: SyncShallowStatus = SyncShallowStatus.NOT_JOINED

    @Volatile
    private var forcedUpdate: WireAppUpdate? = null

    @Volatile
    private var optionalUpdate: WireAppUpdate? = null

    fun configureEndpoint(url: String) {
        require(!url.contains("192.168.50.4")) {
            "greenfield must not default/hardcode family NAS"
        }
        endpoint = url.trimEnd('/')
    }

    fun setTrustedSpki(spki: String?) {
        trustedSpkiSha256 = spki
    }

    fun setAccessToken(newToken: String?) {
        token = newToken
        if (newToken == null) {
            lastStatus = SyncShallowStatus.NOT_JOINED
        }
    }

    fun markLocalPending(delta: Int) {
        pendingAtomicUnits = (pendingAtomicUnits + delta).coerceAtLeast(0)
        if (accessToken != null && pendingAtomicUnits > 0) {
            lastStatus = SyncShallowStatus.PENDING
        }
    }

    fun shallowStatus(): Pair<SyncShallowStatus, Int> = lastStatus to pendingAtomicUnits

    fun forcedUpdateShell(): WireAppUpdate? = forcedUpdate
    fun optionalUpdateShell(): WireAppUpdate? = optionalUpdate

    fun dismissOptionalUpdate() {
        optionalUpdate = null
    }

    fun injectMockUpdate(update: WireAppUpdate) {
        if (update.force || update.min_supported_version_code > ProductVersion.CODE) {
            forcedUpdate = update
        } else {
            optionalUpdate = update
        }
    }

    fun health(): GfResult<WireHealth> = get("/health")

    fun ready(): GfResult<WireReady> = get("/ready")

    fun capability(): GfResult<WireCapability> {
        val cap = get<WireCapability>("/v1/capability")
        if (cap is GfResult.Ok) {
            val missing = WireCaps.REQUIRED - cap.value.capabilities.toSet()
            if (cap.value.wire_current != WireCaps.CURRENT) {
                return GfResult.Err(GfError.CapabilityMissing("wire_current=${cap.value.wire_current}"))
            }
            if (missing.isNotEmpty()) {
                return GfResult.Err(GfError.CapabilityMissing(missing.joinToString(",")))
            }
        }
        return cap
    }

    fun setupStatus(): GfResult<WireSetupStatus> = get("/v1/setup-status")

    fun trustAndProbe(spkiFromServer: String?): GfResult<WireSetupStatus> {
        if (trustedSpkiSha256 != null && spkiFromServer != null &&
            !trustedSpkiSha256.equals(spkiFromServer, ignoreCase = true)
        ) {
            return GfResult.Err(GfError.TrustBlocked("服务器证书已变更，请确认后「忘记并重新连接」"))
        }
        if (trustedSpkiSha256 == null && spkiFromServer != null) {
            trustedSpkiSha256 = spkiFromServer
        }
        return setupStatus()
    }

    fun createFamily(
        bootstrapSecret: String,
        familyName: String,
        displayName: String,
        deviceName: String,
    ): GfResult<WireSessionResponse> {
        val body = json.encodeToString(
            WireCreateFamilyRequest(
                bootstrap_secret = bootstrapSecret,
                family_name = familyName,
                display_name = displayName,
                device_name = deviceName,
            ),
        )
        val res = postRaw<WireSessionResponse>("/v1/family/create", body, auth = false)
        if (res is GfResult.Ok) {
            setAccessToken(res.value.access_token)
            lastStatus = SyncShallowStatus.SYNCED
            pendingAtomicUnits = 0
        }
        return res
    }

    fun ownerLogin(
        bootstrapSecret: String,
        deviceName: String,
        takeover: Boolean = false,
    ): GfResult<WireSessionResponse> {
        val path = if (takeover) "/v1/family/owner-takeover" else "/v1/family/owner-login"
        val body = """{"bootstrap_secret":${jsonStr(bootstrapSecret)},"device_name":${jsonStr(deviceName)}}"""
        val res = postRaw<WireSessionResponse>(path, body, auth = false)
        if (res is GfResult.Ok) {
            setAccessToken(res.value.access_token)
            lastStatus = SyncShallowStatus.SYNCED
        }
        return res
    }

    fun applyJoin(displayName: String, deviceName: String): GfResult<WireJoinPending> {
        val body = json.encodeToString(WireJoinRequest(displayName, deviceName))
        return postRaw("/v1/family/join-request", body, auth = false)
    }

    fun approveJoin(requestId: String, approve: Boolean = true): GfResult<WireSessionResponse> {
        val body = json.encodeToString(WireApproveRequest(requestId, approve))
        return postRaw("/v1/family/approve-join", body, auth = true)
    }

    fun claimJoin(requestId: String): GfResult<WireSessionResponse> {
        val body = """{"request_id":${jsonStr(requestId)}}"""
        val res = postRaw<WireSessionResponse>("/v1/family/claim-join", body, auth = false)
        if (res is GfResult.Ok) {
            setAccessToken(res.value.access_token)
            lastStatus = SyncShallowStatus.SYNCED
        }
        return res
    }

    fun reconcile(request: WireReconcileRequest): GfResult<WireReconcileResponse> {
        val body = json.encodeToString(request.copy(since_revision = revision))
        val res = postRaw<WireReconcileResponse>("/v1/sync/reconcile", body, auth = true)
        when (res) {
            is GfResult.Ok -> {
                revision = res.value.revision
                pendingAtomicUnits = 0
                lastStatus = SyncShallowStatus.SYNCED
            }
            is GfResult.Err -> {
                if (res.error is GfError.Network || res.error is GfError.TrustBlocked) {
                    lastStatus = SyncShallowStatus.TEMPORARILY_UNAVAILABLE
                }
            }
        }
        return res
    }

    fun checkAppUpdate(joined: Boolean): GfResult<WireAppUpdate?> {
        if (!joined) {
            return GfResult.Err(GfError.Forbidden("未加入家庭时不检查更新"))
        }
        val res = getAuth<WireAppUpdate>("/v1/app-update")
        if (res is GfResult.Ok) {
            val u = res.value
            if (u.min_supported_version_code > ProductVersion.CODE || u.force) {
                forcedUpdate = u
            } else if (u.version_code > ProductVersion.CODE) {
                optionalUpdate = u
            }
            return GfResult.Ok(u)
        }
        if (res is GfResult.Err) {
            val err = res.error
            if (err is GfError.Protocol && err.message.contains("client_update_required")) {
                val force = WireAppUpdate(
                    version_code = ProductVersion.CODE + 1,
                    version_name = "required",
                    force = true,
                    min_supported_version_code = ProductVersion.CODE + 1,
                )
                forcedUpdate = force
                return GfResult.Ok(force)
            }
            return res
        }
        return res
    }

    fun exitDevice(): GfResult<Unit> {
        val res = postRaw<Unit>("/v1/family/exit-device", "{}", auth = true)
        setAccessToken(null)
        revision = 0
        lastStatus = SyncShallowStatus.NOT_JOINED
        return res
    }

    fun leaveFamily(): GfResult<Unit> {
        val res = postRaw<Unit>("/v1/family/leave", "{}", auth = true)
        setAccessToken(null)
        revision = 0
        lastStatus = SyncShallowStatus.NOT_JOINED
        return res
    }

    fun deleteFamily(bootstrapSecret: String): GfResult<Unit> {
        val body = """{"bootstrap_secret":${jsonStr(bootstrapSecret)}}"""
        val res = postRaw<Unit>("/v1/family/delete", body, auth = true)
        setAccessToken(null)
        revision = 0
        lastStatus = SyncShallowStatus.NOT_JOINED
        return res
    }

    fun createMemberQr(): GfResult<QrCodeResponse> =
        postRaw("/v1/family/member-qr", "{}", auth = true)

    fun claimMemberQr(code: String): GfResult<WireSessionResponse> {
        val body = """{"code":${jsonStr(code)}}"""
        val res = postRaw<WireSessionResponse>("/v1/family/claim-qr", body, auth = false)
        if (res is GfResult.Ok) setAccessToken(res.value.access_token)
        return res
    }

    fun disasterRestore(bootstrapSecret: String): GfResult<WireSessionResponse> {
        val body = """{"bootstrap_secret":${jsonStr(bootstrapSecret)}}"""
        return postRaw("/v1/family/disaster-restore", body, auth = false)
    }

    fun evaluateSpkiChange(newSpki: String): GfResult<Unit> {
        val old = trustedSpkiSha256
        if (old != null && !old.equals(newSpki, ignoreCase = true)) {
            return GfResult.Err(GfError.TrustBlocked("SPKI 变更：硬阻断，请忘记并重新连接"))
        }
        return GfResult.Ok(Unit)
    }

    fun evaluateFamilyMismatch(expectedFamilyId: String?, remoteFamilyId: String?): GfResult<Unit> {
        if (expectedFamilyId != null && remoteFamilyId != null && expectedFamilyId != remoteFamilyId) {
            return GfResult.Err(GfError.TrustBlocked("不同家庭：硬阻断，禁止合并"))
        }
        return GfResult.Ok(Unit)
    }

    fun shouldClearLocalOnAuthError(code: String?): Boolean {
        return code in setOf("device_removed", "membership_deleted", "family_deleted")
    }

    private fun jsonStr(s: String): String = json.encodeToString(s)

    private inline fun <reified T> get(path: String): GfResult<T> =
        parse(http.request(endpoint, path, "GET", null, null))

    private inline fun <reified T> getAuth(path: String): GfResult<T> =
        parse(http.request(endpoint, path, "GET", null, token))

    private inline fun <reified T> postRaw(path: String, body: String, auth: Boolean): GfResult<T> =
        parse(http.request(endpoint, path, "POST", body, if (auth) token else null))

    private inline fun <reified T> parse(result: WireHttpResult): GfResult<T> {
        return when (result) {
            is WireHttpResult.Ok -> {
                try {
                    if (T::class == Unit::class) {
                        @Suppress("UNCHECKED_CAST")
                        return GfResult.Ok(Unit as T)
                    }
                    if (result.body.isBlank() || result.body == "{}") {
                        if (T::class == Unit::class) {
                            @Suppress("UNCHECKED_CAST")
                            return GfResult.Ok(Unit as T)
                        }
                    }
                    GfResult.Ok(json.decodeFromString(result.body))
                } catch (e: Exception) {
                    GfResult.Err(GfError.Protocol(e.message ?: "decode"))
                }
            }
            is WireHttpResult.Err -> {
                when {
                    result.code == "trust_blocked" -> GfResult.Err(GfError.TrustBlocked(result.message))
                    result.code == "client_update_required" -> GfResult.Err(GfError.Protocol("client_update_required"))
                    result.httpStatus in 500..599 -> GfResult.Err(GfError.Network(result.message))
                    result.httpStatus == 403 -> GfResult.Err(GfError.Forbidden(result.message))
                    result.httpStatus == 401 -> GfResult.Err(GfError.Network("unauthorized"))
                    else -> GfResult.Err(GfError.Protocol("${result.code}: ${result.message}"))
                }
            }
            is WireHttpResult.NetworkFail -> GfResult.Err(GfError.Network(result.message))
        }
    }
}

@kotlinx.serialization.Serializable
data class QrCodeResponse(
    val code: String,
    val expires_in: String = "300",
    /** Optional non-sensitive LAN invite install base (ADR-0015); App builds full #v1. envelope. */
    val landing_url: String? = null,
)

sealed class WireHttpResult {
    data class Ok(val body: String, val spkiSha256: String? = null) : WireHttpResult()
    data class Err(val httpStatus: Int, val code: String, val message: String) : WireHttpResult()
    data class NetworkFail(val message: String) : WireHttpResult()
}

interface WireTransport {
    fun request(
        endpoint: String,
        path: String,
        method: String,
        jsonBody: String?,
        bearer: String?,
    ): WireHttpResult
}

/** Fake transport for L1/L2 tests — drives real SyncSessionService paths. */
class FakeWireTransport : WireTransport {
    var health = WireHealth(status = "ok", version = ProductVersion.NAME)
    var ready = WireReady(ready = true, version = ProductVersion.NAME)
    var capability = WireCapability(
        wire_current = WireCaps.CURRENT,
        capabilities = WireCaps.REQUIRED.toList(),
    )
    var setup = WireSetupStatus(state = "empty")
    var pendingRequests = mutableMapOf<String, WireJoinRequest>()
    var revision = 0L
    var records = mutableListOf<WireRecordBundle>()
    var plans = mutableListOf<WirePlanBundle>()
    var babies = mutableListOf<WireBaby>()
    var customDefs = mutableListOf<WireCustomDef>()
    var membershipNames = mutableMapOf<String, String>()
    var spki = "test-spki-aaa"
    var appUpdate: WireAppUpdate? = null
    var failNext: String? = null
    var bootstrapSecret = "test-bootstrap-secret"
    var familyConfigured = false
    var approvedSessions = mutableMapOf<String, WireSessionResponse>()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun request(
        endpoint: String,
        path: String,
        method: String,
        jsonBody: String?,
        bearer: String?,
    ): WireHttpResult {
        failNext?.let {
            val f = it
            failNext = null
            return WireHttpResult.NetworkFail(f)
        }
        return try {
            when {
                path == "/health" -> ok(health)
                path == "/ready" -> ok(ready)
                path == "/v1/capability" -> ok(capability)
                path == "/v1/setup-status" -> ok(setup)
                path == "/v1/family/create" && method == "POST" -> {
                    val req = json.decodeFromString<WireCreateFamilyRequest>(jsonBody!!)
                    if (req.bootstrap_secret != bootstrapSecret) {
                        return WireHttpResult.Err(403, "bad_secret", "根密码错误")
                    }
                    if (familyConfigured) {
                        return WireHttpResult.Err(409, "already_configured", "家庭已配置")
                    }
                    familyConfigured = true
                    setup = WireSetupStatus(state = "configured", family_id = "fam-1", family_name = req.family_name)
                    val s = WireSessionResponse(
                        family_id = "fam-1",
                        family_name = req.family_name,
                        membership_id = "mem-owner",
                        role = "owner",
                        display_name = req.display_name,
                        device_id = "dev-owner-1",
                        access_token = "tok-owner",
                        refresh_token = "ref-owner",
                    )
                    membershipNames["mem-owner"] = req.display_name
                    ok(s)
                }
                path == "/v1/family/join-request" && method == "POST" -> {
                    val req = json.decodeFromString<WireJoinRequest>(jsonBody!!)
                    val id = "req-${pendingRequests.size + 1}"
                    pendingRequests[id] = req
                    ok(WireJoinPending(request_id = id))
                }
                path == "/v1/family/approve-join" && method == "POST" -> {
                    if (bearer == null) return WireHttpResult.Err(401, "unauthorized", "no token")
                    val req = json.decodeFromString<WireApproveRequest>(jsonBody!!)
                    val join = pendingRequests[req.request_id]
                        ?: return WireHttpResult.Err(404, "not_found", "申请不存在")
                    if (!req.approve) {
                        pendingRequests.remove(req.request_id)
                        return WireHttpResult.Ok("""{"status":"rejected"}""")
                    }
                    val s = WireSessionResponse(
                        family_id = "fam-1",
                        family_name = setup.family_name ?: "家庭",
                        membership_id = "mem-member",
                        role = "member",
                        display_name = join.display_name,
                        device_id = "dev-member-1",
                        access_token = "tok-member",
                        refresh_token = "ref-member",
                    )
                    approvedSessions[req.request_id] = s
                    membershipNames["mem-member"] = join.display_name
                    ok(s)
                }
                path == "/v1/family/claim-join" && method == "POST" -> {
                    val id = Regex("\"request_id\"\\s*:\\s*\"([^\"]+)\"").find(jsonBody!!)?.groupValues?.get(1)
                    val s = id?.let { approvedSessions[it] } ?: WireSessionResponse(
                        family_id = "fam-1",
                        family_name = setup.family_name ?: "家庭",
                        membership_id = "mem-member",
                        role = "member",
                        display_name = "成员",
                        device_id = "dev-member-1",
                        access_token = "tok-member",
                        refresh_token = "ref-member",
                    )
                    pendingRequests.remove(id)
                    ok(s)
                }
                path == "/v1/sync/reconcile" && method == "POST" -> {
                    if (bearer == null) return WireHttpResult.Err(401, "unauthorized", "no token")
                    val req = json.decodeFromString<WireReconcileRequest>(jsonBody!!)
                    for (r in req.push_records) {
                        if (r.photos.any { it.content_base64 == null && it.byte_size > 0 }) {
                            return WireHttpResult.Err(400, "atomic_incomplete", "记录照片未齐")
                        }
                        // Closed-set type keys (mirrors server + PayloadValidation)
                        val known = setOf(
                            "nursing", "formula", "pumped_feed", "pump_express", "pee", "poop",
                            "both_diaper", "sleep", "temperature", "diary", "bath", "walk", "cough",
                            "rash", "vomit", "injury", "medicine", "hospital", "height", "weight",
                            "baby_food", "snack", "drink", "head_size", "chest_size", "foot_size",
                            "vaccine", "custom",
                        )
                        if (r.type_key !in known) {
                            return WireHttpResult.Err(422, "unknown_type", "未知记录类型")
                        }
                        if (r.type_key == "pee") {
                            val amt = Regex("\"pee_amount\"\\s*:\\s*(-?\\d+)")
                                .find(r.payload_json)?.groupValues?.get(1)?.toIntOrNull()
                            if (amt != null && amt !in 1..3) {
                                return WireHttpResult.Err(422, "invalid_payload", "尿量档位须为 1–3")
                            }
                        }
                        if (r.type_key == "temperature") {
                            val c = Regex("\"celsius\"\\s*:\\s*(-?[0-9.]+)")
                                .find(r.payload_json)?.groupValues?.get(1)?.toDoubleOrNull()
                            if (c != null && (c < 30 || c > 45)) {
                                return WireHttpResult.Err(422, "invalid_payload", "体温超出合理范围")
                            }
                        }
                        // LWW by updated_at_ms
                        val idx = records.indexOfFirst { it.client_uuid == r.client_uuid }
                        if (idx >= 0) {
                            if (r.updated_at_ms >= records[idx].updated_at_ms) {
                                records[idx] = r
                            }
                        } else {
                            records.add(r)
                        }
                    }
                    for (p in req.push_plans) {
                        val idx = plans.indexOfFirst { it.client_uuid == p.client_uuid }
                        if (idx >= 0) {
                            if (p.updated_at_ms >= plans[idx].updated_at_ms) plans[idx] = p
                        } else {
                            plans.add(p)
                        }
                    }
                    for (b in req.push_babies) {
                        val idx = babies.indexOfFirst { it.client_uuid == b.client_uuid }
                        if (idx >= 0) {
                            if (b.updated_at_ms >= babies[idx].updated_at_ms) babies[idx] = b
                        } else {
                            babies.add(b)
                        }
                    }
                    for (c in req.push_custom_defs) {
                        val idx = customDefs.indexOfFirst { it.client_uuid == c.client_uuid }
                        if (idx >= 0) {
                            if (c.updated_at_ms >= customDefs[idx].updated_at_ms) {
                                customDefs[idx] = c
                            }
                        } else {
                            customDefs.add(c)
                        }
                    }
                    revision += 1
                    ok(
                        WireReconcileResponse(
                            revision = revision,
                            records = records.toList(),
                            plans = plans.toList(),
                            babies = babies.toList(),
                            custom_defs = customDefs.toList(),
                            membership_names = membershipNames.toMap(),
                        ),
                    )
                }
                path == "/v1/app-update" -> {
                    val u = appUpdate ?: WireAppUpdate(
                        version_code = ProductVersion.CODE,
                        version_name = ProductVersion.NAME,
                    )
                    ok(u)
                }
                path == "/v1/family/exit-device" -> WireHttpResult.Ok("""{"ok":"true"}""")
                path == "/v1/family/leave" -> WireHttpResult.Ok("""{"ok":"true"}""")
                path == "/v1/family/delete" -> {
                    familyConfigured = false
                    setup = WireSetupStatus(state = "empty")
                    records.clear()
                    plans.clear()
                    WireHttpResult.Ok("""{"ok":"true"}""")
                }
                path == "/v1/family/owner-login" || path == "/v1/family/owner-takeover" -> {
                    ok(
                        WireSessionResponse(
                            family_id = "fam-1",
                            family_name = setup.family_name ?: "家庭",
                            membership_id = "mem-owner",
                            role = "owner",
                            display_name = "管理员",
                            device_id = "dev-owner-2",
                            access_token = "tok-owner-2",
                            refresh_token = "ref-owner-2",
                        ),
                    )
                }
                path == "/v1/family/member-qr" -> ok(
                    QrCodeResponse(
                        code = "qr-1",
                        landing_url = endpoint.trimEnd('/') + "/join",
                    ),
                )
                path == "/v1/family/claim-qr" -> {
                    ok(
                        WireSessionResponse(
                            family_id = "fam-1",
                            family_name = setup.family_name ?: "家庭",
                            membership_id = "mem-qr",
                            role = "member",
                            display_name = "QR成员",
                            device_id = "dev-qr",
                            access_token = "tok-qr",
                            refresh_token = "ref-qr",
                        ),
                    )
                }
                path == "/v1/family/disaster-restore" -> {
                    if (familyConfigured && (records.isNotEmpty() || babies.isNotEmpty())) {
                        WireHttpResult.Err(409, "not_empty", "非空服不可走灾难恢复")
                    } else {
                        familyConfigured = true
                        val s = WireSessionResponse(
                            family_id = "fam-restored",
                            family_name = "恢复家庭",
                            membership_id = "mem-owner-new",
                            role = "owner",
                            display_name = "管理员",
                            device_id = "dev-restore",
                            access_token = "tok-restore",
                            refresh_token = "ref-restore",
                        )
                        setup = WireSetupStatus(state = "configured", family_id = s.family_id, family_name = s.family_name)
                        ok(s)
                    }
                }
                else -> WireHttpResult.Err(404, "not_found", path)
            }
        } catch (e: Exception) {
            WireHttpResult.NetworkFail(e.message ?: "error")
        }
    }

    private inline fun <reified T> ok(value: T): WireHttpResult.Ok {
        return WireHttpResult.Ok(json.encodeToString(value), spki)
    }
}

class OkHttpWireTransport(
    private val trustAllForLocalDev: Boolean = true,
) : WireTransport {
    private val client: OkHttpClient = buildClient()
    private val media = "application/json; charset=utf-8".toMediaType()

    private fun buildClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
        if (trustAllForLocalDev) {
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            val ssl = SSLContext.getInstance("TLS")
            ssl.init(null, arrayOf<TrustManager>(trustAll), java.security.SecureRandom())
            builder.sslSocketFactory(ssl.socketFactory, trustAll)
            builder.hostnameVerifier { _, _ -> true }
        }
        return builder.build()
    }

    override fun request(
        endpoint: String,
        path: String,
        method: String,
        jsonBody: String?,
        bearer: String?,
    ): WireHttpResult {
        return try {
            val url = endpoint.trimEnd('/') + path
            val rb = Request.Builder().url(url)
            if (bearer != null) rb.header("Authorization", "Bearer $bearer")
            when (method) {
                "GET" -> rb.get()
                "POST" -> rb.post((jsonBody ?: "{}").toRequestBody(media))
                else -> rb.method(method, jsonBody?.toRequestBody(media))
            }
            client.newCall(rb.build()).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    WireHttpResult.Ok(body)
                } else {
                    val code = Regex("\"code\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                        ?: "http_${resp.code}"
                    val msg = Regex("\"message\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                        ?: body
                    WireHttpResult.Err(resp.code, code, msg)
                }
            }
        } catch (e: Exception) {
            WireHttpResult.NetworkFail(e.message ?: "network")
        }
    }
}

fun sha256Hex(bytes: ByteArray): String {
    val d = MessageDigest.getInstance("SHA-256").digest(bytes)
    return d.joinToString("") { "%02x".format(it) }
}
