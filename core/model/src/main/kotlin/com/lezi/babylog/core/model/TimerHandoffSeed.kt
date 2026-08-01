package com.lezi.babylog.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Explicit Composer→Timer draft ownership handoff.
 *
 * This is a durable ownership transfer of transferable fields (baby, care plan,
 * note, amount, ordered photos with ownership), not an implicit abandon of the
 * Composer draft. Timer must accept the seed before Composer releases ownership.
 */
data class TimerHandoffSeed(
    /** Stable id for idempotent accept across config/process restore. */
    val handoffId: String,
    val babyId: Long,
    val carePlanId: Long? = null,
    val note: String = "",
    val amountMl: String = "",
    /** Ordered photos visible at handoff; ownership is per path. */
    val photos: List<TimerHandoffPhoto> = emptyList(),
) {
    init {
        require(handoffId.isNotBlank()) { "handoffId must not be blank" }
        require(babyId > 0L) { "babyId must be positive" }
        carePlanId?.let { require(it > 0L) { "carePlanId must be positive when set" } }
        require(photos.size <= MAX_RECORD_PHOTOS) {
            "handoff photos must be 0–$MAX_RECORD_PHOTOS"
        }
        require(photos.map { it.path }.distinct().size == photos.size) {
            "handoff photo paths must be unique"
        }
    }

    val orderedPaths: List<String> get() = photos.map { it.path }

    val composerOwnedPaths: List<String>
        get() = photos.filter { it.ownership == TimerHandoffPhotoOwnership.ComposerOwned }
            .map { it.path }

    val borrowedPaths: List<String>
        get() = photos.filter { it.ownership == TimerHandoffPhotoOwnership.Borrowed }
            .map { it.path }

    fun toJson(): String = buildJsonObject {
        put("handoffId", handoffId)
        put("babyId", babyId)
        if (carePlanId == null) {
            put("carePlanId", JsonNull)
        } else {
            put("carePlanId", carePlanId)
        }
        put("note", note)
        put("amountMl", amountMl)
        put(
            "photos",
            buildJsonArray {
                photos.forEach { photo ->
                    add(
                        buildJsonObject {
                            put("path", photo.path)
                            put("ownership", photo.ownership.wire)
                        },
                    )
                }
            },
        )
    }.toString()

    companion object {
        fun fromJson(raw: String?): TimerHandoffSeed? {
            if (raw.isNullOrBlank()) return null
            return runCatching {
                fromJsonObject(Json.parseToJsonElement(raw).jsonObject)
            }.getOrNull()
        }

        fun fromJsonObject(o: JsonObject): TimerHandoffSeed {
            val handoffId = o.requiredString("handoffId").also {
                require(it.isNotBlank())
            }
            val babyId = o.requiredLong("babyId").also { require(it > 0L) }
            val carePlanId = o.optionalNullableLong("carePlanId")?.also {
                require(it > 0L)
            }
            val note = o.optionalString("note").orEmpty().take(200)
            val amountMl = o.optionalString("amountMl").orEmpty()
                .filter(Char::isDigit)
                .take(3)
            val photos = o.optionalPhotoArray("photos")
            return TimerHandoffSeed(
                handoffId = handoffId,
                babyId = babyId,
                carePlanId = carePlanId,
                note = note,
                amountMl = amountMl,
                photos = photos,
            )
        }
    }
}

data class TimerHandoffPhoto(
    val path: String,
    val ownership: TimerHandoffPhotoOwnership,
) {
    init {
        require(path.isNotBlank()) { "photo path must not be blank" }
    }
}

enum class TimerHandoffPhotoOwnership(val wire: String) {
    /** CarePlan (or other entity) owns the bytes; discard/handoff never physical-deletes. */
    Borrowed("borrowed"),

    /** Composer-imported private file; Timer reclaims on discard, Record owns after complete. */
    ComposerOwned("composer_owned"),
    ;

    companion object {
        fun fromWire(raw: String): TimerHandoffPhotoOwnership? =
            entries.firstOrNull { it.wire == raw }
    }
}

