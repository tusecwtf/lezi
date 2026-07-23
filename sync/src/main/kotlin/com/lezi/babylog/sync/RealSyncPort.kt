package com.lezi.babylog.sync

import com.lezi.babylog.core.common.newClientUuid
import com.lezi.babylog.core.database.BabyDao
import com.lezi.babylog.core.database.BabyEntity
import com.lezi.babylog.core.database.OutboxDao
import com.lezi.babylog.core.database.OutboxEntity
import com.lezi.babylog.core.database.RecordDao
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.model.Family
import com.lezi.babylog.core.model.SyncStatus
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

interface SyncBackend {
    suspend fun push(familyId: String, deviceId: String, entities: List<SyncEntity>): Result<Int>
    suspend fun pull(familyId: String, cursor: Long): Result<PullResult>
    suspend fun invite(familyId: String): Result<Invite>
    suspend fun join(code: String, deviceId: String): Result<JoinResult>
}

data class SyncEntity(
    val type: String,
    val clientUuid: String,
    val payloadJson: String,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val rev: Long = 0,
)

data class PullResult(val entities: List<SyncEntity>, val cursor: Long)
data class JoinResult(val familyId: String, val entities: List<SyncEntity>, val cursor: Long)

class HttpSyncBackend(
    private val baseUrl: String,
) : SyncBackend {
    override suspend fun push(familyId: String, deviceId: String, entities: List<SyncEntity>): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                val arr = JSONArray()
                entities.forEach { e ->
                    arr.put(
                        JSONObject()
                            .put("type", e.type)
                            .put("client_uuid", e.clientUuid)
                            .put("payload", JSONObject(e.payloadJson))
                            .put("updated_at", e.updatedAt)
                            .put("deleted_at", e.deletedAt),
                    )
                }
                val body = JSONObject()
                    .put("family_id", familyId)
                    .put("device_id", deviceId)
                    .put("entities", arr)
                val resp = postJson("$baseUrl/v1/push", body)
                resp.getInt("applied")
            }
        }

    override suspend fun pull(familyId: String, cursor: Long): Result<PullResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "$baseUrl/v1/pull?family_id=${familyId}&cursor=$cursor"
                val resp = getJson(url)
                val arr = resp.getJSONArray("entities")
                val list = buildList {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        add(
                            SyncEntity(
                                type = o.getString("type"),
                                clientUuid = o.getString("client_uuid"),
                                payloadJson = o.getJSONObject("payload").toString(),
                                updatedAt = o.getLong("updated_at"),
                                deletedAt = if (o.isNull("deleted_at")) null else o.getLong("deleted_at"),
                                rev = o.optLong("rev"),
                            ),
                        )
                    }
                }
                PullResult(list, resp.getLong("cursor"))
            }
        }

    override suspend fun invite(familyId: String): Result<Invite> =
        withContext(Dispatchers.IO) {
            runCatching {
                val resp = postJson("$baseUrl/v1/invite", JSONObject().put("family_id", familyId))
                Invite(resp.getString("code"), resp.getLong("expires_at"))
            }
        }

    override suspend fun join(code: String, deviceId: String): Result<JoinResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val resp = postJson(
                    "$baseUrl/v1/join",
                    JSONObject().put("code", code).put("device_id", deviceId),
                )
                val arr = resp.getJSONArray("entities")
                val list = buildList {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        add(
                            SyncEntity(
                                type = o.getString("type"),
                                clientUuid = o.getString("client_uuid"),
                                payloadJson = o.getJSONObject("payload").toString(),
                                updatedAt = o.getLong("updated_at"),
                                deletedAt = if (o.isNull("deleted_at")) null else o.getLong("deleted_at"),
                                rev = o.optLong("rev"),
                            ),
                        )
                    }
                }
                JoinResult(resp.getString("family_id"), list, resp.getLong("cursor"))
            }
        }

    private fun postJson(url: String, body: JSONObject): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connectTimeout = 8000
            readTimeout = 8000
        }
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        if (code !in 200..299) error("HTTP $code: $text")
        return JSONObject(text)
    }

    private fun getJson(url: String): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        if (code !in 200..299) error("HTTP $code: $text")
        return JSONObject(text)
    }
}

