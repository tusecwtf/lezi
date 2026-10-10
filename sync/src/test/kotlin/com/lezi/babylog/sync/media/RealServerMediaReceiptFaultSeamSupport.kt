package com.lezi.babylog.sync.media

import com.lezi.babylog.core.database.FamilyEntity
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.MediaLocalPathGate
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.database.fulfillment.FulfillmentAuthoritySettlement
import com.lezi.babylog.sync.IsolatedLeziSyncServer
import com.lezi.babylog.sync.MemoryBabyDao
import com.lezi.babylog.sync.MemoryCarePlanDao
import com.lezi.babylog.sync.MemoryConflictSnapshotCacheDao
import com.lezi.babylog.sync.MemoryConflictSummaryDao
import com.lezi.babylog.sync.MemoryCustomItemDao
import com.lezi.babylog.sync.MemoryFamilyDao
import com.lezi.babylog.sync.MemoryFulfillmentCandidateDao
import com.lezi.babylog.sync.MemoryMediaDao
import com.lezi.babylog.sync.MemoryMediaReferenceDao
import com.lezi.babylog.sync.MemoryRecordDao
import com.lezi.babylog.sync.MemorySourceRelationDao
import com.lezi.babylog.sync.MemorySyncPreferences
import com.lezi.babylog.sync.MemoryWakeObservationDao
import com.lezi.babylog.sync.RecordingTransactionRunner
import com.lezi.babylog.sync.TestImmutableMediaSpool
import com.lezi.babylog.sync.TestMediaFileStore
import com.lezi.babylog.sync.backend.HttpSyncBackend
import com.lezi.babylog.sync.backend.SyncHttpConnectionFactory
import com.lezi.babylog.sync.backend.TrustedEndpointResolver
import com.lezi.babylog.sync.engine.CarePlanFamilyAppliedListener
import com.lezi.babylog.sync.engine.FamilyBabyAuthorityAppliedListener
import com.lezi.babylog.sync.engine.ReplicaSyncEngine
import com.lezi.babylog.sync.engine.localReplicaBaby
import com.lezi.babylog.sync.session.PolicyClock
import com.lezi.babylog.sync.session.SyncSession
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

