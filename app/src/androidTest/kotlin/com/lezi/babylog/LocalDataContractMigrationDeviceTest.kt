package com.lezi.babylog

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.lezi.babylog.core.common.DefaultLocalDataGate
import com.lezi.babylog.core.common.LocalDataUpgradePlan
import com.lezi.babylog.core.common.LocalDataUpgradePlanner
import com.lezi.babylog.core.common.LocalDataUpgradeState
import com.lezi.babylog.core.database.LeziDatabase
import com.lezi.babylog.core.datastore.SettingsDataSource
import com.lezi.babylog.core.model.DeviceLayoutSnapshot
import com.lezi.babylog.core.model.deviceLayoutSnapshot
import com.lezi.babylog.sync.session.InMemorySecureRefreshTokenStore
import com.lezi.babylog.sync.session.DataStoreSyncPreferences
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.SyncSession
import com.lezi.babylog.sync.session.TrustedEndpointProfile
import dagger.Lazy
import java.io.File
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class LocalDataContractMigrationDeviceTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        LeziDatabase::class.java,
    )

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val settingsFile by lazy { context.preferencesDataStoreFile(SETTINGS_STORE_NAME) }
    private val fixtureRoot by lazy {
        File(context.noBackupFilesDir, "local-data-contract-migration-fixture")
    }
    private val storage by lazy {
        AndroidLocalDataStoragePaths(
            dataRoot = File(context.applicationInfo.dataDir),
            database = context.getDatabasePath(DATABASE_NAME),
            upgradeRoot = File(fixtureRoot, "upgrade"),
            settingsDataStore = settingsFile,
            widgetPreferences = File(fixtureRoot, "widget.xml"),
            widgetPreferencesName = "local_data_contract_migration_widget",
            securePreferences = File(fixtureRoot, "credentials.xml"),
            recordMedia = File(fixtureRoot, "record-media"),
            babyAvatars = File(fixtureRoot, "baby-avatars"),
        )
    }
    private lateinit var storeScope: CoroutineScope
    private var openedDatabase: LeziDatabase? = null

    @Before
    fun setUp() {
        context.deleteDatabase(DATABASE_NAME)
        settingsFile.delete()
        fixtureRoot.deleteRecursively()
        storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        openedDatabase?.close()
        storeScope.cancel()
        context.deleteDatabase(DATABASE_NAME)
        settingsFile.delete()
        fixtureRoot.deleteRecursively()
    }

    @Test
    fun contractOneGateRunsAdjacentStepsAndRoom26Opens() = runBlocking {
        migrationHelper.createDatabase(DATABASE_NAME, 24).apply {
            execSQL(
                """
                INSERT INTO babies(
                    id, familyId, nickname, birthdayEpochDay, themeColorArgb, sortOrder,
                    clientUuid, updatedAt, syncDirty, familyAuthority
                ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(1L, 7L, "年年", 20_000L, 0L, 0L, BABY_UUID, 100L, 1, 1),
            )
            execSQL(
                """
                INSERT INTO records(
                    id, clientUuid, babyId, type, timestamp, endTimestamp, payloadJson,
                    schemaVersion, updatedAt, syncDirty, createdByMembershipId
                ) VALUES(?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(
                    1L,
                    RECORD_UUID,
                    1L,
                    "sleep",
                    100L,
                    "{\"is_nap\":false}",
                    2,
                    120L,
                    1,
                    "membership-a",
                ),
            )
            execSQL(
                """
                INSERT INTO media_assets(
                    id, recordId, clientUuid, kind, localUri, byteSize,
                    createdAt, updatedAt, syncDirty
                ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(
                    1L,
                    1L,
                    MEDIA_UUID,
                    "log",
                    "retained-contract-one.jpg",
                    4L,
                    120L,
                    120L,
                    1,
                ),
            )
            execSQL(
                """
                INSERT INTO custom_items(
                    clientUuid, familyId, name, iconSlot, sortOrder, updatedAt,
                    deletedAt, createdByMembershipId, syncDirty
                ) VALUES(?, ?, ?, ?, ?, ?, NULL, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(CLIENT_UUID, 7L, "药", 2, 0, 100L, "member-1", 1),
            )
            close()
        }
        val retainedMedia = File(storage.recordMedia, "retained-contract-one.jpg").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        val retainedCredentialFile = storage.securePreferences.apply {
            parentFile?.mkdirs()
            writeText("opaque-encrypted-credential-fixture")
        }
        val retainedCredentialBytes = retainedCredentialFile.readBytes()
        val dataStore = PreferenceDataStoreFactory.create(
            scope = storeScope,
            produceFile = { settingsFile },
        )
        val settings = SettingsDataSource(dataStore)
        val timerJson =
            """{"schemaVersion":1,"completionClientUuid":"timer-session-a","leftRunning":true}"""
        settings.setDeviceLayoutSnapshot(
            DeviceLayoutSnapshot(
                quickRecordSlots = listOf("custom:1", "custom:999", "pee", ""),
                hiddenItems = setOf("custom:1", "custom:999", "sleep"),
                itemOrderJson = """["custom:999","pee","custom:1"]""",
            ),
        )
        settings.setNursingTimerJson(timerJson)
        val credentialStore = InMemorySecureRefreshTokenStore()
        val syncPreferences = DataStoreSyncPreferences(dataStore, credentialStore)
        val endpoint = TrustedEndpointProfile.tofuSpki(
            "https://192.168.77.4:8765",
            Base64.getEncoder().encodeToString(ByteArray(32) { 7 }),
        )
        syncPreferences.rememberEndpoint(endpoint)
        syncPreferences.saveSession(
            SyncSession(
                familyId = "family-a",
                accessToken = "access-a",
                refreshToken = "refresh-a",
                accessExpiresAtEpochSeconds = 2_000_000_000L,
                deviceId = "device-a",
                role = FamilyRole.Owner,
                pullCursor = 12L,
                pullGeneration = "generation-a",
                serverHost = "192.168.77.4",
                serverPort = 8765,
                serverScheme = "https",
                familyName = "乐乐一家",
                membershipId = "membership-a",
            ),
        )
        val customItemStep = CustomItemClientUuidIndexUpgradeStep(storage.database, settings)
        val environment = AndroidLocalDataUpgradeEnvironment(
            context = context,
            settings = Lazy { dataStore },
            credentials = Lazy { credentialStore },
            storage = storage,
        )
        val gate = DefaultLocalDataGate(
            currentContractVersion = 6,
            minimumMigratableContractVersion = 1,
            steps = setOf(
                customItemStep,
                OutboxRetirementUpgradeStep(storage.database),
                CausalRoomUpgradeStep(storage.database, storage.recordMedia, context.filesDir),
                FinalCausalRoomUpgradeStep(storage.database),
                MediaSha256ColumnUpgradeStep(storage.database),
            ),
            environment = environment,
        )

        val ready = gate.ensureReady()
        assertWithMessage(gate.diagnosticReport()).that(ready).isTrue()
        assertThat(gate.state.value).isEqualTo(LocalDataUpgradeState.Ready(6))

        val room = Room.databaseBuilder(
            context,
            LeziDatabase::class.java,
            DATABASE_NAME,
        ).build()
        openedDatabase = room
        val migrated = room.customItemDao().getByClientUuid(CLIENT_UUID)
        assertThat(migrated?.name).isEqualTo("药")
        val migratedLayout = settings.settings.first().deviceLayoutSnapshot()
        assertThat(migratedLayout.quickRecordSlots).containsExactly(
            "custom:$CLIENT_UUID",
            "",
            "pee",
            "",
        ).inOrder()
        assertThat(migratedLayout.hiddenItems).containsExactly(
            "custom:$CLIENT_UUID",
            "sleep",
        )
        assertThat(migratedLayout.itemOrderJson).isEqualTo(
            """["pee","custom:$CLIENT_UUID"]""",
        )
        assertThat(settings.nursingTimerJson.first()).isEqualTo(timerJson)
        assertThat(room.recordDao().getByClientUuid(RECORD_UUID)?.endTimestamp).isNull()
        assertThat(room.recordDao().getByClientUuid(RECORD_UUID)?.type).isEqualTo("sleep")
        // Open sleep: no WakeObservation auto-close.
        assertThat(room.recordDao().getByClientUuid(RECORD_UUID)?.effectiveWakeObservationClientUuid)
            .isNull()
        assertThat(room.wakeObservationDao().listForSleep(RECORD_UUID)).isEmpty()
        assertThat(room.mediaAssetDao().getByClientUuid(MEDIA_UUID)?.localUri)
            .isEqualTo("retained-contract-one.jpg")
        assertThat(room.mediaReferenceDao().listForMedia(MEDIA_UUID)).isNotEmpty()
        assertThat(retainedMedia.readBytes().toList())
            .containsExactlyElementsIn(byteArrayOf(1, 2, 3, 4).toList())
            .inOrder()
        assertThat(syncPreferences.session.first().familyId).isEqualTo("family-a")
        assertThat(syncPreferences.session.first().membershipId).isEqualTo("membership-a")
        assertThat(syncPreferences.verifiedEndpoint.first()).isEqualTo(endpoint)
        assertThat(credentialStore.getToken()).isEqualTo("refresh-a")
        assertThat(retainedCredentialFile.readBytes()).isEqualTo(retainedCredentialBytes)

        val reopened = reopenRoomAndAssertQuickCheck()
        assertThat(reopened.recordDao().getByClientUuid(RECORD_UUID)?.clientUuid)
            .isEqualTo(RECORD_UUID)
    }

    @Test
    fun everyReleasedProductionVersionMapsToACompleteForwardContractBoundary() {
        val catalog = JSONObject(
            InstrumentationRegistry.getInstrumentation().context.assets
                .open("android-release-compatibility.json")
                .bufferedReader()
                .use { it.readText() },
        )
        val releases = catalog.getJSONArray("released_versions")
        val versionCodes = mutableListOf<Int>()
        val observedBoundaries = mutableSetOf<Pair<Int, Int>>()
        val planner = LocalDataUpgradePlanner(
            currentContractVersion = 6,
            minimumMigratableContractVersion = 1,
            steps = setOf(
                CatalogMigrationStep(1, 2),
                CatalogMigrationStep(2, 3),
                CatalogMigrationStep(3, 4),
                CatalogMigrationStep(4, 5),
                CatalogMigrationStep(5, 6),
            ),
        )

        repeat(releases.length()) { index ->
            val release = releases.getJSONObject(index)
            val versionCode = release.getInt("version_code")
            val contract = release.getInt("local_data_contract")
            val roomSchema = release.getInt("room_schema")
            versionCodes += versionCode
            observedBoundaries += contract to roomSchema

            val inspection = detectLocalDataInspection(
                markerVersion = contract,
                roomSchema = roomSchema,
                currentContractVersion = 6,
                roomSchemasByContract = mapOf(
                    1 to 24,
                    2 to 25,
                    3 to 26,
                    4 to 27,
                    5 to 28,
                    6 to 29,
                ),
            )
            val plan = planner.planFrom(inspection.contractVersion)
            if (contract == 6) {
                assertThat(plan).isEqualTo(LocalDataUpgradePlan.Ready)
            } else {
                assertThat((plan as LocalDataUpgradePlan.Upgrade).steps.last().toContractVersion)
                    .isEqualTo(6)
            }
        }

        assertThat(versionCodes).containsExactlyElementsIn(6..33).inOrder()
        assertThat(observedBoundaries).containsExactly(
            1 to 24,
            2 to 25,
            3 to 26,
            4 to 27,
            5 to 28,
            6 to 29,
        )

        val target = catalog.getJSONObject("upgrade_target")
        assertThat(target.getInt("version_code")).isEqualTo(34)
        assertThat(target.getInt("room_schema")).isEqualTo(29)
        assertThat(target.getInt("local_data_contract")).isEqualTo(6)

    }

    @Test
    fun contractFourGateMovesTransportJournalsWithoutLosingConflictEvidence() = runBlocking {
        val canonicalConflictId = "00000000-0000-4000-8000-000000000027"
        val transportKeys = listOf(
            "conflict-page-stage:$canonicalConflictId",
            "frozen-mutation:record:00000000-0000-4000-8000-000000000001",
            "frozen-media-spool:00000000-0000-4000-8000-000000000002",
            "replica-reset-receipt:current",
        )
        migrationHelper.createDatabase(DATABASE_NAME, 27).apply {
            execSQL(
                "INSERT INTO conflict_detail_cache VALUES(?, '{}', NULL, ?, '[]', ?)",
                arrayOf(canonicalConflictId, "canonical-snapshot", 100L),
            )
            transportKeys.forEachIndexed { index, key ->
                execSQL(
                    "INSERT INTO conflict_detail_cache VALUES(?, '{}', NULL, ?, '[]', ?)",
                    arrayOf(key, "journal-$index", 200L + index),
                )
            }
            close()
        }

        FinalCausalRoomUpgradeStep(storage.database).also {
            it.migrate()
            it.verify()
        }

        SQLiteDatabase.openDatabase(
            storage.database.path,
            null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        ).use { sqlite ->
            assertThat(sqlite.version).isEqualTo(28)
            val journals = sqlite.rawQuery(
                "SELECT journalKey, payloadJson, contentEpoch " +
                    "FROM causal_transport_journal ORDER BY journalKey",
                null,
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(Triple(cursor.getString(0), cursor.getString(1), cursor.getLong(2)))
                    }
                }
            }
            assertThat(journals.map { it.first }).containsExactlyElementsIn(transportKeys)
            assertThat(journals.map { it.second }).containsExactly(
                "journal-0",
                "journal-1",
                "journal-2",
                "journal-3",
            )
            assertThat(
                sqlite.rawQuery(
                    "SELECT branchesJson FROM conflict_detail_cache WHERE conflictId = ?",
                    arrayOf(canonicalConflictId),
                ).use { cursor ->
                    assertThat(cursor.moveToFirst()).isTrue()
                    cursor.getString(0)
                },
            ).isEqualTo("canonical-snapshot")
            assertThat(
                sqlite.rawQuery(
                    "SELECT COUNT(*) FROM conflict_detail_cache " +
                        "WHERE conflictId LIKE 'frozen-%' " +
                        "OR conflictId LIKE 'conflict-page-stage:%' " +
                        "OR conflictId = 'replica-reset-receipt:current'",
                    null,
                ).use { cursor -> cursor.moveToFirst(); cursor.getLong(0) },
            ).isEqualTo(0L)
        }

        val reopened = reopenRoomAndAssertQuickCheck()
        assertThat(
            reopened.conflictSnapshotCacheDao().get(canonicalConflictId)?.snapshotJson,
        ).isEqualTo("canonical-snapshot")
        transportKeys.forEach { key ->
            assertThat(reopened.conflictSnapshotCacheDao().getTransportJournal(key))
                .isNotNull()
        }
    }

    @Test
    fun contractFiveGateAddsNullableMediaSha256WithoutRewritingCareRows() = runBlocking {
        migrationHelper.createDatabase(DATABASE_NAME, 28).apply {
            execSQL(
                "INSERT INTO local_users(id, displayName, deviceId, createdAt) VALUES(1, '家长', 'device-sha', 1)",
            )
            execSQL("INSERT INTO families(id, ownerUserId, createdAt) VALUES(1, 1, 1)")
            execSQL(
                "INSERT INTO memberships(familyId, userId, role, status, joinedAt) VALUES(1, 1, 'owner', 'active', 1)",
            )
            execSQL(
                """
                INSERT INTO babies(
                    id, familyId, nickname, birthdayEpochDay, themeColorArgb, sortOrder,
                    clientUuid, updatedAt, syncDirty, familyAuthority
                ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(1L, 1L, "年年", 20_000L, 0L, 0L, BABY_UUID, 100L, 0, 1),
            )
            execSQL(
                """
                INSERT INTO records(
                    id, clientUuid, babyId, type, timestamp, payloadJson, schemaVersion,
                    updatedAt, syncDirty, createdByMembershipId
                ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(
                    1L,
                    RECORD_UUID,
                    1L,
                    "formula",
                    100L,
                    "{}",
                    2,
                    120L,
                    1,
                    "membership-a",
                ),
            )
            execSQL(
                """
                INSERT INTO media_assets(
                    id, recordId, clientUuid, kind, localUri, byteSize,
                    createdAt, updatedAt, syncDirty
                ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(
                    1L,
                    1L,
                    MEDIA_UUID,
                    "log",
                    "retained-sha256.jpg",
                    4L,
                    120L,
                    120L,
                    1,
                ),
            )
            close()
        }
        val retainedMedia = File(storage.recordMedia, "retained-sha256.jpg").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        val dataStore = PreferenceDataStoreFactory.create(
            scope = storeScope,
            produceFile = { settingsFile },
        )
        val credentialStore = InMemorySecureRefreshTokenStore()
        val syncPreferences = DataStoreSyncPreferences(dataStore, credentialStore)
        val endpoint = TrustedEndpointProfile.tofuSpki(
            "https://192.168.77.4:8765",
            Base64.getEncoder().encodeToString(ByteArray(32) { 7 }),
        )
        syncPreferences.rememberEndpoint(endpoint)
        syncPreferences.saveSession(
            SyncSession(
                familyId = "family-a",
                accessToken = "access-a",
                refreshToken = "refresh-a",
                accessExpiresAtEpochSeconds = 2_000_000_000L,
                deviceId = "device-a",
                role = FamilyRole.Owner,
                pullCursor = 12L,
                pullGeneration = "generation-a",
                serverHost = "192.168.77.4",
                serverPort = 8765,
                serverScheme = "https",
                familyName = "乐乐一家",
                membershipId = "membership-a",
            ),
        )

        MediaSha256ColumnUpgradeStep(storage.database).also {
            it.migrate()
            it.verify()
        }

        SQLiteDatabase.openDatabase(
            storage.database.path,
            null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        ).use { sqlite ->
            assertThat(sqlite.version).isEqualTo(29)
            val media = sqlite.rawQuery(
                "SELECT sha256, updatedAt, syncDirty, localUri FROM media_assets WHERE clientUuid = ?",
                arrayOf(MEDIA_UUID),
            ).use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                Triple(
                    if (cursor.isNull(0)) null else cursor.getString(0),
                    cursor.getLong(1),
                    cursor.getInt(2),
                ) to cursor.getString(3)
            }
            assertThat(media.first.first).isNull()
            assertThat(media.first.second).isEqualTo(120L)
            assertThat(media.first.third).isEqualTo(1)
            assertThat(media.second).isEqualTo("retained-sha256.jpg")
        }

        val room = Room.databaseBuilder(
            context,
            LeziDatabase::class.java,
            DATABASE_NAME,
        ).build()
        openedDatabase = room
        val record = requireNotNull(room.recordDao().getByClientUuid(RECORD_UUID))
        assertThat(record.type).isEqualTo("formula")
        assertThat(record.updatedAt).isEqualTo(120L)
        assertThat(record.syncDirty).isTrue()
        val asset = requireNotNull(room.mediaAssetDao().getByClientUuid(MEDIA_UUID))
        assertThat(asset.sha256).isNull()
        assertThat(asset.updatedAt).isEqualTo(120L)
        assertThat(asset.syncDirty).isTrue()
        assertThat(asset.localUri).isEqualTo("retained-sha256.jpg")
        assertThat(room.membershipDao().listForFamily(1L)).hasSize(1)
        assertThat(syncPreferences.session.first().familyId).isEqualTo("family-a")
        assertThat(syncPreferences.session.first().membershipId).isEqualTo("membership-a")
        assertThat(syncPreferences.verifiedEndpoint.first()).isEqualTo(endpoint)
        assertThat(retainedMedia.readBytes().toList())
            .containsExactlyElementsIn(byteArrayOf(1, 2, 3, 4).toList())
            .inOrder()

        val reopened = reopenRoomAndAssertQuickCheck()
        assertThat(reopened.mediaAssetDao().getByClientUuid(MEDIA_UUID)?.sha256).isNull()
        assertThat(reopened.recordDao().getByClientUuid(RECORD_UUID)?.clientUuid)
            .isEqualTo(RECORD_UUID)
    }

    @Test
    fun contractTwoGateTransfersResidualOutboxIntentAndRoom26Opens() = runBlocking {
        migrationHelper.createDatabase(DATABASE_NAME, 25).apply {
            execSQL(
                """
                INSERT INTO babies(
                    id, familyId, nickname, birthdayEpochDay, themeColorArgb, sortOrder,
                    clientUuid, updatedAt, syncDirty, familyAuthority
                ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(1L, 7L, "年年", 20_000L, 0L, 0L, BABY_UUID, 100L, 0, 1),
            )
            execSQL(
                """
                INSERT INTO records(
                    clientUuid, babyId, type, timestamp, payloadJson, schemaVersion,
                    updatedAt, syncDirty, createdByMembershipId
                ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(RECORD_UUID, 1L, "formula", 100L, "{}", 2, 120L, 0, "member-1"),
            )
            execSQL(
                """
                INSERT INTO outbox(
                    familyId, entityType, clientUuid, payloadJson, updatedAt, createdAt
                ) VALUES(?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>("family-a", "record", RECORD_UUID, "{}", 120L, 120L),
            )
            close()
        }
        val retainedMedia = File(storage.recordMedia, "retained.jpg").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        val dataStore = PreferenceDataStoreFactory.create(
            scope = storeScope,
            produceFile = { settingsFile },
        )
        val environment = AndroidLocalDataUpgradeEnvironment(
            context = context,
            settings = Lazy { dataStore },
            credentials = Lazy { InMemorySecureRefreshTokenStore() },
            storage = storage,
        )
        val gate = DefaultLocalDataGate(
            currentContractVersion = 6,
            minimumMigratableContractVersion = 1,
            steps = setOf(
                CustomItemClientUuidIndexUpgradeStep(
                    storage.database,
                    SettingsDataSource(dataStore),
                ),
                OutboxRetirementUpgradeStep(storage.database),
                CausalRoomUpgradeStep(storage.database, storage.recordMedia, context.filesDir),
                FinalCausalRoomUpgradeStep(storage.database),
                MediaSha256ColumnUpgradeStep(storage.database),
            ),
            environment = environment,
        )

        val ready = gate.ensureReady()

        assertWithMessage(gate.diagnosticReport()).that(ready).isTrue()
        assertThat(gate.state.value).isEqualTo(LocalDataUpgradeState.Ready(6))
        val room = Room.databaseBuilder(
            context,
            LeziDatabase::class.java,
            DATABASE_NAME,
        ).build()
        openedDatabase = room
        assertThat(room.recordDao().getByClientUuid(RECORD_UUID)?.syncDirty).isTrue()
        assertThat(room.babyDao().getByClientUuid(BABY_UUID)?.syncDirty).isFalse()
        val tables = buildSet {
            room.openHelper.readableDatabase.query(
                "SELECT name FROM sqlite_master WHERE type = 'table'",
            ).use { cursor ->
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        assertThat(tables).doesNotContain("outbox")
        assertThat(tables).contains("wake_observations")
        assertThat(retainedMedia.readBytes().toList())
            .containsExactlyElementsIn(byteArrayOf(1, 2, 3, 4).toList())
            .inOrder()

        // Simulate process death after migrate/verify but before the contract marker commit.
        openedDatabase?.close()
        openedDatabase = null
        val retry = FinalCausalRoomUpgradeStep(storage.database)
        retry.migrate()
        retry.verify()

        val reopened = reopenRoomAndAssertQuickCheck()
        assertThat(reopened.recordDao().getByClientUuid(RECORD_UUID)?.syncDirty).isTrue()
    }

    @Test
    fun room26FixtureMigratesTo28PreservingDirtyCareMediaAndClosedSleepWake() = runBlocking {
        migrationHelper.createDatabase(DATABASE_NAME, 26).apply {
            execSQL(
                """
                INSERT INTO local_users(id, displayName, deviceId, createdAt)
                VALUES(1, '家长', 'device-fixture', 1)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO families(id, ownerUserId, createdAt) VALUES(1, 1, 1)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO memberships(familyId, userId, role, status, joinedAt)
                VALUES(1, 1, 'owner', 'active', 1)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO babies(
                    id, familyId, nickname, birthdayEpochDay, themeColorArgb, sortOrder,
                    clientUuid, updatedAt, syncDirty, familyAuthority
                ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(1L, 1L, "本机宝宝", 20_000L, 0L, 0L, BABY_UUID, 100L, 1, 0),
            )
            execSQL(
                """
                INSERT INTO custom_items(
                    id, clientUuid, familyId, name, iconSlot, sortOrder, updatedAt,
                    deletedAt, createdByMembershipId, syncDirty
                ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(
                    7L,
                    CUSTOM_ITEM_UUID,
                    1L,
                    "本机用药",
                    2,
                    0,
                    105L,
                    104L,
                    "membership-a",
                    1,
                ),
            )
            execSQL(
                """
                INSERT INTO care_plans(
                    id, clientUuid, babyId, type, customItemId, scheduledAt,
                    scheduledZoneId, note, payloadJson, schemaVersion, status,
                    createdByMembershipId, updatedAt, syncDirty
                ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(
                    8L,
                    CARE_PLAN_UUID,
                    1L,
                    "medicine",
                    7L,
                    5_000L,
                    "Asia/Shanghai",
                    "本机计划",
                    "{\"name\":\"维生素D\",\"dose\":\"1滴\"}",
                    2,
                    "pending",
                    "membership-a",
                    110L,
                    1,
                ),
            )
            execSQL(
                """
                INSERT INTO records(
                    id, clientUuid, babyId, type, timestamp, endTimestamp, note, payloadJson,
                    schemaVersion, updatedAt, syncDirty, createdByMembershipId,
                    familyPublishedUpdatedAt
                ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(
                    1L,
                    CLOSED_SLEEP_UUID,
                    1L,
                    "sleep",
                    1_000L,
                    2_000L,
                    "slept well",
                    "{\"is_nap\":false}",
                    2,
                    1_700_000_000_000L,
                    1,
                    "membership-a",
                    1_700_000_000_000L,
                ),
            )
            execSQL(
                """
                INSERT INTO records(
                    id, clientUuid, babyId, type, timestamp, endTimestamp, payloadJson,
                    schemaVersion, updatedAt, syncDirty, createdByMembershipId
                ) VALUES(?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(
                    2L,
                    OPEN_SLEEP_UUID,
                    1L,
                    "sleep",
                    3_000L,
                    "{\"is_nap\":true}",
                    2,
                    130L,
                    0,
                    "membership-a",
                ),
            )
            execSQL(
                """
                INSERT INTO records(
                    id, clientUuid, babyId, type, timestamp, payloadJson, schemaVersion,
                    updatedAt, deletedAt, syncDirty, createdByMembershipId
                ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(
                    3L,
                    TOMBSTONE_UUID,
                    1L,
                    "formula",
                    50L,
                    "{}",
                    2,
                    90L,
                    90L,
                    0,
                    "membership-b",
                ),
            )
            execSQL(
                """
                INSERT INTO media_assets(
                    id, recordId, clientUuid, kind, localUri, byteSize,
                    createdAt, updatedAt, syncDirty
                ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(
                    1L,
                    1L,
                    MEDIA_UUID,
                    "log",
                    "retained-closed-sleep.jpg",
                    4L,
                    120L,
                    120L,
                    1,
                ),
            )
            close()
        }
        val retainedMedia = File(storage.recordMedia, "retained-closed-sleep.jpg").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        val dataStore = PreferenceDataStoreFactory.create(
            scope = storeScope,
            produceFile = { settingsFile },
        )
        val environment = AndroidLocalDataUpgradeEnvironment(
            context = context,
            settings = Lazy { dataStore },
            credentials = Lazy { InMemorySecureRefreshTokenStore() },
            storage = storage,
        )
        val gate = DefaultLocalDataGate(
            currentContractVersion = 6,
            minimumMigratableContractVersion = 1,
            steps = setOf(
                CausalRoomUpgradeStep(storage.database, storage.recordMedia, context.filesDir),
                FinalCausalRoomUpgradeStep(storage.database),
                MediaSha256ColumnUpgradeStep(storage.database),
            ),
            environment = environment,
        )

        val ready = gate.ensureReady()
        assertWithMessage(gate.diagnosticReport()).that(ready).isTrue()
        assertThat(gate.state.value).isEqualTo(LocalDataUpgradeState.Ready(6))

        val room = Room.databaseBuilder(
            context,
            LeziDatabase::class.java,
            DATABASE_NAME,
        ).build()
        openedDatabase = room

        assertThat(room.babyDao().getByClientUuid(BABY_UUID)?.familyAuthority).isFalse()
        assertThat(room.babyDao().getByClientUuid(BABY_UUID)?.syncDirty).isTrue()
        assertThat(room.babyDao().getByClientUuid(BABY_UUID)?.baseVersion).isNull()
        val customItem = room.customItemDao().getByClientUuid(CUSTOM_ITEM_UUID)
        assertThat(customItem?.name).isEqualTo("本机用药")
        assertThat(customItem?.deletedAt).isEqualTo(104L)
        assertThat(customItem?.syncDirty).isTrue()
        assertThat(customItem?.baseVersion).isNull()
        val carePlan = room.carePlanDao().getByClientUuid(CARE_PLAN_UUID)
        assertThat(carePlan?.note).isEqualTo("本机计划")
        assertThat(carePlan?.customItemId).isEqualTo(7L)
        assertThat(carePlan?.syncDirty).isTrue()
        assertThat(carePlan?.baseVersion).isNull()
        // Dual-compat: WakeObservation is projected while denormalized endTimestamp
        // remains only as historical closed-sleep compatibility evidence.
        assertThat(room.recordDao().getByClientUuid(CLOSED_SLEEP_UUID)?.endTimestamp)
            .isEqualTo(2_000L)
        assertThat(room.recordDao().getByClientUuid(CLOSED_SLEEP_UUID)?.syncDirty).isTrue()
        assertThat(room.recordDao().getByClientUuid(CLOSED_SLEEP_UUID)?.note)
            .isEqualTo("slept well")
        val expectedWake = com.lezi.babylog.core.common.wakeObservationClientUuid(
            CLOSED_SLEEP_UUID,
            1_700_000_000_000L,
        )
        assertThat(
            room.recordDao().getByClientUuid(CLOSED_SLEEP_UUID)
                ?.effectiveWakeObservationClientUuid,
        ).isEqualTo(expectedWake)
        val wake = room.wakeObservationDao().getByClientUuid(expectedWake)
        assertThat(wake).isNotNull()
        assertThat(wake!!.wakeTimestamp).isEqualTo(2_000L)
        assertThat(wake.note).isEqualTo("slept well")
        assertThat(wake.observerMembershipId).isEqualTo("membership-a")
        assertThat(wake.syncDirty).isTrue()
        assertThat(wake.sleepRecordClientUuid).isEqualTo(CLOSED_SLEEP_UUID)
        // Closed-with-effective-wake must not appear as open (heal/UI contract).
        assertThat(
            room.timelineWindowDao().loadOpenSleepProjection(
                room.babyDao().getByClientUuid(BABY_UUID)!!.id,
            ).map { it.root.clientUuid },
        ).containsExactly(OPEN_SLEEP_UUID)

        // Open sleep stays open — no wake, no auto-close.
        assertThat(room.recordDao().getByClientUuid(OPEN_SLEEP_UUID)?.endTimestamp).isNull()
        assertThat(
            room.recordDao().getByClientUuid(OPEN_SLEEP_UUID)
                ?.effectiveWakeObservationClientUuid,
        ).isNull()
        assertThat(room.wakeObservationDao().listForSleep(OPEN_SLEEP_UUID)).isEmpty()

        // Historical tombstone stays hidden (deletedAt set, no recovery surface).
        assertThat(room.recordDao().getByClientUuid(TOMBSTONE_UUID)?.deletedAt).isEqualTo(90L)

        assertThat(room.mediaAssetDao().getByClientUuid(MEDIA_UUID)?.deletedAt).isNotNull()
        val fixtureBytes = byteArrayOf(1, 2, 3, 4)
        val expectedSha256 = java.security.MessageDigest.getInstance("SHA-256")
            .digest(fixtureBytes)
            .joinToString("") { b -> "%02x".format(b) }
        val expectedWakeMediaUuid = com.lezi.babylog.core.common.wakeMediaUuid(
            CLOSED_SLEEP_UUID,
            MEDIA_UUID,
            expectedSha256,
        )
        val wakeMedia = room.mediaAssetDao().listAllIncludingDeleted()
            .filter { it.kind == "wake" }
        assertThat(wakeMedia).isNotEmpty()
        assertThat(wakeMedia.first().clientUuid).isEqualTo(expectedWakeMediaUuid)
        assertThat(wakeMedia.first().localUri).isEqualTo("retained-closed-sleep.jpg")
        assertThat(wakeMedia.first().syncDirty).isTrue()
        assertThat(wakeMedia.first().baseVersion).isNull()
        assertThat(room.mediaReferenceDao().listForMedia(expectedWakeMediaUuid)).isNotEmpty()
        assertThat(retainedMedia.readBytes().toList())
            .containsExactlyElementsIn(fixtureBytes.toList())
            .inOrder()
        assertThat(room.localUserDao().get()?.deviceId).isEqualTo("device-fixture")
        assertThat(room.membershipDao().listForFamily(1L)).hasSize(1)

        val reopened = reopenRoomAndAssertQuickCheck()
        assertThat(reopened.recordDao().getByClientUuid(TOMBSTONE_UUID)?.deletedAt)
            .isEqualTo(90L)
    }

    private companion object {
        const val DATABASE_NAME = "local-data-contract-migration.db"
        const val SETTINGS_STORE_NAME = "local_data_contract_migration_settings"
        const val CLIENT_UUID = "11111111-1111-4111-8111-111111111111"
        const val BABY_UUID = "22222222-2222-4222-8222-222222222222"
        const val RECORD_UUID = "33333333-3333-4333-8333-333333333333"
        const val MEDIA_UUID = "44444444-4444-4444-8444-444444444444"
        const val CLOSED_SLEEP_UUID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        const val OPEN_SLEEP_UUID = "55555555-5555-4555-8555-555555555555"
        const val TOMBSTONE_UUID = "66666666-6666-4666-8666-666666666666"
        const val CUSTOM_ITEM_UUID = "77777777-7777-4777-8777-777777777777"
        const val CARE_PLAN_UUID = "88888888-8888-4888-8888-888888888888"
    }

    private fun reopenRoomAndAssertQuickCheck(): LeziDatabase {
        openedDatabase?.close()
        val reopened = Room.databaseBuilder(
            context,
            LeziDatabase::class.java,
            DATABASE_NAME,
        ).build()
        openedDatabase = reopened
        reopened.openHelper.readableDatabase.query("PRAGMA quick_check").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("ok")
        }
        return reopened
    }

    private class CatalogMigrationStep(
        override val fromContractVersion: Int,
        override val toContractVersion: Int,
    ) : com.lezi.babylog.core.common.LocalDataUpgradeStep {
        override val affectedDomains = emptySet<com.lezi.babylog.core.common.LocalDataDomain>()

        override suspend fun migrate() = Unit

        override suspend fun verify() = Unit
    }
}
