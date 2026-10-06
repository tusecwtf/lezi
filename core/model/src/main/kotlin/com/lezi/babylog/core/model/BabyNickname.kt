package com.lezi.babylog.core.model

const val BABY_NICKNAME_MAX_CODE_POINTS = 20

fun babyNicknameLength(value: String): Int =
    value.codePointCount(0, value.length)

/** Limits editable text without splitting a Unicode surrogate pair. */
fun limitBabyNicknameInput(value: String): String {
    if (babyNicknameLength(value) <= BABY_NICKNAME_MAX_CODE_POINTS) return value
    val end = value.offsetByCodePoints(0, BABY_NICKNAME_MAX_CODE_POINTS)
    return value.substring(0, end)
}

/** Canonical boundary used before a baby nickname is persisted. */
fun normalizeBabyNickname(raw: String, fallback: String = "年年"): String {
    val nickname = raw.trim().ifBlank { fallback }
    require(babyNicknameLength(nickname) <= BABY_NICKNAME_MAX_CODE_POINTS) {
        "宝宝昵称最多 $BABY_NICKNAME_MAX_CODE_POINTS 个字符"
    }
    return nickname
}
