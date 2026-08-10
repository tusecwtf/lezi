package com.lezi.babylog.sync.conflict

import com.lezi.babylog.core.model.BABY_NICKNAME_MAX_CODE_POINTS
import com.lezi.babylog.core.model.babyNicknameLength
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * One validation owner for ConflictSnapshot receipts and choice-only commands.
 *
 * Snapshot parsing also accepts the frozen golden-corpus token shape. Runtime
 * resolution commands are deliberately narrower and accept only 43-byte
 * base64url receipt frames plus canonical UUID mutation identities.
 */
object ConflictSnapshotValidation {
    const val MAX_BRANCHES_PER_PAGE = 16
    const val MAX_RESOLUTION_PATHS = 64
    const val MAX_CANDIDATES_PER_PATH = 65
    const val MAX_SOURCES_PER_OUTCOME = 65
    const val MAX_PATH_BYTES = 1_024
    const val MAX_NOTE_CHARS = 20_000

    private val framedToken = Regex("^[A-Za-z0-9_-]{43}$")
    private val goldenToken = Regex("^[0-9a-f]{64}$")
    private val choiceId = Regex("^[A-Za-z0-9_-]{16,128}$")
    private val jsonPointer = Regex("^/(?:[^~/]|~0|~1)+(?:/(?:[^~/]|~0|~1)+)*$")

    fun requireUuid(value: String, context: String): String = value.also {
        require(runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false)) {
            "$context 不是 canonical UUID"
        }
    }

    fun requireToken(value: String, context: String): String = value.also {
        // Runtime receipts are 43-byte base64url frames; ADR-0022 golden uses hex-256.
        require(framedToken.matches(it) || goldenToken.matches(it)) { "$context 无效" }
    }

    fun requireRuntimeToken(value: String, context: String): String = value.also {
        require(framedToken.matches(it)) { "$context 不是 runtime receipt frame" }
    }

    fun requireChoiceId(value: String, context: String): String = value.also {
        require(choiceId.matches(it)) { "$context 无效" }
    }

    fun requirePointer(value: String, context: String): String = value.also {
        require(it.toByteArray().size <= MAX_PATH_BYTES && jsonPointer.matches(it)) {
            "$context 不是 canonical JSON Pointer"
        }
    }

    fun requireResolutionChoices(
        snapshotToken: String,
        resolutionMutationId: String,
        choices: List<Pair<String, String>>,
        context: String,
    ) {
        requireRuntimeToken(snapshotToken, "$context.snapshot_token")
        requireUuid(resolutionMutationId, "$context.resolution_mutation_id")
        require(choices.size in 1..MAX_RESOLUTION_PATHS) {
            "$context.choices 数量无效"
        }
        require(choices.map { it.first } == choices.map { it.first }.sorted()) {
            "$context.choices 必须按 path 排序"
        }
        require(choices.map { it.first }.distinct().size == choices.size) {
            "$context.choices path 重复"
        }
        choices.forEachIndexed { index, (path, choiceId) ->
            requirePointer(path, "$context.choices[$index].path")
            requireRuntimeToken(choiceId, "$context.choices[$index].choice_id")
        }
    }

    fun requireBounded(value: String, min: Int, max: Int, context: String): String = value.also {
        require(it.length in min..max) { "$context 长度无效" }
    }

    fun requireTrimmed(value: String, maxCodePoints: Int, context: String): String = value.also {
        require(it.isNotBlank() && it == it.trim() && it.codePointCount(0, it.length) <= maxCodePoints) {
            "$context 不是 canonical trimmed string"
        }
    }

    fun requireBabyNickname(value: String, context: String): String = value.also {
        require(it.isNotBlank() && it == it.trim()) { "$context 不是 canonical nickname" }
        require(babyNicknameLength(it) <= BABY_NICKNAME_MAX_CODE_POINTS) { "$context 过长" }
    }

    fun requireDate(value: String, context: String): String = value.also {
        require(runCatching { LocalDate.parse(it).toString() == it }.getOrDefault(false)) {
            "$context 不是 ISO 日期"
        }
    }

    fun requireZone(value: String, context: String): String = value.also {
        require(value.length <= 64 && runCatching { ZoneId.of(it).id == it }.getOrDefault(false)) {
            "$context 不是 current ZoneId"
        }
    }

    fun requireNonNegative(value: Long, context: String): Long = value.also {
        require(it >= 0) { "$context 不能为负数" }
    }
}
