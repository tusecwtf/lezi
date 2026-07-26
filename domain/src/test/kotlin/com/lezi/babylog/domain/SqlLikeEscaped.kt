package com.lezi.babylog.domain

/**
 * SQLite-style `LIKE` matcher with `ESCAPE '\'` for unit tests that cannot
 * host Room. Mirrors the production `searchCandidates` contract so fake DAOs
 * and pure pattern tests share one semantics surface.
 *
 * Rules (SQLite defaults used by Room on Android):
 * - `%` matches any sequence (including empty)
 * - `_` matches any single character
 * - `escape` makes the next character literal (including `%`, `_`, and itself)
 */
internal fun matchesSqlLike(
    text: String,
    pattern: String,
    escape: Char = '\\',
): Boolean {
    fun match(ti: Int, pi: Int): Boolean {
        var t = ti
        var p = pi
        while (p < pattern.length) {
            val pc = pattern[p]
            when {
                pc == escape -> {
                    if (p + 1 >= pattern.length) return false
                    val literal = pattern[p + 1]
                    if (t >= text.length || text[t] != literal) return false
                    t++
                    p += 2
                }
                pc == '%' -> {
                    // Greedy-free: try zero-width then consume one text char at a time.
                    if (p + 1 >= pattern.length) return true
                    var tt = t
                    while (tt <= text.length) {
                        if (match(tt, p + 1)) return true
                        if (tt == text.length) break
                        tt++
                    }
                    return false
                }
                pc == '_' -> {
                    if (t >= text.length) return false
                    t++
                    p++
                }
                else -> {
                    if (t >= text.length || text[t] != pc) return false
                    t++
                    p++
                }
            }
        }
        return t == text.length
    }
    return match(0, 0)
}
