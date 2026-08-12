package com.lezi.babylog.sync.engine

import com.lezi.babylog.core.model.limitBabyNicknameInput
import java.util.UUID
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Where the authoritative revision stamp lives for the same canonical root payload. */
internal enum class RootUpdatedAtLocation {
    /** Incremental pull carries updated_at in [com.lezi.babylog.sync.backend.SyncEntity]. */
    SeparateEnvelope,

    /** Causal terminal proof carries updated_at inside stable_root. */
    InlineStableRoot,
}

internal data class BabyWire(
    val nickname: String,
    val sex: String?,
    val birthdayEpochDay: Long,
    val birthWeightGrams: Int?,
    val avatarMediaUuid: String?,
    val inlineUpdatedAt: Long?,
)

internal data class CustomItemWire(
    val name: String,
    val iconSlot: Int,
    /** Server-stamped creator; canonical null is represented as the empty string. */
    val createdByMembershipId: String,
    val inlineUpdatedAt: Long?,
)

/** Single typed owner for Baby payloads consumed by both pull and causal commit proof. */
internal fun decodeBabyWire(
    payload: JsonObject,
    updatedAtLocation: RootUpdatedAtLocation = RootUpdatedAtLocation.SeparateEnvelope,
): BabyWire {
    // Server stamp_root always writes created_by_membership_id onto baby roots for both
    // causal stable proofs and ordinary pull projections. Treat it as a non-domain stamp.
    payload.requireProviderKeys(
        "nickname",
        "sex",
        "birthday",
        "birth_weight_grams",
        "avatar_media_uuid",
        context = "baby",
        updatedAtLocation = updatedAtLocation,
        extraAllowedKeys = setOf("created_by_membership_id"),
    )
    val nickname = payload.requireProviderNonBlankString("nickname", "baby").trim()
    require(limitBabyNicknameInput(nickname) == nickname) { "baby nickname 超出 current 限制" }
    val sex = payload.requireProviderNullableString("sex", "baby")
    require(sex == null || sex == "female" || sex == "male") { "baby sex 无效" }
    val birthWeight = payload.requireProviderNullableLong("birth_weight_grams", "baby")
    require(birthWeight == null || birthWeight in 0..100_000) {
        "baby birth_weight_grams 无效"
    }
    val avatar = payload.requireProviderNullableString("avatar_media_uuid", "baby")
    avatar?.let { requireProviderCanonicalUuid(it, "baby avatar_media_uuid") }
    return BabyWire(
        nickname = nickname,
        sex = sex,
        birthdayEpochDay = SyncWireMapper.birthdayEpochDay(payload),
        birthWeightGrams = birthWeight?.toInt(),
        avatarMediaUuid = avatar,
        inlineUpdatedAt = payload.requireInlineUpdatedAt(updatedAtLocation, "baby"),
    )
}

/** Single typed owner for CustomItem payloads consumed by pull and causal commit proof. */
internal fun decodeCustomItemWire(
    payload: JsonObject,
    updatedAtLocation: RootUpdatedAtLocation = RootUpdatedAtLocation.SeparateEnvelope,
): CustomItemWire {
    payload.requireProviderKeys(
        "name",
        "icon_slot",
        "created_by_membership_id",
        context = "custom_item",
        updatedAtLocation = updatedAtLocation,
    )
    val name = payload.requireProviderNonBlankString("name", "custom_item").trim()
    require(name.length <= 40) { "custom_item name 超出 current 限制" }
    val iconSlot = payload.requireProviderLong("icon_slot", "custom_item")
    require(iconSlot in 0..7) { "custom_item icon_slot 无效" }
    return CustomItemWire(
        name = name,
        iconSlot = iconSlot.toInt(),
        createdByMembershipId = payload.requireProviderNullableString(
            "created_by_membership_id",
            "custom_item",
        ).orEmpty().trim(),
        inlineUpdatedAt = payload.requireInlineUpdatedAt(updatedAtLocation, "custom_item"),
    )
}

private fun JsonObject.requireProviderKeys(
    vararg payloadKeys: String,
    context: String,
    updatedAtLocation: RootUpdatedAtLocation,
    extraAllowedKeys: Set<String> = emptySet(),
) {
    val expected = payloadKeys.toSet() + when (updatedAtLocation) {
        RootUpdatedAtLocation.SeparateEnvelope -> emptySet()
        RootUpdatedAtLocation.InlineStableRoot -> setOf("updated_at")
    }
    val allowed = expected + extraAllowedKeys
    require(keys.containsAll(expected) && keys.all(allowed::contains)) {
        "$context current wire 字段不完整或包含未知字段: ${keys.sorted()}"
    }
}

private fun JsonObject.requireInlineUpdatedAt(
    location: RootUpdatedAtLocation,
    context: String,
): Long? = when (location) {
    RootUpdatedAtLocation.SeparateEnvelope -> null
    RootUpdatedAtLocation.InlineStableRoot -> requireProviderLong("updated_at", context).also {
        require(it >= 0) { "$context.updated_at 必须是非负整数" }
    }
}

private fun JsonObject.requireProviderNonBlankString(key: String, context: String): String {
    val primitive = get(key) as? JsonPrimitive
    require(primitive?.isString == true && primitive.content.isNotBlank()) {
        "$context.$key 必须是非空字符串"
    }
    return primitive.content
}

private fun JsonObject.requireProviderNullableString(key: String, context: String): String? {
    require(key in this) { "$context 缺少 $key" }
    val value = getValue(key)
    if (value === JsonNull) return null
    val primitive = value as? JsonPrimitive
    require(primitive?.isString == true) { "$context.$key 必须是字符串或 null" }
    return primitive.content
}

private fun JsonObject.requireProviderLong(key: String, context: String): Long {
    val primitive = get(key) as? JsonPrimitive
    require(primitive?.isString == false && primitive.longOrNull != null) {
        "$context.$key 必须是整数"
    }
    return requireNotNull(primitive.longOrNull)
}

private fun JsonObject.requireProviderNullableLong(key: String, context: String): Long? {
    require(key in this) { "$context 缺少 $key" }
    val value = getValue(key)
    if (value === JsonNull) return null
    val primitive = value as? JsonPrimitive
    require(primitive?.isString == false && primitive.longOrNull != null) {
        "$context.$key 必须是整数或 null"
    }
    return primitive.longOrNull
}

private fun requireProviderCanonicalUuid(value: String, field: String) {
    val parsed = runCatching { UUID.fromString(value) }.getOrNull()
    require(parsed?.toString() == value) { "$field 必须是规范 UUID: $value" }
}
