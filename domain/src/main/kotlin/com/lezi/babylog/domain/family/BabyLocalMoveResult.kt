package com.lezi.babylog.domain.family

enum class BabyLocalMoveResult {
    Moved,
    Empty,
    Unavailable,
    Boundary,
    ;

    val feedback: String?
        get() = when (this) {
            Moved -> null
            Empty -> "暂无可排序的宝宝"
            Unavailable -> "宝宝已不在当前列表"
            Boundary -> "宝宝已在该位置"
        }
}
