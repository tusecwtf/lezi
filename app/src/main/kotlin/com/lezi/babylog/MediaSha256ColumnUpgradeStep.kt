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

/** Contract 5→6 / Room 28→29: add nullable media_assets.sha256. Never hashes files. */
internal class MediaSha256ColumnUpgradeStep internal constructor(
    private val database: File,
) : LocalDataUpgradeStep {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context.getDatabasePath(PRODUCTION_DATABASE_NAME),
    )

    override val fromContractVersion: Int = 5
    override val toContractVersion: Int = 6
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
                    "ALTER TABLE media_assets ADD COLUMN sha256 TEXT DEFAULT NULL",
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
            hasSha256Column(sqlite)

    private fun hasSha256Column(sqlite: SQLiteDatabase): Boolean =
        sqlite.rawQuery("PRAGMA table_info(media_assets)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == "sha256") return@use true
            }
            false
        }

    private fun roomIdentityHash(sqlite: SQLiteDatabase): String? = sqlite.rawQuery(
        "SELECT identity_hash FROM room_master_table WHERE id = 42",
        null,
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private companion object {
        const val SOURCE_ROOM_SCHEMA = 28
        const val SOURCE_ROOM_IDENTITY_HASH = "cfaf78a0c995957f7374cba2e7a45418"
        const val TARGET_ROOM_SCHEMA = 29
        const val TARGET_ROOM_IDENTITY_HASH = "de94eda8425832877fd3fc91a7950030"
    }
}
