package com.lezi.babylog.sync.conflict

import com.lezi.babylog.sync.engine.SyncWireMapper
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.isNextFeedPlanNote
import com.lezi.babylog.sync.backend.CausalMediaItem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

internal fun JsonObject.toConflictSnapshot(
    context: String,
    maxBranches: Int = ConflictSnapshotValidation.MAX_BRANCHES_PER_PAGE,
): ConflictSnapshot {
    requireClosedKeys(
        context,
        "contract",
        "conflict_id",
        "entity_type",
        "client_uuid",
        "snapshot_token",
        "expires_at",
        "stable",
        "branches",
        "conflicting",
        "auto_merged",
        "page_index",
        "continuation",
        "complete",
    )
    require(requiredString("contract", context) == "conflict_snapshot_v2") {
        "$context.contract 无效"
    }
    val entityType = ConflictRootType.fromWire(requiredString("entity_type", context))
    val stable = requiredObject("stable", context).toVersion(entityType, "$context.stable")
    val branches = requiredArray("branches", context).mapIndexed { index, raw ->
        (raw as? JsonObject)?.toVersion(entityType, "$context.branches[$index]")
            ?: throw IllegalArgumentException("$context.branches[$index] 不是对象")
    }
    require(branches.size <= maxBranches) {
        "$context.branches 超出当前 snapshot 上限"
    }
    require(branches.map { it.versionId } == branches.map { it.versionId }.sorted()) {
        "$context.branches 必须按 version_id 排序"
    }
    require(branches.map { it.versionId }.distinct().size == branches.size) {
        "$context.branches 包含重复 version_id"
    }
    val conflicting = requiredArray("conflicting", context).mapIndexed { index, raw ->
        (raw as? JsonObject)?.toConflictingPath("$context.conflicting[$index]")
            ?: throw IllegalArgumentException("$context.conflicting[$index] 不是对象")
    }
    val autoMerged = requiredArray("auto_merged", context).mapIndexed { index, raw ->
        (raw as? JsonObject)?.toAutoMergedPath("$context.auto_merged[$index]")
            ?: throw IllegalArgumentException("$context.auto_merged[$index] 不是对象")
    }
    require(conflicting.size + autoMerged.size <= ConflictSnapshotValidation.MAX_RESOLUTION_PATHS) {
        "$context resolution paths 超出上限"
    }
    val conflictingPaths = conflicting.map { it.path }
    val autoPaths = autoMerged.map { it.path }
    require(conflictingPaths.distinct().size == conflictingPaths.size) {
        "$context.conflicting path 重复"
    }
    require(autoPaths.distinct().size == autoPaths.size) { "$context.auto_merged path 重复" }
    require(conflictingPaths.toSet().intersect(autoPaths.toSet()).isEmpty()) {
        "$context conflicting/auto_merged path 重叠"
    }
    val complete = requiredBoolean("complete", context)
    val continuation = optionalString("continuation", context)
    require(complete == (continuation == null)) {
        "$context.complete 与 continuation 不一致"
    }
    val token = ConflictSnapshotValidation.requireToken(
        requiredString("snapshot_token", context),
        "$context.snapshot_token",
    )
    return ConflictSnapshot(
        conflictId = ConflictSnapshotValidation.requireUuid(
            requiredString("conflict_id", context),
            "$context.conflict_id",
        ),
        entityType = entityType,
        clientUuid = ConflictSnapshotValidation.requireUuid(
            requiredString("client_uuid", context),
            "$context.client_uuid",
        ),
        snapshotToken = token,
        expiresAt = ConflictSnapshotValidation.requireNonNegative(
            requiredLong("expires_at", context),
            "$context.expires_at",
        ),
        stable = stable,
        branches = branches,
        conflicting = conflicting,
        autoMerged = autoMerged,
        pageIndex = requiredInt("page_index", context).also {
            require(it >= 0) { "$context.page_index 无效" }
        },
        continuation = continuation?.let {
            ConflictSnapshotValidation.requireToken(it, "$context.continuation")
        },
        complete = complete,
    )
}

/** Canonical persistence codec used below the domain/UI seam. */
object ConflictSnapshotCodec {
    private val json = Json

