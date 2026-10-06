package com.lezi.babylog.core.database

/** SQLite on minSdk 26 binds at most 999 variables. Stay under that for every `IN` list. */
internal const val SQL_BIND_CHUNK = 500

internal suspend fun <I, T> queryInChunks(
    ids: List<I>,
    query: suspend (List<I>) -> List<T>,
): List<T> {
    if (ids.isEmpty()) return emptyList()
    val rows = ArrayList<T>(ids.size)
    for (chunk in ids.chunked(SQL_BIND_CHUNK)) {
        rows.addAll(query(chunk))
    }
    return rows
}
