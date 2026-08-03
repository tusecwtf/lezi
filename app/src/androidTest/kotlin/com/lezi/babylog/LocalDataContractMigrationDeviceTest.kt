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
import com.lezi.babylog.core.common.LocalDataUpgradeState
import com.lezi.babylog.core.database.LeziDatabase
import com.lezi.babylog.core.datastore.SettingsDataSource
import com.lezi.babylog.core.model.DeviceLayoutSnapshot
import com.lezi.babylog.core.model.deviceLayoutSnapshot
import com.lezi.babylog.sync.session.InMemorySecureRefreshTokenStore
import dagger.Lazy
import java.io.File
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
    fun contractOneGateMigratesRealRoom24DatabaseAndRoom25Opens() = runBlocking {
        migrationHelper.createDatabase(DATABASE_NAME, 24).apply {
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
        val dataStore = PreferenceDataStoreFactory.create(
            scope = storeScope,
            produceFile = { settingsFile },
        )
        val settings = SettingsDataSource(dataStore)
        settings.setDeviceLayoutSnapshot(
            DeviceLayoutSnapshot(
                quickRecordSlots = listOf("custom:1", "custom:999", "pee", ""),
                hiddenItems = setOf("custom:1", "custom:999", "sleep"),
                itemOrderJson = """["custom:999","pee","custom:1"]""",
            ),
        )
        val step = CustomItemClientUuidIndexUpgradeStep(storage.database, settings)
        val environment = AndroidLocalDataUpgradeEnvironment(
            context = context,
            settings = Lazy { dataStore },
            credentials = Lazy { InMemorySecureRefreshTokenStore() },
            storage = storage,
        )
        val gate = DefaultLocalDataGate(
            currentContractVersion = 2,
            minimumMigratableContractVersion = 1,
            steps = setOf(step),
            environment = environment,
        )

        val ready = gate.ensureReady()
        assertWithMessage(gate.diagnosticReport()).that(ready).isTrue()
        assertThat(gate.state.value).isEqualTo(LocalDataUpgradeState.Ready(2))

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
    }

    private companion object {
        const val DATABASE_NAME = "local-data-contract-migration.db"
        const val SETTINGS_STORE_NAME = "local_data_contract_migration_settings"
        const val CLIENT_UUID = "11111111-1111-4111-8111-111111111111"
    }
}
