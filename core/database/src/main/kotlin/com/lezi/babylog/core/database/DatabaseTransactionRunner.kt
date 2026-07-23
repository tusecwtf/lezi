package com.lezi.babylog.core.database

import androidx.room.withTransaction

/**
 * Keeps multi-DAO writes atomic without leaking Room into the domain API.
 */
interface DatabaseTransactionRunner {
    suspend fun <T> run(block: suspend () -> T): T
}

internal class RoomDatabaseTransactionRunner(
    private val database: LeziDatabase,
) : DatabaseTransactionRunner {
    override suspend fun <T> run(block: suspend () -> T): T =
        database.withTransaction { block() }
}
