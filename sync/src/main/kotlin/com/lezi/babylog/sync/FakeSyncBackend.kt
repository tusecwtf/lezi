package com.lezi.babylog.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Deterministic in-memory backend for coordinator and dual-client tests.
 *
 * Mirrors core lezi-sync push rules used by client tests: LWW with existing
 * win on equal `updatedAt`, member avatar ACL, immutable media association,
 * and basic baby/record/media reference checks.
 */
class FakeSyncBackend : SyncBackend {
    private data class Row(val entity: SyncEntity, val rev: Long)

    private val rows = mutableMapOf<String, MutableMap<String, Row>>()
    private val mediaBytes = mutableMapOf<String, MutableMap<String, ByteArray>>()
    private val invites = mutableMapOf<String, Pair<String, Long>>()
    private val membershipNames = mutableMapOf<String, String>()
    private val familyNames = mutableMapOf<String, String?>()
    private var revision = 0L

    suspend fun push(familyId: String, deviceId: String, entities: List<SyncEntity>): Result<Int> =
        runCatching { pushRows(familyId, entities, FamilyRole.Owner) }

    suspend fun pull(familyId: String, cursor: Long): Result<PullResult> =
        runCatching { pullRows(familyId, cursor) }

    suspend fun invite(familyId: String): Result<Invite> = runCatching {
        val invite = Invite("TEST${invites.size + 1}", System.currentTimeMillis() + 86_400_000)
        invites[invite.code] = familyId to invite.expiresAt
        invite
    }

    suspend fun join(code: String, deviceId: String): Result<JoinResult> = runCatching {
        val invite = invites[code.uppercase()] ?: error("invalid code")
        require(invite.second >= System.currentTimeMillis()) { "expired" }
        val pull = pullRows(invite.first, 0)
        JoinResult(
            familyId = invite.first,
            token = "fake-token",
            role = FamilyRole.Member,
            entities = pull.entities,
            cursor = pull.cursor,
            familyName = familyNames[invite.first],
        )
    }

    override suspend fun create(
        baseUrl: String,
        deviceId: String,
        displayName: String?,
        createRequestId: String,
        bootstrapSecret: String?,
        familyName: String?,
    ): JoinResult {
        val name = requireMemberDisplayName(displayName)
        val sharedName = normalizeFamilyNameForWire(familyName)
        val familyId = "family-${rows.size + 1}"
        membershipNames["$familyId:$deviceId"] = name
        familyNames[familyId] = sharedName
        return JoinResult(
            familyId = familyId,
            token = "owner-token",
            role = FamilyRole.Owner,
            familyName = sharedName,
        )
    }

    override suspend fun push(session: SyncSession, entities: List<SyncEntity>) =
        pushRows(session.familyId, entities, session.role)

    override suspend fun pull(session: SyncSession) = pullRows(session.familyId, session.pullCursor)

    override suspend fun invite(session: SyncSession) = invite(session.familyId).getOrThrow()

    override suspend fun join(
        baseUrl: String,
        code: String,
        deviceId: String,
        displayName: String?,
    ): JoinResult {
        val name = requireMemberDisplayName(displayName)
        val joined = join(code, deviceId).getOrThrow()
        membershipNames["${joined.familyId}:$deviceId"] = name
        return joined
    }

    override suspend fun members(session: SyncSession) = listOf(
        FamilyMember(
            displayName = membershipNames["${session.familyId}:${session.deviceId}"]
                ?: if (session.role == FamilyRole.Owner) "管理员" else null,
            role = session.role,
            isSelf = true,
            deviceId = session.deviceId,
        ),
    )

    override suspend fun updateMyDisplayName(session: SyncSession, displayName: String) {
        membershipNames["${session.familyId}:${session.deviceId}"] =
            requireMemberDisplayName(displayName)
    }

    override suspend fun renameFamily(session: SyncSession, familyName: String?) {
        require(session.role == FamilyRole.Owner) { "仅家庭管理员可修改家庭名" }
        familyNames[session.familyId] = normalizeFamilyNameForWire(familyName)
    }

    override suspend fun leave(session: SyncSession) = Unit

    override suspend fun deleteFamily(session: SyncSession) {
        rows.remove(session.familyId)
        mediaBytes.remove(session.familyId)
    }