/** Fields entered in Composer that Timer cannot carry; require explicit confirm. */
enum class UntransferableTimerField {
    ManualDuration,
    ManualOrder,
    ManualTime,
}

sealed interface TimerHandoffBuildResult {
    data class Ready(val seed: TimerHandoffSeed) : TimerHandoffBuildResult

    /**
     * Distinct seed + live plan photos would exceed [MAX_RECORD_PHOTOS].
     * User must trim Composer photos before leaving.
     */
    data class PhotoOverflow(
        val distinctCount: Int,
        val maxAllowed: Int = MAX_RECORD_PHOTOS,
    ) : TimerHandoffBuildResult
}

/**
 * Build a handoff seed from ordered Composer paths and ownership bookkeeping.
 *
 * [borrowedPaths] win over [ownedPaths] when a path appears in both (fail closed
 * to CarePlan ownership — never delete plan bytes via Timer discard).
 * [livePlanPhotoPaths] is the plan's current active media (Ticket 08) used only
 * for pre-leave overflow detection; seed photo list stays the Composer view.
 */
fun buildTimerHandoffSeed(
    handoffId: String,
    babyId: Long,
    carePlanId: Long?,
    note: String,
    amountMl: String,
    orderedPhotoPaths: List<String>,
    borrowedPaths: Collection<String>,
    ownedPaths: Collection<String>,
    livePlanPhotoPaths: List<String> = emptyList(),
): TimerHandoffBuildResult {
    val borrowed = borrowedPaths.map(String::trim).filter(String::isNotEmpty).toSet()
    val owned = ownedPaths.map(String::trim).filter(String::isNotEmpty).toSet()
    val ordered = orderedPhotoPaths
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
    if (ordered.size > MAX_RECORD_PHOTOS) {
        return TimerHandoffBuildResult.PhotoOverflow(distinctCount = ordered.size)
    }
    val overflowProbe = mergeTimerCompletionPhotos(
        seedPhotoPaths = ordered,
        livePlanPhotoPaths = livePlanPhotoPaths,
    )
    val distinctForComplete = (
        ordered + livePlanPhotoPaths.map(String::trim).filter(String::isNotEmpty)
        ).distinct()
    if (distinctForComplete.size > MAX_RECORD_PHOTOS) {
        return TimerHandoffBuildResult.PhotoOverflow(distinctCount = distinctForComplete.size)
    }
    // overflowProbe always ≤ max; keep for call-site clarity / future strictness
    check(overflowProbe.size <= MAX_RECORD_PHOTOS)

    val photos = ordered.map { path ->
        val ownership = when {
            path in borrowed -> TimerHandoffPhotoOwnership.Borrowed
            path in owned -> TimerHandoffPhotoOwnership.ComposerOwned
            // Visible but unknown bookkeeping: treat as borrowed (never physical-delete).
            else -> TimerHandoffPhotoOwnership.Borrowed
        }
        TimerHandoffPhoto(path = path, ownership = ownership)
    }
    return TimerHandoffBuildResult.Ready(
        TimerHandoffSeed(
            handoffId = handoffId,
            babyId = babyId,
            carePlanId = carePlanId,
            note = note.take(200),
            amountMl = amountMl.filter(Char::isDigit).take(3),
            photos = photos,
        ),
    )
}

/**
 * Merge seed photos with Ticket 08 live plan media: seed order first, then any
 * plan-only paths, distinct, capped at [MAX_RECORD_PHOTOS].
 */
fun mergeTimerCompletionPhotos(
    seedPhotoPaths: List<String>,
    livePlanPhotoPaths: List<String>,
): List<String> {
    val result = ArrayList<String>(MAX_RECORD_PHOTOS)
    fun addAll(paths: List<String>) {
        for (raw in paths) {
            if (result.size >= MAX_RECORD_PHOTOS) return
            val path = raw.trim()
            if (path.isEmpty() || path in result) continue
            result += path
        }
    }
    addAll(seedPhotoPaths)
    addAll(livePlanPhotoPaths)
    return result
}

/**
 * Detect Composer nursing fields that Timer will not carry.
 *
 * - Manual duration: any positive left/right minutes (including plan prefill).
 * - Manual order: order string differs from the open baseline.
 * - Manual time: timestamp differs from the open baseline.
 */