/** In-memory dual-client backend for unit tests. */
class FakeSyncBackend : SyncBackend {
    private data class Row(
        val type: String,
        val clientUuid: String,
        var payloadJson: String,
        var updatedAt: Long,
        var deletedAt: Long?,
        var rev: Long,
    )

    private val store = mutableMapOf<String, MutableMap<String, Row>>() // family -> key->row
    private val invites = mutableMapOf<String, Pair<String, Long>>() // code -> family, exp
    private var rev = 0L

    private fun key(type: String, uuid: String) = "$type::$uuid"

    override suspend fun push(familyId: String, deviceId: String, entities: List<SyncEntity>): Result<Int> {
        val map = store.getOrPut(familyId) { mutableMapOf() }
        var applied = 0
        for (e in entities) {
            val k = key(e.type, e.clientUuid)
            val existing = map[k]
            if (existing != null && existing.updatedAt > e.updatedAt) continue
            rev += 1
            map[k] = Row(e.type, e.clientUuid, e.payloadJson, e.updatedAt, e.deletedAt, rev)
            applied++
        }
        return Result.success(applied)
    }

    override suspend fun pull(familyId: String, cursor: Long): Result<PullResult> {
        val rows = store[familyId]?.values?.filter { it.rev > cursor }?.sortedBy { it.rev }.orEmpty()
        val entities = rows.map {
            SyncEntity(it.type, it.clientUuid, it.payloadJson, it.updatedAt, it.deletedAt, it.rev)
        }
        return Result.success(PullResult(entities, entities.lastOrNull()?.rev ?: cursor))
    }

    override suspend fun invite(familyId: String): Result<Invite> {
        val code = newClientUuid().take(8).uppercase()
        val exp = System.currentTimeMillis() + 24 * 3600_000L
        invites[code] = familyId to exp
        return Result.success(Invite(code, exp))
    }

    override suspend fun join(code: String, deviceId: String): Result<JoinResult> {
        val inv = invites[code.uppercase()] ?: return Result.failure(IllegalArgumentException("invalid code"))
        if (inv.second < System.currentTimeMillis()) return Result.failure(IllegalStateException("expired"))
        val pull = pull(inv.first, 0).getOrThrow()
        return Result.success(JoinResult(inv.first, pull.entities, pull.cursor))
    }
}