internal class RealServerMediaReceiptFaultFixture private constructor(
    val server: IsolatedLeziSyncServer,
    val proxy: DeterministicHttpsFaultProxy,
    val backend: HttpSyncBackend,
    val preferences: MemorySyncPreferences,
    var session: SyncSession,
) : AutoCloseable {
    val records = MemoryRecordDao()
    val carePlans = MemoryCarePlanDao()
    val fulfillmentCandidates = MemoryFulfillmentCandidateDao()
    val babies = MemoryBabyDao()
    val media = MemoryMediaDao()
    val customItems = MemoryCustomItemDao()
    val mediaFiles = object : TestMediaFileStore() {
        override suspend fun prepareUpload(localUri: String): PreparedMedia {
            val prepared = super.prepareUpload(localUri)
            return PreparedMedia(
                file = prepared.file,
                mime = prepared.mime,
                width = prepared.width ?: 10,
                height = prepared.height ?: 10,
            )
        }
    }
    val immutableMediaSpool = TestImmutableMediaSpool(mediaFiles)
    val transactions = RecordingTransactionRunner()
    val fulfillmentAuthoritySettlement = FulfillmentAuthoritySettlement(
        carePlanDao = carePlans,
        fulfillmentCandidateDao = fulfillmentCandidates,
        transactionRunner = transactions,
    )
    val mediaFileCleanup = ReferenceAwareMediaFileCleanup(
        mediaDao = media,
        mediaReferenceDao = MemoryMediaReferenceDao(),
        mediaFiles = mediaFiles,
        transactionRunner = transactions,
        pathGate = MediaLocalPathGate(),
    )
    val families = MemoryFamilyDao()
    val wakeObservations = MemoryWakeObservationDao()
    val conflictSummaries = MemoryConflictSummaryDao()
    val conflictDetails = MemoryConflictSnapshotCacheDao()
    val sourceRelations = MemorySourceRelationDao()
    val engine = newEngine()
    var babyId: Long = 0
    var baselinePrepareForwards: Int = 0
    var baselineCommitForwards: Int = 0

    fun prepareForwards(): Int = proxy.prepareForwards.get() - baselinePrepareForwards

    fun commitForwards(): Int = proxy.commitForwards.get() - baselineCommitForwards

    fun newEngine() = ReplicaSyncEngine(
        backend = backend,
        preferences = preferences,
        recordDao = records,
        carePlanDao = carePlans,
        babyDao = babies,
        mediaDao = media,
        customItemDao = customItems,
        familyDao = families,
        clock = object : PolicyClock {
            override fun nowMillis(): Long = System.currentTimeMillis()
        },
        mediaFiles = mediaFiles,
        immutableMediaSpool = immutableMediaSpool,
        mediaFileCleanup = mediaFileCleanup,
        transactionRunner = transactions,
        carePlanAppliedListener = CarePlanFamilyAppliedListener { },
        familyBabyAppliedListener = FamilyBabyAuthorityAppliedListener { },
        fulfillmentCandidateDao = fulfillmentCandidates,
        fulfillmentAuthoritySettlement = fulfillmentAuthoritySettlement,
        requireRemoteAllowed = {},
        wakeObservationDao = wakeObservations,
        conflictSummaryDao = conflictSummaries,
        conflictSnapshotCacheDao = conflictDetails,
        sourceRelationDao = sourceRelations,
    )

    data class SeededMedia(
        val recordUuid: String,
        val mediaUuid: String,
        val localUri: String,
        val frozenBytes: ByteArray,
    )

    fun seedRecordMedia(
        recordUuid: String,
        mediaUuid: String,
        localUri: String,
        bytes: ByteArray,
    ): SeededMedia {
        check(babyId != 0L) { "H44 fixture baby is missing" }
        val recordId = records.seed(
            RecordEntity(
                clientUuid = recordUuid,
                babyId = babyId,
                type = "formula",
                timestamp = 1_700_000_000_000L,
                payloadJson = """{"amount_ml":60}""",
                schemaVersion = 2,
                updatedAt = 1_700_000_000_000L,
                syncDirty = true,
                createdByMembershipId = session.membershipId,
            ),
        )
        mediaFiles.preparedUploadBytes[localUri] = bytes
        media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = mediaUuid,
                kind = "log",
                localUri = localUri,
                createdAt = 1_700_000_000_000L,
                updatedAt = 1_700_000_000_000L,
                syncDirty = true,
            ),
        )
        return SeededMedia(recordUuid, mediaUuid, localUri, bytes)
    }

    fun sqlite(sql: String): String {
        val process = ProcessBuilder(
            resolveSqlite3(),
            "-batch",
            "-noheader",
            server.databaseFile.absolutePath,
            "PRAGMA busy_timeout=5000; $sql",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        check(process.waitFor() == 0) { "sqlite3 failed: $output\n$sql" }
        return output.lineSequence().lastOrNull().orEmpty()
    }

    fun sqliteAll(sql: String): String {
        val process = ProcessBuilder(
            resolveSqlite3(),
            "-batch",
            "-noheader",
            "-separator",
            "|",
            server.databaseFile.absolutePath,
            "PRAGMA busy_timeout=5000; $sql",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        check(process.waitFor() == 0) { "sqlite3 failed: $output\n$sql" }
        return output
    }

    fun stagingCount(status: String? = null): Int {
        val clause = if (status == null) "" else " WHERE status = '$status'"
        return sqlite("SELECT COUNT(*) FROM causal_media_staging$clause;").toInt()
    }

    fun versionCount(): Int = sqlite("SELECT COUNT(*) FROM entity_versions;").toInt()

    fun recordVersionCount(): Int =
        sqlite("SELECT COUNT(*) FROM entity_versions WHERE entity_type = 'record';").toInt()

    fun receiptCount(): Int = sqlite("SELECT COUNT(*) FROM mutation_receipts;").toInt()

    fun recordReceiptCount(): Int =
        sqlite("SELECT COUNT(*) FROM mutation_receipts WHERE entity_type = 'record';").toInt()

    fun recordVersionDump(): String = sqliteAll(
        "SELECT version_id, mutation_id, client_uuid, content_hash FROM entity_versions " +
            "WHERE entity_type = 'record' ORDER BY created_at, version_id;",
    )

    fun recordReceiptDump(): String = sqliteAll(
        "SELECT mutation_id, entity_type, client_uuid, content_hash, status FROM mutation_receipts " +
            "WHERE entity_type = 'record' ORDER BY mutation_id;",
    )

    fun stagingSha256(mediaUuid: String): String =
        sqlite("SELECT sha256 FROM causal_media_staging WHERE media_uuid = '$mediaUuid';")

    override fun close() {
        proxy.close()
        server.close()
    }

    companion object {
        suspend fun open(): RealServerMediaReceiptFaultFixture {
            assumeToolsPresent()
            val server = IsolatedLeziSyncServer.start()
            val proxy = try {
                DeterministicHttpsFaultProxy.start(server)
            } catch (error: Throwable) {
                server.close()
                throw error
            }
            return try {
                val endpoint = proxy.endpoint
                val backend = HttpSyncBackend(
                    connectionFactory = SyncHttpConnectionFactory { url: URL ->
                        url.openConnection() as HttpURLConnection
                    },
                    trustedEndpointResolver = TrustedEndpointResolver { endpoint },
                    clientVersionCode = 35,
                )
                val created = backend.create(
                    baseUrl = proxy.origin,
                    deviceId = "H44 Owner Phone",
                    displayName = "妈妈",
                    createRequestId = "44444444-4444-4444-8444-444444444444",
                    bootstrapSecret = server.bootstrapSecret,
                    familyName = "H44验收家庭",
                )
                val session = SyncSession(
                    familyId = created.familyId,
                    accessToken = created.accessToken,
                    refreshToken = created.refreshToken,
                    accessExpiresAtEpochSeconds = created.accessExpiresAtEpochSeconds,
                    deviceId = created.deviceId,
                    role = created.role,
                    pullGeneration = created.generation,
                    membershipId = created.membershipId,
                    familyName = created.familyName,
                    serverHost = "127.0.0.1",
                    serverPort = proxy.listenPort,
                    serverScheme = "https",
                )
                val preferences = MemorySyncPreferences(session)
                preferences.rememberEndpoint(endpoint)
                val fixture = RealServerMediaReceiptFaultFixture(
                    server = server,
                    proxy = proxy,
                    backend = backend,
                    preferences = preferences,
                    session = session,
                )
                fixture.families.seed(FamilyEntity(ownerUserId = 1, createdAt = 0))
                fixture.babyId = fixture.babies.seed(
                    localReplicaBaby().copy(
                        nickname = "H44宝宝",
                        clientUuid = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb4",
                        syncDirty = true,
                    ),
                )
                fixture.engine.synchronize(session, com.lezi.babylog.sync.SyncTrigger.LocalWrite)
                check(fixture.babies.get(fixture.babyId)?.syncDirty == false) {
                    "H44 fixture baby did not settle before media cases"
                }
                fixture.session = preferences.current()
                fixture.baselinePrepareForwards = fixture.proxy.prepareForwards.get()
                fixture.baselineCommitForwards = fixture.proxy.commitForwards.get()
                fixture
            } catch (error: Throwable) {
                proxy.close()
                server.close()
                throw error
            }
        }

        private fun assumeToolsPresent() {
            check(ProcessBuilder("openssl", "version").start().waitFor() == 0) { "openssl required" }
            check(ProcessBuilder("curl", "--version").start().waitFor() == 0) { "curl required" }
            resolveSqlite3()
        }

        private fun resolveSqlite3(): String {
            val override = System.getenv("SQLITE3_BIN")?.trim().orEmpty()
            if (override.isNotEmpty()) {
                require(File(override).canExecute())
                return override
            }
            listOf("sqlite3", "/usr/bin/sqlite3").forEach { candidate ->
                if (ProcessBuilder(candidate, "-version").start().waitFor() == 0) return candidate
            }
            error("sqlite3 is required")
        }
    }
}

internal fun h44MediaUuid(suffix: String): String =
    "00000000-0000-4000-8000-${suffix.padStart(12, '0')}"