    fun decode(raw: String): ConflictSnapshot {
        return decode(raw, ConflictSnapshotValidation.MAX_BRANCHES_PER_PAGE)
    }

    fun decodeComplete(raw: String): ConflictSnapshot {
        val snapshot = decode(raw, ConflictSnapshotPaging.MAX_BRANCHES_PER_SNAPSHOT)
        require(snapshot.pageIndex == 0 && snapshot.complete && snapshot.continuation == null) {
            "conflict snapshot cache 不是完整 page-0 snapshot"
        }
        return snapshot
    }

    private fun decode(raw: String, maxBranches: Int): ConflictSnapshot {
        val value = runCatching { json.parseToJsonElement(raw) }.getOrElse {
            throw IllegalArgumentException("conflict snapshot cache 不是 JSON", it)
        } as? JsonObject ?: throw IllegalArgumentException("conflict snapshot cache 不是对象")
        return value.toConflictSnapshot("conflict snapshot cache", maxBranches)
    }

    fun encode(snapshot: ConflictSnapshot): String = buildJsonObject {
        put("contract", JsonPrimitive("conflict_snapshot_v2"))
        put("conflict_id", JsonPrimitive(snapshot.conflictId))
        put("entity_type", JsonPrimitive(snapshot.entityType.wireName))
        put("client_uuid", JsonPrimitive(snapshot.clientUuid))
        put("snapshot_token", JsonPrimitive(snapshot.snapshotToken))
        put("expires_at", JsonPrimitive(snapshot.expiresAt))
        put("stable", snapshot.stable.toJson())
        put("branches", buildJsonArray { snapshot.branches.forEach { add(it.toJson()) } })
        put(
            "conflicting",
            buildJsonArray {
                snapshot.conflicting.forEach { conflict ->
                    add(
                        buildJsonObject {
                            put("path", JsonPrimitive(conflict.path))
                            put(
                                "candidates",
                                buildJsonArray {
                                    conflict.candidates.forEach { candidate ->
                                        add(
                                            buildJsonObject {
                                                put("choice_id", JsonPrimitive(candidate.choiceId))
                                                put("outcome", candidate.outcome.toJson())
                                                put(
                                                    "sources",
                                                    buildJsonArray {
                                                        candidate.sources.forEach { add(it.toJson()) }
                                                    },
                                                )
                                            },
                                        )
                                    }
                                },
                            )
                        },
                    )
                }
            },
        )
        put(
            "auto_merged",
            buildJsonArray {
                snapshot.autoMerged.forEach { outcome ->
                    add(
                        buildJsonObject {
                            put("path", JsonPrimitive(outcome.path))
                            put("outcome", outcome.outcome.toJson())
                            put(
                                "sources",
                                buildJsonArray { outcome.sources.forEach { add(it.toJson()) } },
                            )
                        },
                    )
                }
            },
        )
        put("page_index", JsonPrimitive(snapshot.pageIndex))
        put("continuation", snapshot.continuation?.let(::JsonPrimitive) ?: JsonNull)
        put("complete", JsonPrimitive(snapshot.complete))
    }.toString()

    /**
     * Validate a resolve terminal through the same typed root/media decoder as
     * ConflictSnapshot. H06 deliberately does not apply this projection locally:
     * deleted state is authoritative only once the subsequent pull arrives.
     */
    fun validateAcceptedProjection(
        entityType: ConflictRootType,
        stableRootJson: String,
        stableMedia: List<CausalMediaItem>,
    ): ConflictRoot {
        val rootObject = runCatching { json.parseToJsonElement(stableRootJson) }
            .getOrElse { throw IllegalArgumentException("resolve accepted stable_root 不是 JSON", it) }
            as? JsonObject
            ?: throw IllegalArgumentException("resolve accepted stable_root 不是对象")
        val root = rootObject.toRoot(entityType, "resolve accepted.stable_root")
        validateCanonicalMedia(
            entityType = entityType,
            root = root,
            media = stableMedia,
            context = "resolve accepted.stable_media",
        )
        return root
    }
}

