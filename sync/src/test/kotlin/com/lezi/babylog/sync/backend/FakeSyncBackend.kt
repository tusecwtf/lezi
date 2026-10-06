package com.lezi.babylog.sync.backend
import java.io.OutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.lezi.babylog.sync.AppUpdateMetadata
import com.lezi.babylog.sync.FamilyMember
import com.lezi.babylog.sync.appupdate.sha256Hex
import com.lezi.babylog.sync.sourceCausalHandshake
import com.lezi.babylog.sync.media.SyncMediaUploadSource
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.SyncSession
import com.lezi.babylog.sync.engine.serverStampedStableRootJson
import com.lezi.babylog.sync.session.normalizeFamilyNameForWire
import com.lezi.babylog.sync.session.requireMemberDisplayName
import com.lezi.babylog.sync.engine.causalMutationContentHash

/**
 * Deterministic in-memory backend for coordinator and dual-client tests.
 *
 * Mutable roots use the causal commit seam. The retained atomic-bundle seam accepts only
 * immutable fulfillment facts, matching the production protocol contraction.
 */
class FakeSyncBackend : SyncBackend {
    private data class Row(val entity: SyncEntity, val rev: Long)

    private data class StagedBundle(
        val draft: AtomicBundleDraft,
        var committed: Boolean = false,
        var committedCursor: Long = 0,
        var committedApplied: Int = 0,
    )

    private val rows = mutableMapOf<String, MutableMap<String, Row>>()
    private val mediaBytes = mutableMapOf<String, MutableMap<String, ByteArray>>()
    private val membershipNames = mutableMapOf<String, String>()
    /** Stable membership_id keyed by familyId:deviceId (mirrors server immutability). */
    private val membershipIds = mutableMapOf<String, String>()
    private val familyNames = mutableMapOf<String, String?>()
    /** One-stack owner membership (create/reclaim); independent of device keying. */
    private var soleFamilyId: String? = null
    private var ownerMembershipId: String? = null
    private var ownerDeviceId: String? = null
    private var ownerTokenSeq = 0
    private val bundles = mutableMapOf<String, MutableMap<String, StagedBundle>>()
    private var revision = 0L
    private var membershipSeq = 0

    private fun membershipIdFor(familyId: String, deviceId: String): String {
        val key = "$familyId:$deviceId"
        return membershipIds.getOrPut(key) {
            membershipSeq += 1
            "membership-$membershipSeq"
        }
    }

    suspend fun pull(familyId: String, cursor: Long): Result<PullResult> =
        runCatching { pullRows(familyId, cursor) }

    override suspend fun create(
        baseUrl: String,
        deviceId: String,
        displayName: String?,
        createRequestId: String,
        bootstrapSecret: String?,
        familyName: String?,
    ): SessionBootstrapResult {
        val name = requireMemberDisplayName(displayName)
        val sharedName = normalizeFamilyNameForWire(familyName)
        val existingFamilyId = soleFamilyId
        if (existingFamilyId != null) {
            val membershipId = requireNotNull(ownerMembershipId) {
                "owner membership missing for sole family"
            }
            val previousDevice = ownerDeviceId
            if (previousDevice != null && previousDevice != deviceId) {
                membershipNames.remove("$existingFamilyId:$previousDevice")
            }
            ownerDeviceId = deviceId
            membershipNames["$existingFamilyId:$deviceId"] = name
            if (sharedName != null) {
                familyNames[existingFamilyId] = sharedName
            }
            ownerTokenSeq += 1
            membershipIds["$existingFamilyId:$deviceId"] = membershipId
            return SessionBootstrapResult(
                familyId = existingFamilyId,
                accessToken = "owner-token-reclaimed-$ownerTokenSeq",
                role = FamilyRole.Owner,
                generation = FAKE_SYNC_GENERATION,
                familyName = familyNames[existingFamilyId],
                membershipId = membershipId,
                reclaimed = true,
            )
        }
        val familyId = "family-${rows.size + 1}"
        rows.getOrPut(familyId) { mutableMapOf() }
        soleFamilyId = familyId
        val membershipId = membershipIdFor(familyId, deviceId)
        ownerMembershipId = membershipId
        ownerDeviceId = deviceId
        membershipNames["$familyId:$deviceId"] = name
        familyNames[familyId] = sharedName
        return SessionBootstrapResult(
            familyId = familyId,
            accessToken = "owner-token",
            role = FamilyRole.Owner,
            generation = FAKE_SYNC_GENERATION,
            familyName = sharedName,
            membershipId = membershipId,
            reclaimed = false,
        )
    }

