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

/** Contract 2→3: transfer residual outbox intent to Room dirty flags, then retire outbox. */
internal class OutboxRetirementUpgradeStep internal constructor(
    private val database: File,
) : LocalDataUpgradeStep {
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(context.getDatabasePath(PRODUCTION_DATABASE_NAME))

    override val fromContractVersion: Int = 2
    override val toContractVersion: Int = 3
    override val affectedDomains: Set<LocalDataDomain> = setOf(LocalDataDomain.Room)

    override suspend fun migrate() {
        if (!database.exists()) return
        SQLiteDatabase.openDatabase(
            database.path,
            null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        ).use { sqlite ->
            sqlite.beginTransaction()
            try {
                rejectUnknownEntityTypes(sqlite)
                ENTITY_TABLES.forEach { (entityType, table) ->
                    // A missing entity is a stale row left by an earlier hard delete. There is
                    // nothing to publish; recreating it from an old payload would resurrect data.
                    sqlite.execSQL(
                        "UPDATE $table SET syncDirty = 1 WHERE clientUuid IN " +
                            "(SELECT clientUuid FROM outbox WHERE entityType = ?)",
                        arrayOf(entityType),
                    )
                    checkNoMappedRowRemainsClean(sqlite, entityType, table)
                }
                sqlite.execSQL("DROP TABLE outbox")
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
            check(sqlite.version == TARGET_ROOM_SCHEMA) {
                "Room schema 未升级到 $TARGET_ROOM_SCHEMA"
            }
            val outboxExists = sqlite.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'outbox'",
                null,
            ).use { it.moveToFirst() }
            check(!outboxExists) { "旧 outbox 表仍然存在" }
            val identityHash = sqlite.rawQuery(
                "SELECT identity_hash FROM room_master_table WHERE id = 42",
                null,
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            check(identityHash == TARGET_ROOM_IDENTITY_HASH) {
                "Room schema identity 未升级到 $TARGET_ROOM_SCHEMA"
            }
        }
    }

    private fun rejectUnknownEntityTypes(sqlite: SQLiteDatabase) {
        val unknown = sqlite.rawQuery(
            "SELECT DISTINCT entityType FROM outbox " +
                "WHERE entityType NOT IN (${ENTITY_TABLES.keys.joinToString { "?" }}) LIMIT 1",
            ENTITY_TABLES.keys.toTypedArray(),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        if (unknown != null) {
            throw LocalDataUpgradeFailure(
                LocalDataUpgradeBlockReason.InconsistentData,
                "发现无法转交发布意图的旧实体类型，已保留原数据并停止升级",
            )
        }
    }

    private fun checkNoMappedRowRemainsClean(
        sqlite: SQLiteDatabase,
        entityType: String,
        table: String,
    ) {
        val cleanCount = sqlite.rawQuery(
            "SELECT COUNT(*) FROM outbox o JOIN $table e ON e.clientUuid = o.clientUuid " +
                "WHERE o.entityType = ? AND e.syncDirty != 1",
            arrayOf(entityType),
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }
        check(cleanCount == 0L) { "旧发布意图未完整转交到 $table" }
    }

    private companion object {
        const val TARGET_ROOM_SCHEMA = 26
        const val TARGET_ROOM_IDENTITY_HASH = "078622636b6b4463a0b66213aba5613a"
        val ENTITY_TABLES = linkedMapOf(
            "baby" to "babies",
            "record" to "records",
            "care_plan" to "care_plans",
            "fulfillment_candidate" to "fulfillment_candidates",
            "media" to "media_assets",
            "custom_item" to "custom_items",
        )
    }
}