private fun ConflictVersionSnapshot.toJson(): JsonObject = buildJsonObject {
    put("version_id", JsonPrimitive(versionId))
    put("base_version", baseVersion?.let(::JsonPrimitive) ?: JsonNull)
    put("root", root.canonical)
    put("media", buildJsonArray { media.forEach { add(it.toSnapshotJson()) } })
    put("deleted", JsonPrimitive(deleted))
    put("mutation_id", JsonPrimitive(mutationId))
    put("actor_id", JsonPrimitive(actorId))
    put("device_id", JsonPrimitive(deviceId))
    put("received_at", JsonPrimitive(receivedAt))
}

private fun CausalMediaItem.toSnapshotJson(): JsonObject = buildJsonObject {
    put("media_uuid", JsonPrimitive(mediaUuid))
    put("role", JsonPrimitive(role))
    put("sha256", JsonPrimitive(sha256))
    put("byte_size", JsonPrimitive(byteSize))
    put("mime", JsonPrimitive(mime))
    put("width", width?.let(::JsonPrimitive) ?: JsonNull)
    put("height", height?.let(::JsonPrimitive) ?: JsonNull)
}

private fun ConflictOutcome.toJson(): JsonObject = when (this) {
    is ConflictOutcome.Set -> buildJsonObject {
        put("op", JsonPrimitive("set"))
        put("value", value)
    }
    ConflictOutcome.Remove -> buildJsonObject { put("op", JsonPrimitive("remove")) }
}

private fun ConflictSource.toJson(): JsonObject = buildJsonObject {
    put("version_id", JsonPrimitive(versionId))
    put("mutation_id", JsonPrimitive(mutationId))
    put("actor_id", JsonPrimitive(actorId))
    put("device_id", JsonPrimitive(deviceId))
    put("received_at", JsonPrimitive(receivedAt))
}

private fun JsonObject.toVersion(
    entityType: ConflictRootType,
    context: String,
): ConflictVersionSnapshot {
    requireClosedKeys(
        context,
        "version_id",
        "base_version",
        "root",
        "media",
        "deleted",
        "mutation_id",
        "actor_id",
        "device_id",
        "received_at",
    )
    val root = requiredObject("root", context).toRoot(entityType, "$context.root")
    val media = requiredArray("media", context).mapIndexed { index, raw ->
        (raw as? JsonObject)?.toSnapshotMedia("$context.media[$index]")
            ?: throw IllegalArgumentException("$context.media[$index] 不是对象")
    }
    validateCanonicalMedia(entityType, root, media, "$context.media")
    return ConflictVersionSnapshot(
        versionId = ConflictSnapshotValidation.requireBounded(
            requiredString("version_id", context), 1, 128, "$context.version_id",
        ),
        baseVersion = optionalString("base_version", context)?.let {
            ConflictSnapshotValidation.requireBounded(it, 1, 128, "$context.base_version")
        },
        root = root,
        media = media,
        deleted = requiredBoolean("deleted", context),
        mutationId = ConflictSnapshotValidation.requireUuid(
            requiredString("mutation_id", context), "$context.mutation_id",
        ),
        actorId = ConflictSnapshotValidation.requireBounded(
            requiredString("actor_id", context), 1, 64, "$context.actor_id",
        ),
        deviceId = ConflictSnapshotValidation.requireBounded(
            requiredString("device_id", context), 1, 128, "$context.device_id",
        ),
        receivedAt = ConflictSnapshotValidation.requireNonNegative(
            requiredLong("received_at", context), "$context.received_at",
        ),
    )
}