    override suspend fun pull(session: SyncSession, page: PullPageRequest) =
        pullRows(session.familyId, session.pullCursor).copy(pageIndex = page.pageIndex)

    override suspend fun authenticatedHandshake(session: SyncSession) =
        sourceCausalHandshake(
            principal = SyncHandshakePrincipal(
                membershipId = session.membershipId,
                deviceId = session.deviceId,
                role = session.role,
            ),
            directoryGeneration = "fake-directory-v1",
        )

    override suspend fun causalCommit(
        session: SyncSession,
        units: List<CausalMutationUnit>,
    ): CausalCommitBatchResult {
        val entities = units.map { unit ->
            val root = Json.parseToJsonElement(unit.rootJson).jsonObject
            val updatedAt = root["updated_at"]?.jsonPrimitive?.content?.toLong()
                ?: error("causal root updated_at is required")
            val deletedAt = when (val value = root["deleted_at"]) {
                null, JsonNull -> null
                else -> value.jsonPrimitive.content.toLong()
            }
            SyncEntity(
                type = unit.entityType,
                clientUuid = unit.clientUuid,
                payloadJson = JsonObject(
                    root.filterKeys { it != "updated_at" && it != "deleted_at" },
                ).toString(),
                updatedAt = updatedAt,
                deletedAt = deletedAt,
            )
        }
        pushRows(session.familyId, entities, session.role, session.membershipId)
        return CausalCommitBatchResult(
            generation = session.pullGeneration,
            results = units.map { unit ->
                CausalCommitUnitResult(
                    status = CausalCommitStatus.ACCEPTED,
                    mutationId = unit.mutationId,
                    requestHash = causalMutationContentHash(unit),
                    stableVersionId = "fake-v-${unit.mutationId}",
                    stableRootJson = serverStampedStableRootJson(
                        unit.entityType,
                        unit.rootJson,
                        session.membershipId,
                    ),
                    stableMedia = unit.media,
                    stableDeleted = unit.deleted,
                    stableDeletedAt = if (unit.deleted) {
                        Json.parseToJsonElement(unit.rootJson).jsonObject["deleted_at"]
                            ?.jsonPrimitive
                            ?.contentOrNull
                            ?.toLongOrNull()
                    } else {
                        null
                    },
                )
            },
        )
    }

    private suspend fun members(session: SyncSession): List<FamilyMember> {
        require(session.membershipId.isNotBlank()) {
            "current session membershipId is required"
        }
        return listOf(
            FamilyMember(
                displayName = membershipNames["${session.familyId}:${session.deviceId}"]
                    ?: if (session.role == FamilyRole.Owner) "管理员" else "家庭成员",
                role = session.role,
                isSelf = true,
                membershipId = session.membershipId,
            ),
        )
    }

    override suspend fun memberDirectory(session: SyncSession) = FamilyMemberDirectorySnapshot(
        generation = "fake-directory-v1",
        members = members(session),
    )

    override suspend fun updateMyDisplayName(
        session: SyncSession,
        displayName: String,
    ): DisplayNameUpdateResult {
        val normalized = requireMemberDisplayName(displayName)
        membershipNames["${session.familyId}:${session.deviceId}"] = normalized
        return DisplayNameUpdateResult.Updated(normalized)
    }

    override suspend fun renameFamily(session: SyncSession, familyName: String?) {
        require(session.role == FamilyRole.Owner) { "仅家庭管理员可修改家庭名" }
        familyNames[session.familyId] = normalizeFamilyNameForWire(familyName)
    }

    override suspend fun leave(session: SyncSession) = Unit

    override suspend fun removeMember(session: SyncSession, membershipId: String) {
        require(session.role == FamilyRole.Owner) { "仅家庭管理员可移除家人" }
        val target = membershipId.trim()
        require(target.isNotEmpty()) { "请选择要移除的家人" }
        require(target != session.membershipId.trim()) { "不能移除自己" }
        removedMembershipIds += target
    }