@Singleton
class RealSyncPort @Inject constructor(
    private val backend: SyncBackend,
    private val outboxDao: OutboxDao,
    private val recordDao: RecordDao,
    private val babyDao: BabyDao,
) : SyncPort {
    private val _status = MutableStateFlow(SyncStatus.Idle)
    private var enabled = true
    private var pullCursor = 0L
    private var joinedFamilyId: String? = null

    override fun status(): Flow<SyncStatus> = _status.asStateFlow()
    override fun isEnabled(): Boolean = enabled

    fun setEnabled(value: Boolean) {
        enabled = value
        _status.value = if (value) SyncStatus.Idle else SyncStatus.Disabled
    }

    override suspend fun pull(familyId: String): Result<Unit> {
        if (!enabled) return Result.success(Unit)
        _status.value = SyncStatus.Syncing
        return backend.pull(familyId, pullCursor).mapCatching { result ->
            applyRemote(result.entities)
            pullCursor = result.cursor
            joinedFamilyId = familyId
            _status.value = SyncStatus.Idle
        }.onFailure { _status.value = SyncStatus.Error }
    }

    override suspend fun push(familyId: String): Result<Unit> {
        if (!enabled) return Result.success(Unit)
        _status.value = SyncStatus.Syncing
        val pending = outboxDao.peek(200)
        if (pending.isEmpty()) {
            _status.value = SyncStatus.Idle
            return Result.success(Unit)
        }
        val entities = pending.map {
            SyncEntity(
                type = it.entityType,
                clientUuid = it.clientUuid,
                payloadJson = it.payloadJson,
                updatedAt = it.updatedAt,
                deletedAt = it.deletedAt,
            )
        }
        return backend.push(familyId, "device", entities).mapCatching {
            outboxDao.deleteIds(pending.map { row -> row.id })
            _status.value = SyncStatus.Idle
        }.onFailure { _status.value = SyncStatus.Error }
    }

    override suspend fun createInvite(familyId: String): Result<Invite> {
        if (!enabled) return Result.failure(SyncNotEnabledException())
        return backend.invite(familyId)
    }

    override suspend fun joinWithCode(code: String): Result<Family> {
        if (!enabled) return Result.failure(SyncNotEnabledException())
        return backend.join(code, "device").mapCatching { join ->
            applyRemote(join.entities)
            pullCursor = join.cursor
            joinedFamilyId = join.familyId
            Family(id = join.familyId.toLongOrNull() ?: 1L, ownerUserId = 0, createdAt = System.currentTimeMillis())
        }
    }

    override suspend fun leave(familyId: String): Result<Unit> {
        joinedFamilyId = null
        pullCursor = 0
        return Result.success(Unit)
    }

    private suspend fun applyRemote(entities: List<SyncEntity>) {
        for (e in entities) {
            when (e.type) {
                "record" -> {
                    val existing = recordDao.getByClientUuid(e.clientUuid)
                    if (existing != null && existing.updatedAt > e.updatedAt) continue
                    val p = JSONObject(e.payloadJson)
                    recordDao.upsert(
                        RecordEntity(
                            id = existing?.id ?: 0,
                            clientUuid = e.clientUuid,
                            babyId = p.optLong("baby_id", existing?.babyId ?: 0),
                            type = p.optString("type", existing?.type ?: "other"),
                            timestamp = p.optLong("timestamp", e.updatedAt),
                            endTimestamp = if (p.has("end_timestamp") && !p.isNull("end_timestamp")) p.getLong("end_timestamp") else existing?.endTimestamp,
                            note = if (p.has("note") && !p.isNull("note")) p.getString("note") else existing?.note,
                            createdByUserId = p.optLong("created_by_user_id", existing?.createdByUserId ?: 1),
                            payloadJson = p.optJSONObject("payload")?.toString() ?: p.optString("payload_json", existing?.payloadJson ?: "{}"),
                            updatedAt = e.updatedAt,
                            deletedAt = e.deletedAt,
                        ),
                    )
                }
                "baby" -> {
                    // best-effort upsert by client uuid not indexed for get — skip complex merge; records are main path
                }
            }
        }
    }
}

@Singleton
class OutboxWriter @Inject constructor(
    private val outboxDao: OutboxDao,
    private val syncPort: SyncPort,
) {
    suspend fun enqueueRecord(
        familyId: String,
        clientUuid: String,
        babyId: Long,
        type: String,
        timestamp: Long,
        endTimestamp: Long?,
        note: String?,
        payloadJson: String,
        updatedAt: Long,
        deletedAt: Long? = null,
    ) {
        if (!syncPort.isEnabled()) return
        val payload = JSONObject()
            .put("baby_id", babyId)
            .put("type", type)
            .put("timestamp", timestamp)
            .put("end_timestamp", endTimestamp)
            .put("note", note)
            .put("payload_json", payloadJson)
        outboxDao.enqueue(
            OutboxEntity(
                familyId = familyId,
                entityType = "record",
                clientUuid = clientUuid,
                payloadJson = payload.toString(),
                updatedAt = updatedAt,
                deletedAt = deletedAt,
            ),
        )
    }
}