    override suspend fun putMedia(
        session: SyncSession,
        clientUuid: String,
        bytes: ByteArray,
        mime: String?,
    ) {
        val existing = rows[session.familyId]?.get("media:$clientUuid")?.entity
        val kind = existing?.let(::mediaKind)
        if (session.role == FamilyRole.Member && kind == "avatar") {
            throw SyncHttpException(403, "Only owner may change avatar")
        }
        mediaBytes.getOrPut(session.familyId) { mutableMapOf() }[clientUuid] = bytes
    }

    override suspend fun getMedia(session: SyncSession, clientUuid: String): ByteArray =
        mediaBytes[session.familyId]?.get(clientUuid) ?: byteArrayOf()

    private fun pushRows(familyId: String, entities: List<SyncEntity>, role: FamilyRole): Int {
        val family = rows.getOrPut(familyId) { mutableMapOf() }
        // Apply LWW first so validation sees the same effective set as the server.
        val winners = LinkedHashMap<String, SyncEntity>()
        entities.forEach { entity ->
            val key = "${entity.type}:${entity.clientUuid}"
            val existing = family[key]
            if (existing != null && existing.entity.updatedAt >= entity.updatedAt) return@forEach
            val previous = winners[key]
            if (previous == null || entity.updatedAt > previous.updatedAt) {
                winners[key] = entity
            }
        }
        validatePush(role, family, winners.values.toList())
        var applied = 0
        winners.values.forEach { entity ->
            val key = "${entity.type}:${entity.clientUuid}"
            revision++
            family[key] = Row(entity, revision)
            applied++
        }
        return applied
    }

    private fun validatePush(
        role: FamilyRole,
        family: Map<String, Row>,
        winners: List<SyncEntity>,
    ) {
        val babyIds = family.keys
            .filter { it.startsWith("baby:") }
            .map { it.removePrefix("baby:") }
            .toMutableSet()
        babyIds += winners.filter { it.type == "baby" }.map { it.clientUuid }

        val recordIds = family.keys
            .filter { it.startsWith("record:") }
            .map { it.removePrefix("record:") }
            .toMutableSet()
        recordIds += winners.filter { it.type == "record" }.map { it.clientUuid }

        winners.forEach { entity ->
            when (entity.type) {
                "record" -> {
                    val babyUuid = payloadString(entity, "baby_client_uuid")
                        ?: throw SyncHttpException(409, "record baby_client_uuid does not exist")
                    if (babyUuid !in babyIds) {
                        throw SyncHttpException(409, "record baby_client_uuid does not exist")
                    }
                }
                "media" -> {
                    val kind = mediaKind(entity)
                    val existing = family["media:${entity.clientUuid}"]?.entity
                    val existingKind = existing?.let(::mediaKind)
                    if (role == FamilyRole.Member && (kind == "avatar" || existingKind == "avatar")) {
                        throw SyncHttpException(403, "Only owner may change avatar")
                    }
                    if (existing != null && mediaAssociation(existing) != mediaAssociation(entity)) {
                        throw SyncHttpException(409, "Media kind and association are immutable")
                    }
                    when (kind) {
                        "avatar" -> {
                            val babyUuid = payloadString(entity, "baby_client_uuid")
                            if (babyUuid == null || babyUuid !in babyIds) {
                                throw SyncHttpException(
                                    409,
                                    "avatar baby_client_uuid does not exist",
                                )
                            }
                        }
                        "log" -> {
                            val recordUuid = payloadString(entity, "record_client_uuid")
                            if (recordUuid == null || recordUuid !in recordIds) {
                                throw SyncHttpException(
                                    409,
                                    "log record_client_uuid does not exist",
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun mediaKind(entity: SyncEntity): String? = payloadString(entity, "kind")

    private fun mediaAssociation(entity: SyncEntity): Triple<String?, String?, String?> =
        Triple(
            mediaKind(entity),
            payloadString(entity, "record_client_uuid"),
            payloadString(entity, "baby_client_uuid"),
        )

    private fun payloadString(entity: SyncEntity, key: String): String? =
        runCatching {
            Json.parseToJsonElement(entity.payloadJson).jsonObject[key]
                ?.jsonPrimitive
                ?.contentOrNull
        }.getOrNull()

    private fun pullRows(familyId: String, cursor: Long): PullResult {
        val changed = rows[familyId].orEmpty().values.filter { it.rev > cursor }.sortedBy { it.rev }
        return PullResult(
            changed.map { it.entity.copy(rev = it.rev) },
            changed.lastOrNull()?.rev ?: cursor,
        )
    }
}
