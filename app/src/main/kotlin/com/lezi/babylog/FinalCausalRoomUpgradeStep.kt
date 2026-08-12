package com.lezi.babylog

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.lezi.babylog.core.common.LocalDataDomain
import com.lezi.babylog.core.common.LocalDataUpgradeBlockReason
import com.lezi.babylog.core.common.LocalDataUpgradeFailure
import com.lezi.babylog.core.common.LocalDataUpgradeStep
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

/** Contract 4→5 / Room 27→28: isolate causal transport journals from conflict evidence. */
internal class FinalCausalRoomUpgradeStep internal constructor(
    private val database: File,
) : LocalDataUpgradeStep {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context.getDatabasePath(PRODUCTION_DATABASE_NAME),
    )

    override val fromContractVersion: Int = 4
    override val toContractVersion: Int = 5
    override val affectedDomains: Set<LocalDataDomain> = setOf(LocalDataDomain.Room)

    override suspend fun migrate() {
        if (!database.exists()) return
        SQLiteDatabase.openDatabase(
            database.path,
            null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        ).use { sqlite ->
            if (isCompleteTarget(sqlite)) return@use
            requireCompleteSource(sqlite)
            sqlite.beginTransaction()
            try {
                sqlite.execSQL(
                    """
                    CREATE TABLE causal_transport_journal (
                        journalKey TEXT NOT NULL PRIMARY KEY,
                        payloadJson TEXT NOT NULL,
                        contentEpoch INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                sqlite.execSQL(
                    """
                    INSERT INTO causal_transport_journal(journalKey, payloadJson, contentEpoch)
                    SELECT conflictId, branchesJson, cachedAt
                    FROM conflict_detail_cache
                    WHERE conflictId LIKE 'conflict-page-stage:%'
                       OR conflictId LIKE 'frozen-mutation:%'
                       OR conflictId LIKE 'frozen-media-spool:%'
                       OR conflictId = 'replica-reset-receipt:current'
                    """.trimIndent(),
                )
                sqlite.execSQL(
                    """
                    DELETE FROM conflict_detail_cache
                    WHERE conflictId LIKE 'conflict-page-stage:%'
                       OR conflictId LIKE 'frozen-mutation:%'
                       OR conflictId LIKE 'frozen-media-spool:%'
                       OR conflictId = 'replica-reset-receipt:current'
                    """.trimIndent(),
                )
                sqlite.execSQL(
                    "INSERT OR REPLACE INTO room_master_table " +
                        "(id, identity_hash) VALUES(42, ?)",
                    arrayOf(TARGET_ROOM_IDENTITY_HASH),
                )
                sqlite.version = TARGET_ROOM_SCHEMA
                sqlite.setTransactionSuccessful()
            } finally {
                sqlite.endTransaction()
            }
        }
    }

    override suspend fun verify() {
        if (!database.exists()) return
        SQLiteDatabase.openDatabase(
            database.path,
            null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        ).use { sqlite ->
            check(isCompleteTarget(sqlite)) { "Room schema 未升级到 $TARGET_ROOM_SCHEMA" }
            val leakedTransportRows = sqlite.rawQuery(
                """
                SELECT COUNT(*) FROM conflict_detail_cache
                WHERE conflictId LIKE 'conflict-page-stage:%'
                   OR conflictId LIKE 'frozen-mutation:%'
                   OR conflictId LIKE 'frozen-media-spool:%'
                   OR conflictId = 'replica-reset-receipt:current'
                """.trimIndent(),
                null,
            ).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getLong(0)
            }
            check(leakedTransportRows == 0L) {
                "transport journal 仍混存在 conflict_detail_cache"
            }
        }
    }

    private fun requireCompleteSource(sqlite: SQLiteDatabase) {
        if (
            sqlite.version != SOURCE_ROOM_SCHEMA ||
            roomIdentityHash(sqlite) != SOURCE_ROOM_IDENTITY_HASH
        ) {
            throw LocalDataUpgradeFailure(
                LocalDataUpgradeBlockReason.InconsistentData,
                "Room 状态不是完整的 $SOURCE_ROOM_SCHEMA 源或 " +
                    "$TARGET_ROOM_SCHEMA 目标，已保留原数据并停止升级",
            )
        }
    }

    private fun isCompleteTarget(sqlite: SQLiteDatabase): Boolean =
        sqlite.version == TARGET_ROOM_SCHEMA &&
            roomIdentityHash(sqlite) == TARGET_ROOM_IDENTITY_HASH &&
            sqlite.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' " +
                    "AND name = 'causal_transport_journal'",
                null,
            ).use { it.moveToFirst() }

    private fun roomIdentityHash(sqlite: SQLiteDatabase): String? = sqlite.rawQuery(
        "SELECT identity_hash FROM room_master_table WHERE id = 42",
        null,
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private companion object {
        const val SOURCE_ROOM_SCHEMA = 27
        const val SOURCE_ROOM_IDENTITY_HASH = "13347fcd9f15f748522196b3feeab925"
        const val TARGET_ROOM_SCHEMA = 28
        const val TARGET_ROOM_IDENTITY_HASH = "cfaf78a0c995957f7374cba2e7a45418"
    }
}
