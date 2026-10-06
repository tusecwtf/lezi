package com.lezi.babylog

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.lezi.babylog.core.common.LocalDataDomain
import com.lezi.babylog.core.common.LocalDataUpgradeFailure
import com.lezi.babylog.core.common.LocalDataUpgradeBlockReason
import com.lezi.babylog.core.common.LocalDataUpgradeStep
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.core.model.deviceLayoutSnapshot
import com.lezi.babylog.core.model.hasLegacyLocalCustomCatalogKeys
import com.lezi.babylog.core.model.stabilizeCustomLayoutSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/** Contract 1→2: make the family custom-definition identity unique on device. */
internal class CustomItemClientUuidIndexUpgradeStep internal constructor(
    private val database: File,
    private val settingsStore: SettingsStore,
) : LocalDataUpgradeStep {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        settingsStore: SettingsStore,
    ) : this(context.getDatabasePath(PRODUCTION_DATABASE_NAME), settingsStore)

    override val fromContractVersion: Int = 1
    override val toContractVersion: Int = 2
    override val affectedDomains: Set<LocalDataDomain> = setOf(
        LocalDataDomain.Room,
        LocalDataDomain.Settings,
    )

    override suspend fun migrate() {
        val clientUuidByLocalId = readCustomItemIdentities()
        if (database.exists()) {
            SQLiteDatabase.openDatabase(
                database.path,
                null,
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            ).use { sqlite ->
                sqlite.beginTransaction()
                try {
                    val duplicate = sqlite.rawQuery(
                        """
                        SELECT clientUuid
                        FROM custom_items
                        GROUP BY clientUuid
                        HAVING COUNT(*) > 1
                        LIMIT 1
                        """.trimIndent(),
                        null,
                    ).use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
                    if (duplicate != null) {
                        throw LocalDataUpgradeFailure(
                            LocalDataUpgradeBlockReason.InconsistentData,
                            "自定义项目稳定身份重复，已保留原数据并停止升级",
                        )
                    }
                    sqlite.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS " +
                            "index_custom_items_clientUuid ON custom_items(clientUuid)",
                    )
                    // This step runs before Room is allowed to open the database. Keep the
                    // Room identity in the same transaction as user_version so Room 25 does
                    // not reject an otherwise valid contract-1 database with the v24 hash.
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
        val snapshot = settingsStore.settings.first().deviceLayoutSnapshot()
        settingsStore.setDeviceLayoutSnapshot(
            stabilizeCustomLayoutSnapshot(snapshot, clientUuidByLocalId),
        )
    }

    override suspend fun verify() {
        if (database.exists()) {
            SQLiteDatabase.openDatabase(
                database.path,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            ).use { sqlite ->
                check(sqlite.version == TARGET_ROOM_SCHEMA) {
                    "Room schema 未升级到 $TARGET_ROOM_SCHEMA"
                }
                val unique = sqlite.rawQuery(
                    "PRAGMA index_list('custom_items')",
                    null,
                ).use { cursor ->
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    val uniqueIndex = cursor.getColumnIndexOrThrow("unique")
                    var found = false
                    while (cursor.moveToNext()) {
                        if (
                            cursor.getString(nameIndex) == INDEX_NAME &&
                            cursor.getInt(uniqueIndex) == 1
                        ) {
                            found = true
                        }
                    }
                    found
                }
                check(unique) { "custom_items.clientUuid 唯一索引缺失" }
                val indexedColumns = sqlite.rawQuery(
                    "PRAGMA index_info('$INDEX_NAME')",
                    null,
                ).use { cursor ->
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    buildList {
                        while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                    }
                }
                check(indexedColumns == listOf("clientUuid")) {
                    "custom_items.clientUuid 唯一索引列不一致"
                }
                val identityHash = sqlite.rawQuery(
                    "SELECT identity_hash FROM room_master_table WHERE id = 42",
                    null,
                ).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
                check(identityHash == TARGET_ROOM_IDENTITY_HASH) {
                    "Room schema identity 未升级到 $TARGET_ROOM_SCHEMA"
                }
            }
        }
        check(
            !hasLegacyLocalCustomCatalogKeys(
                settingsStore.settings.first().deviceLayoutSnapshot(),
            ),
        ) {
            "设备布局仍含本机自增 custom key"
        }
    }

    private fun readCustomItemIdentities(): Map<Long, String> {
        if (!database.exists()) return emptyMap()
        return SQLiteDatabase.openDatabase(
            database.path,
            null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        ).use { sqlite ->
            sqlite.rawQuery("SELECT id, clientUuid FROM custom_items", null).use { cursor ->
                buildMap {
                    while (cursor.moveToNext()) {
                        put(cursor.getLong(0), cursor.getString(1))
                    }
                }
            }
        }
    }

    private companion object {
        const val TARGET_ROOM_SCHEMA = 25
        const val TARGET_ROOM_IDENTITY_HASH = "91e42aafeee126b93e223e69b38d9747"
        const val INDEX_NAME = "index_custom_items_clientUuid"
    }
}