private fun validateCanonicalMedia(
    entityType: ConflictRootType,
    root: ConflictRoot,
    media: List<CausalMediaItem>,
    context: String,
) {
    require(media.map { it.mediaUuid } == media.map { it.mediaUuid }.sorted()) {
        "$context 必须按 media_uuid 排序"
    }
    require(media.map { it.mediaUuid }.distinct().size == media.size) {
        "$context 包含重复 media_uuid"
    }
    require(media.size <= entityType.mediaLimit) { "$context 超出领域上限" }
    media.forEachIndexed { index, item ->
        val itemContext = "$context[$index]"
        ConflictSnapshotValidation.requireUuid(item.mediaUuid, "$itemContext.media_uuid")
        require(item.role == entityType.mediaRole) { "$itemContext.role 无效" }
        require(LOWER_SHA.matches(item.sha256)) { "$itemContext.sha256 无效" }
        require(item.byteSize > 0) { "$itemContext.byte_size 无效" }
        ConflictSnapshotValidation.requireBounded(item.mime, 1, 255, "$itemContext.mime")
        item.width?.also {
            require(it in 1..Int.MAX_VALUE.toLong()) { "$itemContext.width 无效" }
        }
        item.height?.also {
            require(it in 1..Int.MAX_VALUE.toLong()) { "$itemContext.height 无效" }
        }
    }
    if (root is ConflictRoot.Baby) {
        require(root.avatarMediaUuid == null || media.any { it.mediaUuid == root.avatarMediaUuid }) {
            "$context 未包含 stable_root.avatar_media_uuid"
        }
    }
}

