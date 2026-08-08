package com.lezi.babylog

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
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
            "https://192.168.50.4:8765",
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
                serverHost = "192.168.50.4",
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
            currentContractVersion = 3,
            minimumMigratableContractVersion = 1,
            steps = setOf(
                customItemStep,
                OutboxRetirementUpgradeStep(storage.database),
            ),
            environment = environment,
        )

        val ready = gate.ensureReady()
        assertWithMessage(gate.diagnosticReport()).that(ready).isTrue()
        assertThat(gate.state.value).isEqualTo(LocalDataUpgradeState.Ready(3))

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
        assertThat(room.mediaAssetDao().getByClientUuid(MEDIA_UUID)?.localUri)
            .isEqualTo("retained-contract-one.jpg")
        assertThat(retainedMedia.readBytes().toList())
            .containsExactlyElementsIn(byteArrayOf(1, 2, 3, 4).toList())
            .inOrder()
        assertThat(syncPreferences.session.first().familyId).isEqualTo("family-a")
        assertThat(syncPreferences.session.first().membershipId).isEqualTo("membership-a")
        assertThat(syncPreferences.verifiedEndpoint.first()).isEqualTo(endpoint)
        assertThat(credentialStore.getToken()).isEqualTo("refresh-a")
        assertThat(retainedCredentialFile.readBytes()).isEqualTo(retainedCredentialBytes)
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
            currentContractVersion = 3,
            minimumMigratableContractVersion = 1,
            steps = setOf(
                CatalogMigrationStep(1, 2),
                CatalogMigrationStep(2, 3),
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
                currentContractVersion = 3,
                roomSchemasByContract = mapOf(1 to 24, 2 to 25, 3 to 26),
            )
            val plan = planner.planFrom(inspection.contractVersion)
            if (contract == 3) {
                assertThat(plan).isEqualTo(LocalDataUpgradePlan.Ready)
            } else {
                assertThat((plan as LocalDataUpgradePlan.Upgrade).steps.last().toContractVersion)
                    .isEqualTo(3)
            }
        }

        assertThat(versionCodes).containsExactlyElementsIn(6..18).inOrder()
        assertThat(observedBoundaries).containsExactly(1 to 24, 2 to 25, 3 to 26)
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
            currentContractVersion = 3,
            minimumMigratableContractVersion = 1,
            steps = setOf(
                CustomItemClientUuidIndexUpgradeStep(
                    storage.database,
                    SettingsDataSource(dataStore),
                ),
                OutboxRetirementUpgradeStep(storage.database),
            ),
            environment = environment,
        )

        val ready = gate.ensureReady()

        assertWithMessage(gate.diagnosticReport()).that(ready).isTrue()
        assertThat(gate.state.value).isEqualTo(LocalDataUpgradeState.Ready(3))
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
        assertThat(retainedMedia.readBytes().toList())
            .containsExactlyElementsIn(byteArrayOf(1, 2, 3, 4).toList())
            .inOrder()

        // Simulate process death after migrate/verify but before the contract marker commit.
        openedDatabase?.close()
        openedDatabase = null
        val retry = OutboxRetirementUpgradeStep(storage.database)
        retry.migrate()
        retry.verify()
    }

    @Test
    fun released036Room26DirtyShapeOpensIn037WithoutWipingCareOrMedia() = runBlocking {
        migrationHelper.createDatabase(DATABASE_NAME, 26).apply {
            execSQL(
                """
                INSERT INTO babies(
                    id, familyId, nickname, birthdayEpochDay, themeColorArgb, sortOrder,
                    clientUuid, updatedAt, syncDirty, familyAuthority
                ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(1L, 7L, "本机宝宝", 20_000L, 0L, 0L, BABY_UUID, 100L, 1, 0),
            )
            execSQL(
                """
                INSERT INTO records(
                    id, clientUuid, babyId, type, timestamp, payloadJson, schemaVersion,
                    updatedAt, syncDirty, createdByMembershipId
                ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(1L, RECORD_UUID, 1L, "formula", 100L, "{\"amount_ml\":80}", 2, 120L, 1, ""),
            )
            execSQL(
                """
                INSERT INTO media_assets(
                    id, recordId, clientUuid, kind, localUri, byteSize,
                    createdAt, updatedAt, syncDirty
                ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(1L, 1L, MEDIA_UUID, "log", "retained-036.jpg", 4L, 120L, 120L, 1),
            )
            close()
        }
        val retainedMedia = File(storage.recordMedia, "retained-036.jpg").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }

        val room = Room.databaseBuilder(
            context,
            LeziDatabase::class.java,
            DATABASE_NAME,
        ).build()
        openedDatabase = room

        assertThat(room.babyDao().getByClientUuid(BABY_UUID)?.familyAuthority).isFalse()
        assertThat(room.babyDao().getByClientUuid(BABY_UUID)?.syncDirty).isTrue()
        assertThat(room.recordDao().getByClientUuid(RECORD_UUID)?.payloadJson)
            .isEqualTo("{\"amount_ml\":80}")
        assertThat(room.mediaAssetDao().getByClientUuid(MEDIA_UUID)?.localUri)
            .isEqualTo("retained-036.jpg")
        assertThat(room.pendingPublishDao().observeCount().first()).isEqualTo(2)
        assertThat(retainedMedia.readBytes().toList())
            .containsExactlyElementsIn(byteArrayOf(1, 2, 3, 4).toList())
            .inOrder()
    }

    private companion object {
        const val DATABASE_NAME = "local-data-contract-migration.db"
        const val SETTINGS_STORE_NAME = "local_data_contract_migration_settings"
        const val CLIENT_UUID = "11111111-1111-4111-8111-111111111111"
        const val BABY_UUID = "22222222-2222-4222-8222-222222222222"
        const val RECORD_UUID = "33333333-3333-4333-8333-333333333333"
        const val MEDIA_UUID = "44444444-4444-4444-8444-444444444444"
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
