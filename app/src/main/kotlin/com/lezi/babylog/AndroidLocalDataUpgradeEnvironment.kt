package com.lezi.babylog

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.AtomicFile
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.lezi.babylog.core.common.LocalDataDomain
import com.lezi.babylog.core.common.LocalDataInspection
import com.lezi.babylog.core.common.LocalDataUpgradeBlockReason
import com.lezi.babylog.core.common.LocalDataUpgradeEnvironment
import com.lezi.babylog.core.common.LocalDataUpgradeFailure
import com.lezi.babylog.core.common.LocalDataUpgradeStep
import com.lezi.babylog.sync.SecureRefreshTokenStore
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Properties
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

@Singleton
internal class AndroidLocalDataUpgradeEnvironment @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: Lazy<DataStore<Preferences>>,
    private val credentials: Lazy<SecureRefreshTokenStore>,
) : LocalDataUpgradeEnvironment {
    private val dataRoot = File(context.applicationInfo.dataDir)
    private val database = context.getDatabasePath(DATABASE_NAME)
    private val upgradeRoot = File(context.noBackupFilesDir, UPGRADE_DIRECTORY)
    private val roomSchemasByContract = BuildConfig.LOCAL_DATA_CONTRACT_ROOM_SCHEMAS
        .split(',')
        .associate { entry ->
            val (contract, schema) = entry.split(':', limit = 2)
            contract.toInt() to schema.toInt()
        }
    private val marker = AtomicFile(File(upgradeRoot, CONTRACT_MARKER_FILE))
    private val snapshotStore = LocalDataSnapshotStore(
        snapshotRoot = upgradeRoot,
        inventory = LocalDataFileInventory(
            dataRoot = dataRoot,
            sources = mapOf(
                LocalDataDomain.Room to listOf(
                    database,
                    File(database.path + "-wal"),
                    File(database.path + "-shm"),
                ),
                LocalDataDomain.Settings to listOf(
                    File(context.filesDir, "datastore/lezi_settings.preferences_pb"),
                    File(dataRoot, "shared_prefs/care_widget_state_v2.xml"),
                ),
                LocalDataDomain.Credentials to listOf(
                    File(dataRoot, "shared_prefs/lezi_secure_family.xml"),
                ),
                LocalDataDomain.Media to listOf(
                    File(context.filesDir, "record-media"),
                    File(context.filesDir, "baby_avatars"),
                ),
            ),
        ),
    )
    @Volatile private var lastObservedRoomSchema: Int? = null
    @Volatile private var lastObservedContract: Int? = null

    override suspend fun inspect(): LocalDataInspection {
        val markerVersion = readContractMarker()
        val roomFilesWithoutDatabase = listOf(
            File(database.path + "-wal"),
            File(database.path + "-shm"),
        ).any(File::exists)
        if (!database.exists() && roomFilesWithoutDatabase) {
            throw LocalDataUpgradeFailure(
                LocalDataUpgradeBlockReason.InconsistentData,
                "数据库主文件缺失，但仍存在 WAL/SHM 文件",
            )
        }
        val roomSchema = database.takeIf(File::exists)?.readSqliteUserVersion()
        lastObservedRoomSchema = roomSchema
        val pendingTransition = snapshotStore.pendingVerifiedTransition()

        val inspection = detectLocalDataInspection(
            markerVersion = markerVersion,
            roomSchema = roomSchema,
            currentContractVersion = BuildConfig.LOCAL_DATA_CONTRACT_VERSION,
            roomSchemasByContract = roomSchemasByContract,
            pendingTransition = pendingTransition,
        )
        val contractVersion = inspection.contractVersion
        lastObservedContract = contractVersion
        return inspection
    }

    override suspend fun prepareSnapshot(step: LocalDataUpgradeStep) {
        snapshotStore.prepare(
            fromContractVersion = step.fromContractVersion,
            toContractVersion = step.toContractVersion,
            domains = step.affectedDomains,
        )
    }

    override suspend fun commitContract(contractVersion: Int) {
        upgradeRoot.mkdirs()
        val properties = Properties().apply {
            setProperty("contract_version", contractVersion.toString())
        }
        val output = marker.startWrite()
        try {
            properties.store(output, null)
            output.fd.sync()
            marker.finishWrite(output)
            lastObservedContract = contractVersion
        } catch (failure: Throwable) {
            marker.failWrite(output)
            throw failure
        }
    }

    override suspend fun cleanupSnapshots() {
        snapshotStore.cleanupVerifiedSnapshots()
    }

    override suspend fun verifyCurrent() {
        if (database.exists()) {
            val currentRoomSchema = roomSchemasByContract.getValue(
                BuildConfig.LOCAL_DATA_CONTRACT_VERSION,
            )
            val schema = database.readSqliteUserVersion()
            if (schema != currentRoomSchema) {
                throw LocalDataUpgradeFailure(
                    LocalDataUpgradeBlockReason.VerificationFailed,
                    "Room schema 校验失败：期望 $currentRoomSchema，实际 $schema",
                )
            }
            SQLiteDatabase.openDatabase(
                database.path,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            ).use { sqlite ->
                val quickCheck = sqlite.rawQuery("PRAGMA quick_check", null).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else "missing-result"
                }
                if (quickCheck != "ok") {
                    throw LocalDataUpgradeFailure(
                        LocalDataUpgradeBlockReason.VerificationFailed,
                        "Room quick_check 失败：$quickCheck",
                    )
                }
            }
        }
        settings.get().data.first()
        val securePreferences = File(dataRoot, "shared_prefs/lezi_secure_family.xml")
        if (securePreferences.exists()) credentials.get().verifyReadable()
        val widgetPreferences = File(dataRoot, "shared_prefs/care_widget_state_v2.xml")
        if (widgetPreferences.exists()) {
            context.getSharedPreferences("care_widget_state_v2", Context.MODE_PRIVATE).all
        }
    }

    override fun diagnosticContext(): String = buildString {
        append("app_version=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})")
        append(",target_contract=${BuildConfig.LOCAL_DATA_CONTRACT_VERSION}")
        append(",minimum_contract=${BuildConfig.MINIMUM_MIGRATABLE_LOCAL_DATA_CONTRACT_VERSION}")
        append(",observed_contract=${lastObservedContract ?: "unknown"}")
        append(",room_schema=${lastObservedRoomSchema ?: "none"}")
        append(',')
        append(snapshotStore.journalSummary())
    }

    private fun readContractMarker(): Int? {
        if (!marker.baseFile.isFile) return null
        return try {
            Properties().apply { marker.openRead().use(::load) }
                .getProperty("contract_version")
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
                ?: throw IllegalStateException("本地数据契约标记无效")
        } catch (failure: Throwable) {
            throw LocalDataUpgradeFailure(
                LocalDataUpgradeBlockReason.InconsistentData,
                failure.message.orEmpty().ifBlank { "本地数据契约标记无法读取" },
                failure,
            )
        }
    }

    private companion object {
        const val DATABASE_NAME = "lezi.db"
        const val UPGRADE_DIRECTORY = "local-data-upgrade"
        const val CONTRACT_MARKER_FILE = "contract.properties"
    }
}