private fun JsonObject.toRoot(type: ConflictRootType, context: String): ConflictRoot = when (type) {
    ConflictRootType.Baby -> {
        requireClosedKeys(
            context,
            "nickname",
            "sex",
            "birthday",
            "birth_weight_grams",
            "avatar_media_uuid",
            "updated_at",
            "created_by_membership_id",
        )
        val nickname = ConflictSnapshotValidation.requireBabyNickname(
            requiredString("nickname", context), "$context.nickname",
        )
        val sex = nullableString("sex", context).also {
            require(it == null || it == "female" || it == "male") { "$context.sex 无效" }
        }
        val birthWeightGrams = nullableLong("birth_weight_grams", context)?.also {
            require(it in 0..100_000) { "$context.birth_weight_grams 无效" }
        }?.toInt()
        ConflictRoot.Baby(
            nickname = nickname,
            sex = sex,
            birthday = nullableString("birthday", context)?.let {
                ConflictSnapshotValidation.requireDate(it, "$context.birthday")
            },
            birthWeightGrams = birthWeightGrams,
            avatarMediaUuid = nullableString("avatar_media_uuid", context)?.let {
                ConflictSnapshotValidation.requireUuid(it, "$context.avatar_media_uuid")
            },
            updatedAt = nonNegativeLong("updated_at", context),
            createdByMembershipId = boundedMembership("created_by_membership_id", context),
            canonical = this,
        )
    }
    ConflictRootType.Record -> {
        val recordType = requiredString("type", context)
        val required = mutableSetOf(
            "baby_client_uuid",
            "type",
            "custom_item_client_uuid",
            "timestamp",
            "note",
            "payload_json",
            "schema_version",
            "updated_at",
            "created_by_membership_id",
        )
        if (recordType == "sleep") {
            required += "effective_wake_observation_client_uuid"
        } else {
            required += "end_timestamp"
        }
        requireClosedKeys(context, *required.toTypedArray())
        val schemaVersion = requiredInt("schema_version", context)
        require(schemaVersion == 2) { "$context.schema_version 必须为 2" }
        val customItem = nullableString("custom_item_client_uuid", context)
        require((recordType == "custom") == (customItem != null)) {
            "$context.custom_item_client_uuid 与 type 不一致"
        }
        ConflictRoot.Record(
            babyClientUuid = uuidString("baby_client_uuid", context),
            type = recordType,
            customItemClientUuid = customItem?.let {
                ConflictSnapshotValidation.requireUuid(it, "$context.custom_item_client_uuid")
            },
            timestamp = nonNegativeLong("timestamp", context),
            endTimestamp = if (recordType == "sleep") null else nullableLong("end_timestamp", context)
                ?.also { require(it >= requiredLong("timestamp", context)) { "$context.end_timestamp 无效" } },
            note = boundedNote(context),
            payload = SyncWireMapper.requireCurrentTransportPayload(
                type = SyncWireMapper.requireCurrentRecordType(recordType, "$context.type"),
                payload = requiredObject("payload_json", context),
            ),
            schemaVersion = schemaVersion,
            effectiveWakeObservationClientUuid = if (recordType == "sleep") {
                nullableString("effective_wake_observation_client_uuid", context)?.let {
                    ConflictSnapshotValidation.requireUuid(
                        it, "$context.effective_wake_observation_client_uuid",
                    )
                }
            } else {
                null
            },
            updatedAt = nonNegativeLong("updated_at", context),
            createdByMembershipId = boundedMembership("created_by_membership_id", context),
            canonical = this,
        )
    }
    ConflictRootType.CarePlan -> {
        requireClosedKeys(
            context,
            "baby_client_uuid",
            "type",
            "scheduled_at",
            "scheduled_zone_id",
            "note",
            "payload_json",
            "schema_version",
            "status",
            "fulfilled_record_client_uuid",
            "fulfilled_at",
            "source_record_client_uuid",
            "custom_item_client_uuid",
            "updated_at",
            "created_by_membership_id",
        )
        val schemaVersion = requiredInt("schema_version", context)
        require(schemaVersion == 2) { "$context.schema_version 必须为 2" }
        val status = requiredString("status", context)
        require(status in CARE_PLAN_STATUSES) { "$context.status 无效" }
        val fulfilledRecord = nullableString("fulfilled_record_client_uuid", context)
        val fulfilledAt = nullableLong("fulfilled_at", context)
        require((fulfilledRecord == null) == (fulfilledAt == null)) {
            "$context fulfilled pair 不完整"
        }
        require((status == "completed") == (fulfilledRecord != null)) {
            "$context status 与 fulfilled pair 不一致"
        }
        val recordType = SyncWireMapper.requireCurrentRecordType(
            requiredString("type", context),
            "$context.type",
        )
        val customItem = nullableString("custom_item_client_uuid", context)
        require((recordType == RecordType.CUSTOM) == (customItem != null)) {
            "$context.custom_item_client_uuid 与 type 不一致"
        }
        val note = boundedNote(context)
        ConflictRoot.CarePlan(
            babyClientUuid = uuidString("baby_client_uuid", context),
            type = recordType.key,
            scheduledAt = nonNegativeLong("scheduled_at", context),
            scheduledZoneId = ConflictSnapshotValidation.requireZone(
                requiredString("scheduled_zone_id", context), "$context.scheduled_zone_id",
            ),
            note = note,
            payload = SyncWireMapper.requireCurrentTransportPayload(
                type = recordType,
                payload = requiredObject("payload_json", context),
                allowIntentOnlyFeed = isNextFeedPlanNote(note),
            ),
            schemaVersion = schemaVersion,
            status = status,
            fulfilledRecordClientUuid = fulfilledRecord?.let {
                ConflictSnapshotValidation.requireUuid(it, "$context.fulfilled_record_client_uuid")
            },
            fulfilledAt = fulfilledAt?.also { require(it >= 0) { "$context.fulfilled_at 无效" } },
            sourceRecordClientUuid = nullableString("source_record_client_uuid", context)?.let {
                ConflictSnapshotValidation.requireUuid(it, "$context.source_record_client_uuid")
            },
            customItemClientUuid = customItem?.let {
                ConflictSnapshotValidation.requireUuid(it, "$context.custom_item_client_uuid")
            },
            updatedAt = nonNegativeLong("updated_at", context),
            createdByMembershipId = boundedMembership("created_by_membership_id", context),
            canonical = this,
        )
    }
    ConflictRootType.CustomItem -> {
        requireClosedKeys(context, "name", "icon_slot", "updated_at", "created_by_membership_id")
        ConflictRoot.CustomItem(
            name = ConflictSnapshotValidation.requireTrimmed(
                requiredString("name", context), 40, "$context.name",
            ),
            iconSlot = requiredInt("icon_slot", context).also {
                require(it in 0..7) { "$context.icon_slot 无效" }
            },
            updatedAt = nonNegativeLong("updated_at", context),
            createdByMembershipId = boundedMembership("created_by_membership_id", context),
            canonical = this,
        )
    }
    ConflictRootType.WakeObservation -> {
        requireClosedKeys(
            context,
            "sleep_record_client_uuid",
            "wake_timestamp",
            "note",
            "withdrawn",
            "updated_at",
            "observer_membership_id",
        )
        ConflictRoot.WakeObservation(
            sleepRecordClientUuid = uuidString("sleep_record_client_uuid", context),
            wakeTimestamp = nonNegativeLong("wake_timestamp", context),
            note = boundedNote(context),
            withdrawn = requiredBoolean("withdrawn", context),
            updatedAt = nonNegativeLong("updated_at", context),
            observerMembershipId = boundedMembership("observer_membership_id", context),
            canonical = this,
        )
    }
}

