package com.lezi.babylog.sync.session

import java.text.Normalizer

/**
 * Local-only UI placeholder for the current device; must never be uploaded as a
 * real family 称呼 or treated as a caregiver name on the wire.
 */
const val LOCAL_DEVICE_DISPLAY_NAME = "我（本机）"

/**
 * Product-required family 称呼 for create / join / self-rename.
 * Blank, whitespace-only, and the device-local placeholder all fail hard.
 */
internal fun requireMemberDisplayName(displayName: String?): String {
    require(!displayName.isNullOrBlank()) { "请填写家庭称呼" }
    require(displayName.none { it.isISOControl() || it.isBidirectionalControl() }) {
        "家庭称呼不能包含控制字符或双向格式控制符"
    }
    val normalized = normalizeHumanFacingName(displayName)
    require(normalized.isNotEmpty()) { "请填写家庭称呼" }
    require(normalized != normalizeHumanFacingName(LOCAL_DEVICE_DISPLAY_NAME)) {
        "请填写家庭称呼，不能使用本机占位名"
    }
    require(normalized.codePointCount(0, normalized.length) <= 128) {
        "家庭称呼最多 128 个字符"
    }
    return normalized
}

/** Product-required editable device label for create / join / rename. */
fun requireDeviceName(deviceName: String?): String {
    require(!deviceName.isNullOrBlank()) { "请填写设备称呼" }
    require(deviceName.none { it.isISOControl() || it.isBidirectionalControl() }) {
        "设备称呼不能包含控制字符或双向格式控制符"
    }
    return normalizeHumanFacingName(deviceName).also {
        require(it.isNotEmpty()) { "请填写设备称呼" }
        require(it.codePointCount(0, it.length) <= 128) { "设备称呼最多 128 个字符" }
    }
}

/**
 * Optional shared family name for create / owner rename.
 * Blank becomes null (server stores null; client applies fallback display).
 */
internal fun normalizeFamilyNameForWire(familyName: String?): String? {
    if (familyName == null) return null
    require(familyName.none { it.isISOControl() || it.isBidirectionalControl() }) {
        "家庭名不能包含控制字符或双向格式控制符"
    }
    val normalized = familyName.trim()
    if (normalized.isEmpty()) return null
    require(normalized.codePointCount(0, normalized.length) <= 64) {
        "家庭名最多 64 个字符"
    }
    return normalized
}

private fun normalizeHumanFacingName(value: String): String {
    val compatibilityNormalized = Normalizer.normalize(value, Normalizer.Form.NFKC).trim()
    return buildString(compatibilityNormalized.length) {
        var pendingSpace = false
        compatibilityNormalized.forEach { character ->
            if (character.isWhitespace()) {
                pendingSpace = isNotEmpty()
            } else {
                if (pendingSpace) append(' ')
                append(character)
                pendingSpace = false
            }
        }
    }
}

private fun Char.isBidirectionalControl(): Boolean =
    this == '\u061c' ||
        this in '\u200e'..'\u200f' ||
        this in '\u202a'..'\u202e' ||
        this in '\u2066'..'\u206f'