    /** Membership ids removed via [removeMember] (test inspection). */
    val removedMembershipIds = mutableListOf<String>()

    override suspend fun deleteFamily(
        session: SyncSession,
        familyName: String,
        rootPassword: String,
    ) {
        require(session.role == FamilyRole.Owner)
        require(normalizeFamilyNameForWire(familyName) == familyNames[session.familyId])
        require(rootPassword.isNotBlank())
        rows.remove(session.familyId)
        mediaBytes.remove(session.familyId)
    }

    override suspend fun getMedia(session: SyncSession, clientUuid: String): ByteArray =
        mediaBytes[session.familyId]?.get(clientUuid) ?: byteArrayOf()

    /** When null, [getAppUpdateMetadata] fails as if the server has no package. */
    var appUpdateMetadata: AppUpdateMetadata? = null
    var appUpdateApkBytes: ByteArray? = null
    var downloadAppUpdateApkCalls = 0

    override suspend fun getAppUpdateMetadata(session: SyncSession): AppUpdateMetadata =
        appUpdateMetadata
            ?: throw SyncHttpException(404, """{"detail":"App update metadata is not available"}""")

    override suspend fun downloadAppUpdateApk(
        session: SyncSession,
        target: OutputStream,
    ): AppUpdateApkDownload {
        downloadAppUpdateApkCalls += 1
        val bytes = appUpdateApkBytes
            ?: throw SyncHttpException(404, """{"detail":"App update package is not available"}""")
        target.write(bytes)
        return AppUpdateApkDownload(
            sha256 = sha256Hex(bytes),
            byteCount = bytes.size.toLong(),
        )
    }

    override suspend fun stageBundle(
        session: SyncSession,
        draft: AtomicBundleDraft,
    ): BundleStageStatus {
        require(draft.root.type == "fulfillment_candidate" && draft.media.isEmpty()) {
            "bundle must contain one fulfillment_candidate and no media"
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

    override suspend fun commitBundle(
        session: SyncSession,
        bundleId: String,
    ): BundleCommitResult {
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
        val family = rows.getOrPut(session.familyId) { mutableMapOf() }
        val publishedRoot = family["${staged.draft.root.type}:${staged.draft.root.clientUuid}"]
        if (publishedRoot != null && publishedRoot.entity.updatedAt > staged.draft.root.updatedAt) {
            throw SyncHttpException(409, "bundle root is not newer than the published version")
        }
        val packageEntities = listOf(staged.draft.root)
        val applied = pushRows(
            session.familyId,
            packageEntities,
            session.role,
            session.membershipId,
        )
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

    private fun StagedBundle.toStatus(): BundleStageStatus {
        return BundleStageStatus(
            bundleId = draft.bundleId,
            status = if (committed) "committed" else "staging",
            missingMedia = emptyList(),
            stagedMedia = emptyList(),
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
        if (role == FamilyRole.Member && entities.any { it.type == "baby" }) {
            throw SyncHttpException(403, "Only the family owner may change babies")
        }
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
            stampRecordAuthor(it, family, membershipId)
                .let { entity -> stampCustomItem(entity, family, role, membershipId) }
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

    private fun stampRecordAuthor(
        entity: SyncEntity,
        family: Map<String, Row>,
        membershipId: String,
    ): SyncEntity {
        if (entity.type != "record") return entity
        val existing = family["record:${entity.clientUuid}"]?.entity
        val payload = runCatching {
            Json.parseToJsonElement(entity.payloadJson).jsonObject.toMutableMap()
        }.getOrElse { linkedMapOf() }
        val canonicalMembership = if (existing == null) {
            membershipId
        } else {
            payloadString(existing, "created_by_membership_id")
                ?.takeIf(String::isNotBlank)
        }
        if (canonicalMembership == null) {
            payload.remove("created_by_membership_id")
        } else {
            payload["created_by_membership_id"] =
                kotlinx.serialization.json.JsonPrimitive(canonicalMembership)
        }
        return entity.copy(
            payloadJson = kotlinx.serialization.json.JsonObject(payload).toString(),
        )
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
            generation = FAKE_SYNC_GENERATION,
            hasMore = false,
            familyName = familyNames[familyId],
        )
    }
}

private const val FAKE_SYNC_GENERATION = "fake-generation"
