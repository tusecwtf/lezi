package com.lezi.babylog

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.lezi.babylog.core.common.LocalDataDomain
import com.lezi.babylog.core.common.LocalDataUpgradeBlockReason
import com.lezi.babylog.core.common.LocalDataUpgradeFailure
import com.lezi.babylog.core.common.LocalDataUpgradeStep
import com.lezi.babylog.core.common.wakeMediaUuid
import com.lezi.babylog.core.common.wakeObservationClientUuid
import com.lezi.babylog.core.database.causal.MediaReferenceHolderKind
import com.lezi.babylog.core.database.mediaAssetOwnerTriggerSql
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject

/**
 * Contract 3→4 / Room 26→27: causal columns, WakeObservation, conflict/source/media
 * reference tables, and deterministic closed-sleep → WakeObservation projection.
 *
 * In-place: never clears tables or uses destructive Room fallback.
 *
 * [recordMediaRoot] and [filesRoot] resolve legacy media localUri the same way
 * SyncMediaFileStore does (absolute under product roots, else relative to filesDir /
 * record-media). Wire §11 wake media_uuid requires real lowercase sha256 of retained
 * bytes — missing/unreadable media fails closed (matches server offline-migrate).
 */
internal class CausalRoomUpgradeStep internal constructor(
    private val database: File,
    private val recordMediaRoot: File,
    private val filesRoot: File,
) : LocalDataUpgradeStep {
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(
        database = context.getDatabasePath(PRODUCTION_DATABASE_NAME),
        recordMediaRoot = File(context.filesDir, "record-media"),
        filesRoot = context.filesDir,
    )

    /** Test / fixture constructor: database path + media roots under the fixture files tree. */
    internal constructor(database: File, filesRoot: File) : this(
        database = database,
        recordMediaRoot = File(filesRoot, "record-media"),
        filesRoot = filesRoot,
    )

    override val fromContractVersion: Int = 3
    override val toContractVersion: Int = 4
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
                addCausalColumns(sqlite)
                createCausalTables(sqlite)
                recreateMediaOwnershipTriggers(sqlite)
                migrateClosedSleepsToWakeObservations(sqlite)
                seedMediaReferences(sqlite)
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
            check(roomIdentityHash(sqlite) == TARGET_ROOM_IDENTITY_HASH) {
                "Room schema identity 未升级到 $TARGET_ROOM_SCHEMA"
            }
            for (table in REQUIRED_TABLES) {
                check(hasTable(sqlite, table)) { "缺少表 $table" }
            }
            // Open sleeps must not gain synthetic wake observations.
            val openWithWake = sqlite.rawQuery(
                """
                SELECT COUNT(*) FROM records r
                WHERE r.type = 'sleep'
                  AND r.endTimestamp IS NULL
                  AND r.effectiveWakeObservationClientUuid IS NOT NULL
                """.trimIndent(),
                null,
            ).use {
                check(it.moveToFirst())
                it.getLong(0)
            }
            check(openWithWake == 0L) {
                "开放睡眠不得被迁移自动闭合或挂上 WakeObservation"
            }
        }
    }

    private fun addCausalColumns(sqlite: SQLiteDatabase) {
        val babyCols = listOf(
            "baseVersion" to "TEXT",
            "mutationId" to "TEXT",
            "openConflictId" to "TEXT",
            "localBranchVersionId" to "TEXT",
        )
        babyCols.forEach { (name, type) ->
            addColumnIfMissing(sqlite, "babies", name, type)
        }
        val recordCols = babyCols + listOf(
            "effectiveWakeObservationClientUuid" to "TEXT",
        )
        recordCols.forEach { (name, type) ->
            addColumnIfMissing(sqlite, "records", name, type)
        }
        babyCols.forEach { (name, type) ->
            addColumnIfMissing(sqlite, "care_plans", name, type)
        }
        babyCols.forEach { (name, type) ->
            addColumnIfMissing(sqlite, "custom_items", name, type)
        }
        val mediaCols = babyCols + listOf(
            "wakeObservationId" to "INTEGER",
        )
        mediaCols.forEach { (name, type) ->
            addColumnIfMissing(sqlite, "media_assets", name, type)
        }
    }

    private fun createCausalTables(sqlite: SQLiteDatabase) {
        sqlite.execSQL(
            """
            CREATE TABLE IF NOT EXISTS wake_observations (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                clientUuid TEXT NOT NULL,
                sleepRecordClientUuid TEXT NOT NULL,
                wakeTimestamp INTEGER NOT NULL,
                observerMembershipId TEXT NOT NULL DEFAULT '',
                note TEXT,
                withdrawn INTEGER NOT NULL DEFAULT 0,
                updatedAt INTEGER NOT NULL,
                deletedAt INTEGER,
                syncDirty INTEGER NOT NULL DEFAULT 1,
                familyPublishedUpdatedAt INTEGER DEFAULT NULL,
                baseVersion TEXT DEFAULT NULL,
                mutationId TEXT DEFAULT NULL,
                openConflictId TEXT DEFAULT NULL,
                localBranchVersionId TEXT DEFAULT NULL
            )
            """.trimIndent(),
        )
        sqlite.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_wake_observations_clientUuid " +
                "ON wake_observations(clientUuid)",
        )
        sqlite.execSQL(
            "CREATE INDEX IF NOT EXISTS index_wake_observations_sleepRecordClientUuid " +
                "ON wake_observations(sleepRecordClientUuid)",
        )
        sqlite.execSQL(
            "CREATE INDEX IF NOT EXISTS index_wake_observations_updatedAt " +
                "ON wake_observations(updatedAt)",
        )
        sqlite.execSQL(
            "CREATE INDEX IF NOT EXISTS index_wake_observations_syncDirty " +
                "ON wake_observations(syncDirty)",
        )
        sqlite.execSQL(
            "CREATE INDEX IF NOT EXISTS index_wake_observations_openConflictId " +
                "ON wake_observations(openConflictId)",
        )

        sqlite.execSQL(
            """
            CREATE TABLE IF NOT EXISTS conflict_summaries (
                conflictId TEXT NOT NULL PRIMARY KEY,
                entityType TEXT NOT NULL,
                clientUuid TEXT NOT NULL,
                baseVersionId TEXT,
                stableVersionId TEXT NOT NULL,
                status TEXT NOT NULL,
                kind TEXT NOT NULL,
                branchVersionIdsJson TEXT NOT NULL DEFAULT '[]',
                updatedAt INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        sqlite.execSQL(
            "CREATE INDEX IF NOT EXISTS index_conflict_summaries_entityType_clientUuid " +
                "ON conflict_summaries(entityType, clientUuid)",
        )
        sqlite.execSQL(
            "CREATE INDEX IF NOT EXISTS index_conflict_summaries_status " +
                "ON conflict_summaries(status)",
        )

        sqlite.execSQL(
            """
            CREATE TABLE IF NOT EXISTS conflict_detail_cache (
                conflictId TEXT NOT NULL PRIMARY KEY,
                stableRootJson TEXT NOT NULL,
                baseRootJson TEXT,
                branchesJson TEXT NOT NULL,
                conflictPathsJson TEXT NOT NULL,
                cachedAt INTEGER NOT NULL
            )
            """.trimIndent(),
        )

        sqlite.execSQL(
            """
            CREATE TABLE IF NOT EXISTS suspected_duplicate_groups (
                groupId TEXT NOT NULL PRIMARY KEY,
                babyClientUuid TEXT NOT NULL,
                recordType TEXT NOT NULL,
                memberClientUuidsJson TEXT NOT NULL,
                status TEXT NOT NULL,
                updatedAt INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        sqlite.execSQL(
            "CREATE INDEX IF NOT EXISTS index_suspected_duplicate_groups_status " +
                "ON suspected_duplicate_groups(status)",
        )
        sqlite.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "index_suspected_duplicate_groups_babyClientUuid_recordType " +
                "ON suspected_duplicate_groups(babyClientUuid, recordType)",
        )

        sqlite.execSQL(
            """
            CREATE TABLE IF NOT EXISTS source_relations (
                relationId TEXT NOT NULL PRIMARY KEY,
                displayClientUuid TEXT NOT NULL,
                mediaRetained INTEGER NOT NULL DEFAULT 1,
                reason TEXT NOT NULL,
                mutationId TEXT NOT NULL,
                createdByMembershipId TEXT NOT NULL,
                createdAt INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        sqlite.execSQL(
            "CREATE INDEX IF NOT EXISTS index_source_relations_displayClientUuid " +
                "ON source_relations(displayClientUuid)",
        )

        sqlite.execSQL(
            """
            CREATE TABLE IF NOT EXISTS source_relation_members (
                relationId TEXT NOT NULL,
                recordClientUuid TEXT NOT NULL,
                role TEXT NOT NULL,
                PRIMARY KEY(relationId, recordClientUuid)
            )
            """.trimIndent(),
        )
        sqlite.execSQL(
            "CREATE INDEX IF NOT EXISTS index_source_relation_members_recordClientUuid " +
                "ON source_relation_members(recordClientUuid)",
        )

        sqlite.execSQL(
            """
            CREATE TABLE IF NOT EXISTS source_relation_declarations (
                mutationId TEXT NOT NULL PRIMARY KEY,
                recordClientUuid TEXT NOT NULL,
                equivalentToClientUuid TEXT NOT NULL,
                expectedRecordVersion TEXT NOT NULL,
                expectedOtherVersion TEXT NOT NULL,
                authorMembershipId TEXT NOT NULL,
                status TEXT NOT NULL,
                createdAt INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        sqlite.execSQL(
            "CREATE INDEX IF NOT EXISTS index_source_relation_declarations_recordClientUuid " +
                "ON source_relation_declarations(recordClientUuid)",
        )
        sqlite.execSQL(
            "CREATE INDEX IF NOT EXISTS index_source_relation_declarations_status " +
                "ON source_relation_declarations(status)",
        )

        sqlite.execSQL(
            """
            CREATE TABLE IF NOT EXISTS media_references (
                mediaUuid TEXT NOT NULL,
                holderKind TEXT NOT NULL,
                holderId TEXT NOT NULL,
                localUri TEXT,
                remoteUri TEXT,
                createdAt INTEGER NOT NULL,
                PRIMARY KEY(mediaUuid, holderKind, holderId)
            )
            """.trimIndent(),
        )
        sqlite.execSQL(
            "CREATE INDEX IF NOT EXISTS index_media_references_localUri " +
                "ON media_references(localUri)",
        )
        sqlite.execSQL(
            "CREATE INDEX IF NOT EXISTS index_media_references_holderKind_holderId " +
                "ON media_references(holderKind, holderId)",
        )
    }

    private fun recreateMediaOwnershipTriggers(sqlite: SQLiteDatabase) {
        sqlite.execSQL("DROP TRIGGER IF EXISTS media_assets_owner_insert")
        sqlite.execSQL("DROP TRIGGER IF EXISTS media_assets_owner_update")
        sqlite.execSQL(mediaAssetOwnerTriggerSql("media_assets_owner_insert", "INSERT"))
        sqlite.execSQL(mediaAssetOwnerTriggerSql("media_assets_owner_update", "UPDATE"))
    }

    private fun migrateClosedSleepsToWakeObservations(sqlite: SQLiteDatabase) {
        sqlite.rawQuery(
            """
            SELECT id, clientUuid, endTimestamp, note, updatedAt, deletedAt,
                   createdByMembershipId, syncDirty, familyPublishedUpdatedAt
            FROM records
            WHERE type = 'sleep' AND endTimestamp IS NOT NULL
            """.trimIndent(),
            null,
        ).use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow("id")
            val uuidIdx = cursor.getColumnIndexOrThrow("clientUuid")
            val endIdx = cursor.getColumnIndexOrThrow("endTimestamp")
            val noteIdx = cursor.getColumnIndexOrThrow("note")
            val updatedIdx = cursor.getColumnIndexOrThrow("updatedAt")
            val deletedIdx = cursor.getColumnIndexOrThrow("deletedAt")
            val authorIdx = cursor.getColumnIndexOrThrow("createdByMembershipId")
            val dirtyIdx = cursor.getColumnIndexOrThrow("syncDirty")
            val publishedIdx = cursor.getColumnIndexOrThrow("familyPublishedUpdatedAt")
            while (cursor.moveToNext()) {
                val recordId = cursor.getLong(idIdx)
                val sleepUuid = cursor.getString(uuidIdx)
                val wakeTs = cursor.getLong(endIdx)
                val note = if (cursor.isNull(noteIdx)) null else cursor.getString(noteIdx)
                val updatedAt = cursor.getLong(updatedIdx)
                val deletedAt =
                    if (cursor.isNull(deletedIdx)) null else cursor.getLong(deletedIdx)
                val author = cursor.getString(authorIdx).orEmpty()
                val syncDirty = cursor.getInt(dirtyIdx) != 0
                val published =
                    if (cursor.isNull(publishedIdx)) null else cursor.getLong(publishedIdx)

                val wakeUuid = wakeObservationClientUuid(sleepUuid, updatedAt)
                // Idempotent: skip if already projected.
                val exists = sqlite.rawQuery(
                    "SELECT 1 FROM wake_observations WHERE clientUuid = ? LIMIT 1",
                    arrayOf(wakeUuid),
                ).use { it.moveToFirst() }
                if (exists) {
                    sqlite.execSQL(
                        """
                        UPDATE records
                        SET effectiveWakeObservationClientUuid = ?,
                            endTimestamp = NULL
                        WHERE clientUuid = ?
                        """.trimIndent(),
                        arrayOf(wakeUuid, sleepUuid),
                    )
                    continue
                }

                sqlite.execSQL(
                    """
                    INSERT INTO wake_observations(
                        clientUuid, sleepRecordClientUuid, wakeTimestamp,
                        observerMembershipId, note, withdrawn, updatedAt, deletedAt,
                        syncDirty, familyPublishedUpdatedAt
                    ) VALUES(?, ?, ?, ?, ?, 0, ?, ?, ?, ?)
                    """.trimIndent(),
                    arrayOf<Any?>(
                        wakeUuid,
                        sleepUuid,
                        wakeTs,
                        author,
                        note,
                        updatedAt,
                        deletedAt,
                        if (syncDirty) 1 else 0,
                        published,
                    ),
                )
                val wakeRowId = sqlite.rawQuery(
                    "SELECT id FROM wake_observations WHERE clientUuid = ? LIMIT 1",
                    arrayOf(wakeUuid),
                ).use {
                    check(it.moveToFirst())
                    it.getLong(0)
                }

                transferLogMediaToWake(
                    sqlite = sqlite,
                    sleepRecordId = recordId,
                    sleepUuid = sleepUuid,
                    wakeUuid = wakeUuid,
                    wakeRowId = wakeRowId,
                    tombstoneAt = updatedAt,
                )

                // SleepStart-only after transfer: wake time lives on WakeObservation
                // (wire forbids sleep end_timestamp; matches server offline-migrate).
                sqlite.execSQL(
                    """
                    UPDATE records
                    SET effectiveWakeObservationClientUuid = ?,
                        endTimestamp = NULL
                    WHERE clientUuid = ?
                    """.trimIndent(),
                    arrayOf(wakeUuid, sleepUuid),
                )
            }
        }
    }

    private fun transferLogMediaToWake(
        sqlite: SQLiteDatabase,
        sleepRecordId: Long,
        sleepUuid: String,
        wakeUuid: String,
        wakeRowId: Long,
        tombstoneAt: Long,
    ) {
        sqlite.rawQuery(
            """
            SELECT id, clientUuid, localUri, remoteUri, mime, width, height, byteSize,
                   createdAt, updatedAt, deletedAt, syncDirty
            FROM media_assets
            WHERE recordId = ? AND kind = 'log'
            """.trimIndent(),
            arrayOf(sleepRecordId.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val legacyUuid = cursor.getString(cursor.getColumnIndexOrThrow("clientUuid"))
                val localUri = cursor.getString(cursor.getColumnIndexOrThrow("localUri"))
                val remoteUri = nullableString(cursor, "remoteUri")
                val mime = nullableString(cursor, "mime")
                val width = nullableInt(cursor, "width")
                val height = nullableInt(cursor, "height")
                val byteSize = cursor.getLong(cursor.getColumnIndexOrThrow("byteSize"))
                val createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("createdAt"))
                val updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updatedAt"))
                val deletedAt = nullableLong(cursor, "deletedAt")
                val syncDirty =
                    cursor.getInt(cursor.getColumnIndexOrThrow("syncDirty")) != 0
                val sha = sha256HexForLocalUri(localUri)
                val wakeMediaUuid = wakeMediaUuid(sleepUuid, legacyUuid, sha)

                val already = sqlite.rawQuery(
                    "SELECT 1 FROM media_assets WHERE clientUuid = ? LIMIT 1",
                    arrayOf(wakeMediaUuid),
                ).use { it.moveToFirst() }
                if (!already) {
                    sqlite.execSQL(
                        """
                        INSERT INTO media_assets(
                            recordId, carePlanId, wakeObservationId, clientUuid, kind,
                            babyId, localUri, remoteUri, mime, width, height, byteSize,
                            createdAt, updatedAt, deletedAt, syncDirty
                        ) VALUES(NULL, NULL, ?, ?, 'wake', NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                        arrayOf<Any?>(
                            wakeRowId,
                            wakeMediaUuid,
                            localUri,
                            remoteUri,
                            mime,
                            width,
                            height,
                            byteSize,
                            createdAt,
                            updatedAt,
                            deletedAt,
                            if (syncDirty) 1 else 0,
                        ),
                    )
                    // Holder identities: STABLE_ROOT uses WakeObservation client UUID (root
                    // identity until a server stable_version_id is pulled). Not a second
                    // "local:" vocabulary — engine replaceHolders can key on this uuid.
                    insertMediaReference(
                        sqlite = sqlite,
                        mediaUuid = wakeMediaUuid,
                        holderKind = MediaReferenceHolderKind.STABLE_ROOT,
                        holderId = wakeUuid,
                        localUri = localUri,
                        remoteUri = remoteUri,
                        createdAt = createdAt,
                    )
                }

                if (deletedAt == null) {
                    sqlite.execSQL(
                        """
                        UPDATE media_assets
                        SET deletedAt = ?, updatedAt = ?
                        WHERE clientUuid = ? AND deletedAt IS NULL
                        """.trimIndent(),
                        arrayOf(tombstoneAt, tombstoneAt, legacyUuid),
                    )
                }
            }
        }
    }

    /**
     * Seed holder rows for live media after causal tables exist.
     *
     * Temporary local-holder contract (until pull/ack supplies server handles):
     * - STABLE_ROOT.holderId = mediaUuid (the media's own stable identity; not a
     *   server version_id — replaceHolders rewrites this when a real stable_version_id
     *   or parent root version arrives).
     * - LOCAL_MUTATION.holderId = media.mutationId when frozen, else mediaUuid
     *   (kind distinguishes pending content; avoid a second "pending:" namespace).
     */
    private fun seedMediaReferences(sqlite: SQLiteDatabase) {
        sqlite.rawQuery(
            """
            SELECT clientUuid, localUri, remoteUri, createdAt, deletedAt, syncDirty, mutationId
            FROM media_assets
            """.trimIndent(),
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val mediaUuid = cursor.getString(0)
                val localUri = cursor.getString(1)
                val remoteUri = if (cursor.isNull(2)) null else cursor.getString(2)
                val createdAt = cursor.getLong(3)
                val deletedAt = if (cursor.isNull(4)) null else cursor.getLong(4)
                val syncDirty = cursor.getInt(5) != 0
                val mutationId = if (cursor.isNull(6)) null else cursor.getString(6)
                if (deletedAt != null) continue
                insertMediaReference(
                    sqlite = sqlite,
                    mediaUuid = mediaUuid,
                    holderKind = MediaReferenceHolderKind.STABLE_ROOT,
                    holderId = mediaUuid,
                    localUri = localUri,
                    remoteUri = remoteUri,
                    createdAt = createdAt,
                )
                if (syncDirty) {
                    insertMediaReference(
                        sqlite = sqlite,
                        mediaUuid = mediaUuid,
                        holderKind = MediaReferenceHolderKind.LOCAL_MUTATION,
                        holderId = mutationId?.takeIf { it.isNotBlank() } ?: mediaUuid,
                        localUri = localUri,
                        remoteUri = remoteUri,
                        createdAt = createdAt,
                    )
                }
            }
        }
    }

    private fun insertMediaReference(
        sqlite: SQLiteDatabase,
        mediaUuid: String,
        holderKind: String,
        holderId: String,
        localUri: String?,
        remoteUri: String?,
        createdAt: Long,
    ) {
        sqlite.execSQL(
            """
            INSERT OR IGNORE INTO media_references(
                mediaUuid, holderKind, holderId, localUri, remoteUri, createdAt
            ) VALUES(?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(mediaUuid, holderKind, holderId, localUri, remoteUri, createdAt),
        )
    }

    /**
     * Resolve [localUri] under product media roots (record-media, filesDir) the same
     * way SyncMediaFileStore does. Fail closed when bytes cannot be proven — never mint
     * empty-hash wake media_uuid values that fork from server offline-migrate.
     */
    private fun sha256HexForLocalUri(localUri: String): String {
        val file = resolveLocalMediaFile(localUri)
            ?: throw LocalDataUpgradeFailure(
                LocalDataUpgradeBlockReason.InconsistentData,
                "无法读取 sleep 媒体字节以计算 wire §11 sha256: $localUri",
            )
        val bytes = file.readBytes()
        check(bytes.isNotEmpty()) {
            "sleep 媒体字节为空，无法计算 wire §11 sha256: $localUri"
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { b -> "%02x".format(b) }
    }

    private fun resolveLocalMediaFile(localUri: String): File? {
        if (localUri.isBlank()) return null
        val raw = File(localUri)
        val candidates = buildList {
            if (raw.isAbsolute) add(raw)
            add(File(recordMediaRoot, localUri))
            add(File(filesRoot, localUri))
            // Relative names sometimes omit the record-media segment.
            add(File(recordMediaRoot, raw.name))
        }
        return candidates
            .map { it.canonicalFile }
            .firstOrNull { it.isFile }
    }

    private fun addColumnIfMissing(
        sqlite: SQLiteDatabase,
        table: String,
        column: String,
        type: String,
    ) {
        if (hasColumn(sqlite, table, column)) return
        sqlite.execSQL("ALTER TABLE $table ADD COLUMN $column $type DEFAULT NULL")
    }

    private fun hasColumn(sqlite: SQLiteDatabase, table: String, column: String): Boolean =
        sqlite.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val nameIdx = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIdx) == column) return@use true
            }
            false
        }

    private fun isCompleteTarget(sqlite: SQLiteDatabase): Boolean =
        sqlite.version == TARGET_ROOM_SCHEMA &&
            roomIdentityHash(sqlite) == TARGET_ROOM_IDENTITY_HASH &&
            REQUIRED_TABLES.all { hasTable(sqlite, it) }

    private fun requireCompleteSource(sqlite: SQLiteDatabase) {
        val isSource = sqlite.version == SOURCE_ROOM_SCHEMA &&
            roomIdentityHash(sqlite) == SOURCE_ROOM_IDENTITY_HASH
        val isTarget = isCompleteTarget(sqlite)
        if (!isSource && !isTarget) {
            throw LocalDataUpgradeFailure(
                LocalDataUpgradeBlockReason.InconsistentData,
                "Room 状态不是完整的 $SOURCE_ROOM_SCHEMA 源或 " +
                    "$TARGET_ROOM_SCHEMA 目标，已保留原数据并停止升级",
            )
        }
    }

    private fun hasTable(sqlite: SQLiteDatabase, name: String): Boolean =
        sqlite.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(name),
        ).use { it.moveToFirst() }

    private fun roomIdentityHash(sqlite: SQLiteDatabase): String? =
        sqlite.rawQuery(
            "SELECT identity_hash FROM room_master_table WHERE id = 42",
            null,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun nullableString(cursor: android.database.Cursor, column: String): String? {
        val idx = cursor.getColumnIndexOrThrow(column)
        return if (cursor.isNull(idx)) null else cursor.getString(idx)
    }

    private fun nullableInt(cursor: android.database.Cursor, column: String): Int? {
        val idx = cursor.getColumnIndexOrThrow(column)
        return if (cursor.isNull(idx)) null else cursor.getInt(idx)
    }

    private fun nullableLong(cursor: android.database.Cursor, column: String): Long? {
        val idx = cursor.getColumnIndexOrThrow(column)
        return if (cursor.isNull(idx)) null else cursor.getLong(idx)
    }

    private companion object {
        const val SOURCE_ROOM_SCHEMA = 26
        const val SOURCE_ROOM_IDENTITY_HASH = "078622636b6b4463a0b66213aba5613a"
        const val TARGET_ROOM_SCHEMA = 27
        const val TARGET_ROOM_IDENTITY_HASH = "13347fcd9f15f748522196b3feeab925"
        val REQUIRED_TABLES = listOf(
            "wake_observations",
            "conflict_summaries",
            "conflict_detail_cache",
            "suspected_duplicate_groups",
            "source_relations",
            "source_relation_members",
            "source_relation_declarations",
            "media_references",
        )
    }
}