private fun JsonObject.toSnapshotMedia(context: String): CausalMediaItem {
    requireClosedKeys(
        context,
        "media_uuid",
        "role",
        "sha256",
        "byte_size",
        "mime",
        "width",
        "height",
    )
    val sha = requiredString("sha256", context)
    require(LOWER_SHA.matches(sha)) { "$context.sha256 无效" }
    return CausalMediaItem(
        mediaUuid = ConflictSnapshotValidation.requireUuid(
            requiredString("media_uuid", context), "$context.media_uuid",
        ),
        role = requiredString("role", context),
        sha256 = sha,
        byteSize = requiredLong("byte_size", context).also { require(it > 0) },
        mime = ConflictSnapshotValidation.requireBounded(
            requiredString("mime", context), 1, 255, "$context.mime",
        ),
        width = nullableLong("width", context)?.also {
            require(it in 1..Int.MAX_VALUE.toLong()) { "$context.width 无效" }
        },
        height = nullableLong("height", context)?.also {
            require(it in 1..Int.MAX_VALUE.toLong()) { "$context.height 无效" }
        },
    )
}

private fun JsonObject.toConflictingPath(context: String): ConflictingPath {
    requireClosedKeys(context, "path", "candidates")
    val candidates = requiredArray("candidates", context).mapIndexed { index, raw ->
        val value = raw as? JsonObject
            ?: throw IllegalArgumentException("$context.candidates[$index] 不是对象")
        value.requireClosedKeys("$context.candidates[$index]", "choice_id", "outcome", "sources")
        ConflictCandidate(
            choiceId = ConflictSnapshotValidation.requireChoiceId(
                value.requiredString("choice_id", "$context.candidates[$index]"),
                "$context.candidates[$index].choice_id",
            ),
            outcome = value.requiredObject("outcome", "$context.candidates[$index]")
                .toOutcome("$context.candidates[$index].outcome"),
            sources = value.requiredSources("$context.candidates[$index]"),
        )
    }
    require(candidates.size in 2..ConflictSnapshotValidation.MAX_CANDIDATES_PER_PATH) {
        "$context.candidates 数量无效"
    }
    require(candidates.map { it.choiceId }.distinct().size == candidates.size) {
        "$context choice_id 重复"
    }
    require(candidates.map { it.outcome }.distinct().size >= 2) {
        "$context.candidates 必须包含至少两个不同 outcome"
    }
    return ConflictingPath(path = requiredPath("path", context), candidates = candidates)
}

private fun JsonObject.toAutoMergedPath(context: String): AutoMergedPath {
    requireClosedKeys(context, "path", "outcome", "sources")
    return AutoMergedPath(
        path = requiredPath("path", context),
        outcome = requiredObject("outcome", context).toOutcome("$context.outcome"),
        sources = requiredSources(context),
    )
}

private fun JsonObject.requiredSources(context: String): List<ConflictSource> {
    val values = requiredArray("sources", context).mapIndexed { index, raw ->
        val source = raw as? JsonObject
            ?: throw IllegalArgumentException("$context.sources[$index] 不是对象")
        val itemContext = "$context.sources[$index]"
        source.requireClosedKeys(
            itemContext,
            "version_id",
            "mutation_id",
            "actor_id",
            "device_id",
            "received_at",
        )
        ConflictSource(
            versionId = ConflictSnapshotValidation.requireBounded(
                source.requiredString("version_id", itemContext), 1, 128, "$itemContext.version_id",
            ),
            mutationId = ConflictSnapshotValidation.requireUuid(
                source.requiredString("mutation_id", itemContext), "$itemContext.mutation_id",
            ),
            actorId = ConflictSnapshotValidation.requireBounded(
                source.requiredString("actor_id", itemContext), 1, 64, "$itemContext.actor_id",
            ),
            deviceId = ConflictSnapshotValidation.requireBounded(
                source.requiredString("device_id", itemContext), 1, 128, "$itemContext.device_id",
            ),
            receivedAt = ConflictSnapshotValidation.requireNonNegative(
                source.requiredLong("received_at", itemContext), "$itemContext.received_at",
            ),
        )
    }
    require(values.size in 1..ConflictSnapshotValidation.MAX_SOURCES_PER_OUTCOME) {
        "$context.sources 数量无效"
    }
    require(values.distinct().size == values.size) { "$context.sources 重复" }
    return values
}