fun untransferableTimerFields(
    leftMinutes: String,
    rightMinutes: String,
    order: String,
    timestamp: Long,
    baselineOrder: String,
    baselineTimestamp: Long,
): Set<UntransferableTimerField> {
    val fields = linkedSetOf<UntransferableTimerField>()
    val left = leftMinutes.toIntOrNull() ?: 0
    val right = rightMinutes.toIntOrNull() ?: 0
    if (left > 0 || right > 0) {
        fields += UntransferableTimerField.ManualDuration
    }
    if (order != baselineOrder) {
        fields += UntransferableTimerField.ManualOrder
    }
    if (timestamp != baselineTimestamp) {
        fields += UntransferableTimerField.ManualTime
    }
    return fields
}

/** Owned-import paths Timer may reclaim on explicit discard (never borrowed). */
fun timerDiscardReclaimPaths(seed: TimerHandoffSeed?): List<String> =
    seed?.composerOwnedPaths.orEmpty()

sealed interface TimerHandoffAcceptResult {
    data class Accepted(val stateBabyId: Long, val stateCarePlanId: Long?) : TimerHandoffAcceptResult
    data object RejectedConflict : TimerHandoffAcceptResult
    data object AlreadyAccepted : TimerHandoffAcceptResult
}

/**
 * Pure accept decision for an idle or matching timer session.
 *
 * [hasSessionData] is true when the timer already has running/accumulated data
 * or another handoff/care-plan binding that is not this seed.
 */
fun decideTimerHandoffAccept(
    seed: TimerHandoffSeed,
    existingHandoffId: String?,
    boundCarePlanId: Long?,
    boundBabyId: Long?,
    hasSessionData: Boolean,
): TimerHandoffAcceptResult {
    if (existingHandoffId == seed.handoffId) {
        return TimerHandoffAcceptResult.AlreadyAccepted
    }
    if (existingHandoffId != null) {
        return TimerHandoffAcceptResult.RejectedConflict
    }
    if (hasSessionData) {
        return TimerHandoffAcceptResult.RejectedConflict
    }
    if (boundCarePlanId != null && boundCarePlanId != seed.carePlanId) {
        return TimerHandoffAcceptResult.RejectedConflict
    }
    if (boundBabyId != null && boundBabyId != seed.babyId) {
        return TimerHandoffAcceptResult.RejectedConflict
    }
    return TimerHandoffAcceptResult.Accepted(
        stateBabyId = seed.babyId,
        stateCarePlanId = seed.carePlanId,
    )
}

private fun JsonObject.requiredString(key: String): String {
    val value = get(key)?.jsonPrimitive
        ?: throw IllegalArgumentException("Missing or invalid $key")
    require(value.isString) { "Invalid $key" }
    return value.content
}

private fun JsonObject.requiredLong(key: String): Long =
    get(key)?.jsonPrimitive?.longOrNull
        ?: throw IllegalArgumentException("Missing or invalid $key")

private fun JsonObject.optionalString(key: String): String? {
    val value = get(key) ?: return null
    if (value === JsonNull) return null
    val primitive = value.jsonPrimitive
    require(primitive.isString) { "Invalid $key" }
    return primitive.contentOrNull
}

private fun JsonObject.optionalNullableLong(key: String): Long? {
    val value = get(key) ?: return null
    if (value === JsonNull) return null
    return value.jsonPrimitive.longOrNull
        ?: throw IllegalArgumentException("Invalid $key")
}

private fun JsonObject.optionalPhotoArray(key: String): List<TimerHandoffPhoto> {
    val value = get(key) ?: return emptyList()
    if (value === JsonNull) return emptyList()
    val array = value.jsonArray
    return array.map { element ->
        val obj = element.jsonObject
        val path = obj.requiredString("path").trim()
        require(path.isNotEmpty())
        val ownership = TimerHandoffPhotoOwnership.fromWire(
            obj.requiredString("ownership"),
        ) ?: throw IllegalArgumentException("Invalid ownership")
        TimerHandoffPhoto(path = path, ownership = ownership)
    }
}