internal fun detectLocalDataInspection(
    markerVersion: Int?,
    roomSchema: Int?,
    currentContractVersion: Int,
    roomSchemasByContract: Map<Int, Int>,
    pendingTransition: PendingLocalDataTransition? = null,
): LocalDataInspection {
    val currentRoomSchema = roomSchemasByContract[currentContractVersion]
        ?: throw LocalDataUpgradeFailure(
            LocalDataUpgradeBlockReason.InconsistentData,
            "当前本地数据契约未声明 Room schema",
        )
    if (pendingTransition != null) {
        val from = pendingTransition.fromContractVersion
        val to = pendingTransition.toContractVersion
        val transitionIsDeclared = to == from + 1 &&
            to <= currentContractVersion &&
            roomSchemasByContract.containsKey(from) &&
            roomSchemasByContract.containsKey(to)
        if (!transitionIsDeclared) {
            throw LocalDataUpgradeFailure(
                LocalDataUpgradeBlockReason.InconsistentData,
                "迁移日志引用了未声明的本地数据契约 $from->$to",
            )
        }
        if (markerVersion == null || markerVersion == from) {
            // The migration step is required to be idempotent. Its verified pre-write
            // snapshot lets the coordinator safely retry even if a prior process died
            // after partially mutating one or more persistence domains.
            return LocalDataInspection(contractVersion = from)
        }
    }

    if (markerVersion != null) {
        val expectedRoomSchema = roomSchemasByContract[markerVersion]
        if (markerVersion <= currentContractVersion && expectedRoomSchema == null) {
            throw LocalDataUpgradeFailure(
                LocalDataUpgradeBlockReason.InconsistentData,
                "本地数据契约 $markerVersion 未在账本中声明",
            )
        }
        if (roomSchema != null && expectedRoomSchema != null && roomSchema != expectedRoomSchema) {
            throw LocalDataUpgradeFailure(
                LocalDataUpgradeBlockReason.InconsistentData,
                "契约标记为 $markerVersion，但 Room schema 为 $roomSchema",
            )
        }
        return LocalDataInspection(contractVersion = markerVersion)
    }

    val inferredContract = when {
        roomSchema == null -> currentContractVersion
        else -> {
            val matchingContracts = roomSchemasByContract
                .filterValues { it == roomSchema }
                .keys
                .filter { it <= currentContractVersion }
            when {
                matchingContracts.isNotEmpty() -> matchingContracts.min()
                roomSchema > currentRoomSchema -> currentContractVersion + 1
                else -> 0
            }
        }
    }
    return LocalDataInspection(
        contractVersion = inferredContract,
        baselineMarkerRequired = inferredContract == currentContractVersion,
    )
}

internal fun File.readSqliteUserVersion(): Int {
    return try {
        SQLiteDatabase.openDatabase(
            path,
            null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        ).use(SQLiteDatabase::getVersion)
    } catch (failure: Throwable) {
        throw LocalDataUpgradeFailure(
            LocalDataUpgradeBlockReason.InconsistentData,
            failure.message.orEmpty().ifBlank { "无法只读检查数据库" },
            failure,
        )
    }
}
