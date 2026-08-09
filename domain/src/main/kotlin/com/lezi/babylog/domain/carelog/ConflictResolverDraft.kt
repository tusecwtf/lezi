package com.lezi.babylog.domain.carelog

import com.lezi.babylog.sync.backend.CausalMediaItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class ConflictResolverOption(
    val sourceLabel: String,
    val value: JsonElement,
)

data class ConflictResolverPath(
    val path: String,
    val label: String,
    val media: Boolean,
    val options: List<ConflictResolverOption>,
)

/**
 * UI draft for one conflict. Choices stay independent from refreshed CAS identities,
 * so a mismatch can replace [detail] while preserving values the user already chose.
 */
data class ConflictResolverDraft(
    val detail: ConflictResolverDetail,
    val paths: List<ConflictResolverPath>,
    val conflictChoices: Map<String, JsonElement>,
) {
    fun choose(path: String, value: JsonElement): ConflictResolverDraft {
        require(paths.any { it.path == path }) { "不是可解决的冲突字段: $path" }
        return copy(conflictChoices = conflictChoices + (path to value))
    }

    val complete: Boolean get() = paths.all { it.path in conflictChoices }

    val resolvedRootJson: String
        get() {
            var root = JSON.parseToJsonElement(detail.stableRootJson).jsonObject
            val autoMerged = runCatching {
                JSON.parseToJsonElement(detail.autoMergedJson).jsonObject
            }.getOrDefault(JsonObject(emptyMap()))
            for ((path, value) in autoMerged) {
                if (!isMediaConflictPath(path)) root = root.withPointer(path, value)
            }
            for ((path, value) in conflictChoices) {
                if (!isMediaConflictPath(path)) root = root.withPointer(path, value)
            }
            return root.toString()
        }

    val resolvedMedia: List<CausalMediaItem>
        get() {
            val choicesByUuid = conflictChoices
                .filterKeys(::isMediaConflictPath)
                .mapKeys { (path, _) -> path.removePrefix("/media/") }
            val stableByUuid = detail.stableMedia.associateBy(CausalMediaItem::mediaUuid)
            val all = linkedMapOf<String, CausalMediaItem>()
            stableByUuid.forEach { (uuid, media) ->
                if (uuid !in choicesByUuid) all[uuid] = media
            }
            choicesByUuid.forEach { (uuid, value) ->
                if (value !is JsonNull) value.toCausalMediaItem(uuid)?.let { all[uuid] = it }
            }
            return all.values.sortedBy(CausalMediaItem::mediaUuid)
        }

    fun refresh(refreshed: ConflictResolverDetail): ConflictResolverDraft {
        val next = from(refreshed)
        val retained = conflictChoices.filter { (path, value) ->
            next.paths.firstOrNull { it.path == path }?.options?.any { it.value == value } == true
        }
        return next.copy(conflictChoices = next.conflictChoices + retained)
    }

    companion object {
        fun from(detail: ConflictResolverDetail): ConflictResolverDraft {
            val stableRoot = JSON.parseToJsonElement(detail.stableRootJson).jsonObject
            val branches = runCatching {
                JSON.parseToJsonElement(detail.branchesJson).jsonArray.map { it.jsonObject }
            }.getOrDefault(emptyList())
            val autoMergedPaths = runCatching {
                JSON.parseToJsonElement(detail.autoMergedJson).jsonObject.keys
            }.getOrDefault(emptySet())
            val paths = conflictResolverSelectablePaths(
                detail.conflictingPaths,
                autoMergedPaths,
            ).map { path ->
                val mediaPath = isMediaConflictPath(path)
                val stableValue = if (mediaPath) {
                    val uuid = path.removePrefix("/media/")
                    detail.stableMedia.firstOrNull { it.mediaUuid == uuid }
                        ?.toJsonElement() ?: JsonNull
                } else {
                    stableRoot.atPointer(path) ?: JsonNull
                }
                val options = buildList {
                    add(ConflictResolverOption("当前稳定版", stableValue))
                    branches.forEachIndexed { index, branch ->
                        val value = if (mediaPath) {
                            val uuid = path.removePrefix("/media/")
                            branch["media"]?.jsonArray
                                ?.mapNotNull { it.toCausalMediaItem() }
                                ?.firstOrNull { it.mediaUuid == uuid }
                                ?.toJsonElement() ?: JsonNull
                        } else {
                            branch["root"]?.jsonObject?.atPointer(path) ?: JsonNull
                        }
                        add(ConflictResolverOption("分支 ${index + 1}", value))
                    }
                }.distinctBy(ConflictResolverOption::value)
                ConflictResolverPath(
                    path = path,
                    label = conflictPathLabel(path),
                    media = mediaPath,
                    options = options,
                )
            }
            val defaults = paths.associate { it.path to it.options.first().value }
            return ConflictResolverDraft(detail, paths, defaults)
        }
    }
}

fun CausalMediaItem.toJsonElement(): JsonObject = buildJsonObject {
    put("media_uuid", mediaUuid)
    put("role", role)
    put("mime", mime)
    put("sha256", sha256)
    put("byte_size", byteSize)
    width?.let { put("width", it) }
    height?.let { put("height", it) }
}

private fun JsonElement.toCausalMediaItem(fallbackUuid: String? = null): CausalMediaItem? {
    val obj = this as? JsonObject ?: return null
    val uuid = obj["media_uuid"]?.jsonPrimitive?.contentOrNull ?: fallbackUuid ?: return null
    return CausalMediaItem(
        mediaUuid = uuid,
        role = obj["role"]?.jsonPrimitive?.contentOrNull ?: return null,
        mime = obj["mime"]?.jsonPrimitive?.contentOrNull ?: return null,
        sha256 = obj["sha256"]?.jsonPrimitive?.contentOrNull ?: return null,
        byteSize = obj["byte_size"]?.jsonPrimitive?.longOrNull ?: return null,
        width = obj["width"]?.jsonPrimitive?.longOrNull,
        height = obj["height"]?.jsonPrimitive?.longOrNull,
    )
}

private fun conflictPathLabel(path: String): String = when {
    path == "/note" -> "备注"
    path == "/timestamp" -> "发生时间"
    path == "/wake_timestamp" -> "醒来时间"
    path == "/withdrawn" -> "撤回状态"
    path.startsWith("/media/") -> "照片 ${path.removePrefix("/media/").take(8)}"
    path.startsWith("/payload_json/") -> path.substringAfterLast('/').replace('_', ' ')
    else -> path.substringAfterLast('/').replace('_', ' ')
}

private fun JsonObject.atPointer(path: String): JsonElement? {
    if (path.isBlank() || path == "/") return this
    var current: JsonElement = this
    for (segment in path.pointerSegments()) {
        current = (current as? JsonObject)?.get(segment) ?: return null
    }
    return current
}

private fun JsonObject.withPointer(path: String, value: JsonElement): JsonObject {
    val segments = path.pointerSegments()
    require(segments.isNotEmpty()) { "root pointer cannot be replaced" }
    fun replace(obj: JsonObject, index: Int): JsonObject {
        val key = segments[index]
        val next = obj.toMutableMap()
        next[key] = if (index == segments.lastIndex) {
            value
        } else {
            replace((obj[key] as? JsonObject) ?: JsonObject(emptyMap()), index + 1)
        }
        return JsonObject(next)
    }
    return replace(this, 0)
}

private fun String.pointerSegments(): List<String> =
    removePrefix("/").split('/').filter(String::isNotEmpty).map {
        it.replace("~1", "/").replace("~0", "~")
    }

private val JSON = Json { ignoreUnknownKeys = true }
