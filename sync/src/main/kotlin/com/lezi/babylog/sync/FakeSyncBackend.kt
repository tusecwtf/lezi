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

    private data class StagedBundle(
        val draft: AtomicBundleDraft,
        val stagedMedia: MutableSet<String> = mutableSetOf(),
        var committed: Boolean = false,
        var committedCursor: Long = 0,
        var committedApplied: Int = 0,
    )

    private val rows = mutableMapOf<String, MutableMap<String, Row>>()
    private val mediaBytes = mutableMapOf<String, MutableMap<String, ByteArray>>()
    private val invites = mutableMapOf<String, Pair<String, Long>>()
    private val membershipNames = mutableMapOf<String, String>()
    /** Stable membership_id keyed by familyId:deviceId (mirrors server immutability). */
    private val membershipIds = mutableMapOf<String, String>()
    private val familyNames = mutableMapOf<String, String?>()
    private val bundles = mutableMapOf<String, MutableMap<String, StagedBundle>>()
    private val bundleMediaBytes =
        mutableMapOf<String, MutableMap<String, MutableMap<String, ByteArray>>>()
    /** When false, atomic bundle calls throw [AtomicBundleUnsupportedException]. */
    var supportsAtomicBundle: Boolean = true
    private var revision = 0L
    private var membershipSeq = 0

    private fun membershipIdFor(familyId: String, deviceId: String): String {
        val key = "$familyId:$deviceId"
        return membershipIds.getOrPut(key) {
            membershipSeq += 1
            "membership-$membershipSeq"
        }
    }

    suspend fun push(familyId: String, deviceId: String, entities: List<SyncEntity>): Result<Int> =
        runCatching {
            pushRows(
                familyId,
                entities,
                FamilyRole.Owner,
                membershipIdFor(familyId, deviceId),
            )
        }

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
            membershipId = membershipIdFor(invite.first, deviceId),
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
            membershipId = membershipIdFor(familyId, deviceId),
        )
    }

    override suspend fun push(session: SyncSession, entities: List<SyncEntity>) =
        pushRows(session.familyId, entities, session.role, session.membershipId)

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
            membershipId = session.membershipId.ifBlank {
                membershipIdFor(session.familyId, session.deviceId)
            },
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

    override suspend fun stageBundle(
        session: SyncSession,
        draft: AtomicBundleDraft,
    ): BundleStageStatus {
        requireAtomicBundle()
        require(draft.root.type == "record" || draft.root.type == "care_plan") {
            "bundle root type must be record or care_plan"
        }
        val familyBundles = bundles.getOrPut(session.familyId) { mutableMapOf() }
        val existing = familyBundles[draft.bundleId]
        if (existing?.committed == true) {
            require(existing.draft.root.clientUuid == draft.root.clientUuid) {
                "bundle already committed with different content"
            }
            return existing.toStatus()
        }
        familyBundles[draft.bundleId] = StagedBundle(draft = draft)
        return familyBundles.getValue(draft.bundleId).toStatus()
    }

    override suspend fun putBundleMedia(
        session: SyncSession,
        bundleId: String,
        clientUuid: String,
        bytes: ByteArray,
        mime: String?,
    ): BundleStageStatus {
        requireAtomicBundle()
        val staged = bundles[session.familyId]?.get(bundleId)
            ?: throw SyncHttpException(404, "Bundle not found")
        if (staged.committed) return staged.toStatus()
        require(staged.draft.media.any { it.clientUuid == clientUuid }) {
            "media is not listed in the bundle manifest"
        }
        val mediaEntity = staged.draft.media.first { it.clientUuid == clientUuid }
        require(mediaEntity.deletedAt == null) { "tombstone media does not accept bytes" }
        val declared = payloadLong(mediaEntity, "byte_size")
        if (declared != null && declared != bytes.size.toLong()) {
            throw SyncHttpException(422, "Media body size does not match declared byte_size")
        }
        bundleMediaBytes
            .getOrPut(session.familyId) { mutableMapOf() }
            .getOrPut(bundleId) { mutableMapOf() }[clientUuid] = bytes
        staged.stagedMedia += clientUuid
        return staged.toStatus()
    }

    override suspend fun commitBundle(
        session: SyncSession,
        bundleId: String,
    ): BundleCommitResult {
        requireAtomicBundle()
        val staged = bundles[session.familyId]?.get(bundleId)
            ?: throw SyncHttpException(404, "Bundle not found")
        if (staged.committed) {
            return BundleCommitResult(
                bundleId = bundleId,
                status = "committed",
                applied = staged.committedApplied,
                cursor = staged.committedCursor,
            )
        }
        val missing = staged.draft.media
            .filter { it.deletedAt == null && it.clientUuid !in staged.stagedMedia }
            .map { it.clientUuid }
        if (missing.isNotEmpty()) {
            throw SyncHttpException(422, "bundle media bytes are incomplete")
        }
        val family = rows.getOrPut(session.familyId) { mutableMapOf() }
        val publishedRoot = family["${staged.draft.root.type}:${staged.draft.root.clientUuid}"]
        if (publishedRoot != null && publishedRoot.entity.updatedAt > staged.draft.root.updatedAt) {
            throw SyncHttpException(409, "bundle root is not newer than the published version")
        }
        val packageEntities = listOf(staged.draft.root) + staged.draft.media
        val applied = pushRows(
            session.familyId,
            packageEntities,
            session.role,
            session.membershipId,
        )
        // Install staged media into published media store.
        staged.draft.media.filter { it.deletedAt == null }.forEach { media ->
            val bytes = bundleMediaBytes[session.familyId]?.get(bundleId)?.get(media.clientUuid)
            if (bytes != null) {
                mediaBytes.getOrPut(session.familyId) { mutableMapOf() }[media.clientUuid] = bytes
            }
        }
        staged.committed = true
        staged.committedApplied = applied
        staged.committedCursor = revision
        return BundleCommitResult(
            bundleId = bundleId,
            status = "committed",
            applied = applied,
            cursor = revision,
        )
    }

    private fun requireAtomicBundle() {
        if (!supportsAtomicBundle) throw AtomicBundleUnsupportedException()
    }

    private fun StagedBundle.toStatus(): BundleStageStatus {
        val required = draft.media.filter { it.deletedAt == null }.map { it.clientUuid }
        return BundleStageStatus(
            bundleId = draft.bundleId,
            status = if (committed) "committed" else "staging",
            missingMedia = required.filter { it !in stagedMedia },
            stagedMedia = stagedMedia.sorted(),
        )
    }

    private fun payloadLong(entity: SyncEntity, key: String): Long? =
        runCatching {
            Json.parseToJsonElement(entity.payloadJson).jsonObject[key]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.toLongOrNull()
        }.getOrNull()

    private fun pushRows(
        familyId: String,
        entities: List<SyncEntity>,
        role: FamilyRole,
        membershipId: String,
    ): Int {
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
        val stamped = winners.values.map {
            stampCustomItem(it, family, role, membershipId)
                .let { entity -> stampFulfillmentCandidate(entity, family, role, membershipId) }
        }
        validatePush(role, membershipId, family, stamped)
        var applied = 0
        stamped.forEach { entity ->
            val key = "${entity.type}:${entity.clientUuid}"
            revision++
            family[key] = Row(entity, revision)
            applied++
        }
        return applied
    }

    private fun stampCustomItem(
        entity: SyncEntity,
        family: Map<String, Row>,
        role: FamilyRole,
        membershipId: String,
    ): SyncEntity {
        if (entity.type != "custom_item") return entity
        val existing = family["custom_item:${entity.clientUuid}"]?.entity
        if (existing != null && existing.deletedAt != null && entity.deletedAt == null) {
            throw SyncHttpException(409, "Deleted custom item cannot be resurrected")
        }
        val payload = runCatching {
            Json.parseToJsonElement(entity.payloadJson).jsonObject.toMutableMap()
        }.getOrElse { linkedMapOf() }
        if (existing == null) {
            payload["created_by_membership_id"] =
                kotlinx.serialization.json.JsonPrimitive(membershipId)
        } else {
            val creator = payloadString(existing, "created_by_membership_id").orEmpty()
            payload["created_by_membership_id"] =
                kotlinx.serialization.json.JsonPrimitive(creator)
            if (role == FamilyRole.Member && (creator.isEmpty() || creator != membershipId)) {
                throw SyncHttpException(
                    403,
                    "Only the creator or family owner may change this custom item",
                )
            }
        }
        return entity.copy(
            payloadJson = kotlinx.serialization.json.JsonObject(payload).toString(),
        )
    }

    /**
     * Mirror lezi-sync: freeze submitter membership, role, and confirmed_at on
     * first accept; later pushes cannot rewrite those fields.
     */
    private fun stampFulfillmentCandidate(
        entity: SyncEntity,
        family: Map<String, Row>,
        role: FamilyRole,
        membershipId: String,
    ): SyncEntity {
        if (entity.type != "fulfillment_candidate") return entity
        val existing = family["fulfillment_candidate:${entity.clientUuid}"]?.entity
        val payload = runCatching {
            Json.parseToJsonElement(entity.payloadJson).jsonObject.toMutableMap()
        }.getOrElse { linkedMapOf() }
        val roleWire = when (role) {
            FamilyRole.Owner -> "owner"
            FamilyRole.Member -> "member"
            FamilyRole.None -> "member"
        }
        if (existing != null) {
            val frozenMembership = payloadString(existing, "submitter_membership_id")
                ?.takeIf { it.isNotBlank() }
                ?: membershipId
            val frozenRole = payloadString(existing, "submitter_role")
                ?.takeIf { it.isNotBlank() }
                ?: roleWire
            val frozenAt = payloadLong(existing, "confirmed_at")
                ?: entity.updatedAt
            payload["submitter_membership_id"] =
                kotlinx.serialization.json.JsonPrimitive(frozenMembership)
            payload["submitter_role"] =
                kotlinx.serialization.json.JsonPrimitive(frozenRole)
            payload["confirmed_at"] =
                kotlinx.serialization.json.JsonPrimitive(frozenAt)
        } else {
            payload["submitter_membership_id"] =
                kotlinx.serialization.json.JsonPrimitive(membershipId)
            payload["submitter_role"] =
                kotlinx.serialization.json.JsonPrimitive(roleWire)
            // Accept-time freeze: ignore client-forged confirmed_at on first write.
            payload["confirmed_at"] =
                kotlinx.serialization.json.JsonPrimitive(entity.updatedAt)
        }
        return entity.copy(
            payloadJson = kotlinx.serialization.json.JsonObject(payload).toString(),
        )
    }

    private fun validatePush(
        role: FamilyRole,
        membershipId: String,
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

        val planIds = family.keys
            .filter { it.startsWith("care_plan:") }
            .map { it.removePrefix("care_plan:") }
            .toMutableSet()
        planIds += winners.filter { it.type == "care_plan" }.map { it.clientUuid }

        winners.forEach { entity ->
            when (entity.type) {
                "custom_item" -> {
                    // ACL already enforced in [stampCustomItem]; validate wire shape.
                    val name = payloadString(entity, "name")
                    if (name.isNullOrBlank() && entity.deletedAt == null) {
                        throw SyncHttpException(422, "custom_item name is required")
                    }
                    val icon = payloadLong(entity, "icon_slot")
                    if (icon != null && (icon < 0 || icon > 7)) {
                        throw SyncHttpException(422, "icon_slot must be 0..7")
                    }
                    // Keep membershipId referenced so dual-member tests stay honest.
                    check(membershipId.isNotBlank() || role == FamilyRole.Owner)
                }
                "record" -> {
                    val babyUuid = payloadString(entity, "baby_client_uuid")
                        ?: throw SyncHttpException(409, "record baby_client_uuid does not exist")
                    if (babyUuid !in babyIds) {
                        throw SyncHttpException(409, "record baby_client_uuid does not exist")
                    }
                }
                "fulfillment_candidate" -> {
                    val planUuid = payloadString(entity, "care_plan_client_uuid")
                        ?: throw SyncHttpException(
                            409,
                            "fulfillment_candidate care_plan_client_uuid does not exist",
                        )
                    val recordUuid = payloadString(entity, "record_client_uuid")
                        ?: throw SyncHttpException(
                            409,
                            "fulfillment_candidate record_client_uuid does not exist",
                        )
                    if (planUuid !in planIds) {
                        throw SyncHttpException(
                            409,
                            "fulfillment_candidate care_plan_client_uuid does not exist",
                        )
                    }
                    if (recordUuid !in recordIds) {
                        throw SyncHttpException(
                            409,
                            "fulfillment_candidate record_client_uuid does not exist",
                        )
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
                            val carePlanUuid = payloadString(entity, "care_plan_client_uuid")
                            // Mirrors lezi-sync: log media attaches to record XOR care_plan.
                            when {
                                recordUuid != null && carePlanUuid != null -> {
                                    throw SyncHttpException(
                                        422,
                                        "log media cannot bind record and care_plan together",
                                    )
                                }
                                recordUuid != null -> {
                                    if (recordUuid !in recordIds) {
                                        throw SyncHttpException(
                                            409,
                                            "log record_client_uuid does not exist",
                                        )
                                    }
                                }
                                carePlanUuid != null -> {
                                    if (carePlanUuid !in planIds) {
                                        throw SyncHttpException(
                                            409,
                                            "log media care_plan_client_uuid does not exist",
                                        )
                                    }
                                }
                                else -> {
                                    throw SyncHttpException(
                                        422,
                                        "log media requires record_client_uuid or care_plan_client_uuid",
                                    )
                                }
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