private fun JsonObject.toOutcome(context: String): ConflictOutcome {
    val op = requiredString("op", context)
    return when (op) {
        "set" -> {
            requireClosedKeys(context, "op", "value")
            ConflictOutcome.Set(getValue("value"))
        }
        "remove" -> {
            requireClosedKeys(context, "op")
            ConflictOutcome.Remove
        }
        else -> throw IllegalArgumentException("$context.op 无效")
    }
}

private fun JsonObject.requireClosedKeys(context: String, vararg required: String) {
    val expected = required.toSet()
    val unknown = keys - expected
    val missing = expected - keys
    require(unknown.isEmpty()) { "$context 存在未知字段: ${unknown.sorted()}" }
    require(missing.isEmpty()) { "$context 缺少字段: ${missing.sorted()}" }
}

private fun JsonObject.requiredObject(key: String, context: String): JsonObject =
    get(key) as? JsonObject ?: throw IllegalArgumentException("$context.$key 不是对象")

private fun JsonObject.requiredArray(key: String, context: String): JsonArray =
    get(key) as? JsonArray ?: throw IllegalArgumentException("$context.$key 不是数组")

private fun JsonObject.requiredString(key: String, context: String): String =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }
        ?.contentOrNull?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("$context.$key 不是非空字符串")

private fun JsonObject.optionalString(key: String, context: String): String? = when (val raw = get(key)) {
    JsonNull -> null
    is JsonPrimitive -> raw.takeIf(JsonPrimitive::isString)?.contentOrNull
        ?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("$context.$key 不是非空字符串或 null")
    else -> throw IllegalArgumentException("$context.$key 不是字符串或 null")
}

private fun JsonObject.nullableString(key: String, context: String): String? =
    optionalString(key, context)

private fun JsonObject.requiredLong(key: String, context: String): Long =
    (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        ?: throw IllegalArgumentException("$context.$key 不是整数")

private fun JsonObject.nullableLong(key: String, context: String): Long? = when (val raw = get(key)) {
    JsonNull -> null
    is JsonPrimitive -> raw.takeUnless { it.isString }?.longOrNull
        ?: throw IllegalArgumentException("$context.$key 不是整数或 null")
    else -> throw IllegalArgumentException("$context.$key 不是整数或 null")
}

private fun JsonObject.requiredInt(key: String, context: String): Int =
    (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        ?: throw IllegalArgumentException("$context.$key 不是整数")

private fun JsonObject.requiredBoolean(key: String, context: String): Boolean =
    (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
        ?: throw IllegalArgumentException("$context.$key 不是布尔值")

private fun JsonObject.requiredPath(key: String, context: String): String =
    ConflictSnapshotValidation.requirePointer(requiredString(key, context), "$context.$key")

private fun JsonObject.uuidString(key: String, context: String): String =
    ConflictSnapshotValidation.requireUuid(requiredString(key, context), "$context.$key")

private fun JsonObject.nonNegativeLong(key: String, context: String): Long =
    ConflictSnapshotValidation.requireNonNegative(requiredLong(key, context), "$context.$key")

private fun JsonObject.boundedMembership(key: String, context: String): String =
    ConflictSnapshotValidation.requireBounded(requiredString(key, context), 1, 64, "$context.$key")

private fun JsonObject.boundedNote(context: String): String? = nullableString("note", context)?.also {
    require(it.length <= ConflictSnapshotValidation.MAX_NOTE_CHARS) { "$context.note 过长" }
}

private val LOWER_SHA = Regex("^[0-9a-f]{64}$")
private val CARE_PLAN_STATUSES = setOf("pending", "missed", "completed", "skipped")
