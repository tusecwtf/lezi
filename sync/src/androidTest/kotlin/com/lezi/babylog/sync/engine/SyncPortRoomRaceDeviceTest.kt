package com.lezi.babylog.sync.engine

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.*
import com.lezi.babylog.core.database.causal.*
import com.lezi.babylog.core.database.fulfillment.FulfillmentAuthoritySettlement
import com.lezi.babylog.core.model.*
import com.lezi.babylog.sync.*
import com.lezi.babylog.sync.backend.*
import com.lezi.babylog.sync.media.*
import com.lezi.babylog.sync.session.*
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Test
import org.junit.runner.RunWith

/** Public SyncPort with real Room/DataStore/files. Faults sit only at persistence/API boundaries. */
@RunWith(AndroidJUnit4::class)
class SyncPortRoomRaceDeviceTest {
    @Test(timeout = 120_000)
    fun wakeRepairKeepsNewNotePhotoSelectionAndTombstoneAtEveryPendingReadBoundary() = runBlocking {
        for (edit in listOf("note", "photo", "selection", "tombstone")) {
            var reachedEnd = false
            for (boundary in 1..16) {
                val captured = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                var reads = 0
                RoomPortRig.open(recordAdapter = { actual ->
                    object : RecordDao by actual {
                        override suspend fun listPendingSync(): List<RecordEntity> {
                            val snapshot = actual.listPendingSync()
                            if (++reads == boundary) {
                                captured.complete(Unit)
                                release.await()
                            }
                            return snapshot
                        }
                    }
                }).use { rig ->
                    val id = rig.seedSleep()
                    val sync = async(Dispatchers.IO) { rig.port.sync(SyncTrigger.LocalWrite) }
                    try {
                        val paused = select<Boolean> {
                            captured.onAwait { true }
                            sync.onAwait { false }
                        }
                        if (!paused) {
                            sync.await().getOrThrow()
                            reachedEnd = true
                            android.util.Log.i("SyncPortRoomRace", "$edit explored ${boundary - 1} pending-read boundaries")
                            return@use
                        }
                        // Separate coroutine/Room transaction; never inherit a sync transaction.
                        val before = requireNotNull(rig.db.recordDao().getIncludingDeleted(id))
                        val revision = maxOf(System.currentTimeMillis(), before.updatedAt + 10)
                        rig.transactions.run {
                            val current = requireNotNull(rig.db.recordDao().getIncludingDeleted(id))
                            val changed = when (edit) {
                                "note" -> current.copy(note = "new user note")
                                "photo" -> {
                                    val image = rig.photo("new-user-photo.jpg")
                                    rig.db.mediaAssetDao().upsert(MediaAssetEntity(
                                        clientUuid = PHOTO, recordId = id, localUri = image.path,
                                        mime = "image/jpeg", byteSize = image.length(), width = 2, height = 2,
                                        sha256 = digest(image.readBytes()), createdAt = revision, updatedAt = revision,
                                    ))
                                    current.copy(note = "new photo attached")
                                }
                                "selection" -> current.copy(effectiveWakeObservationClientUuid = LIVE_WAKE)
                                else -> current.copy(deletedAt = revision)
                            }
                            rig.db.recordDao().update(changed.copy(updatedAt = revision, syncDirty = true, mutationId = null))
                        }
                        release.complete(Unit)
                        // A stale captured publish epoch may request another round; it may never undo the edit.
                        sync.await()
                        val after = requireNotNull(rig.db.recordDao().getIncludingDeleted(id))
                        assertThat(after.updatedAt).isAtLeast(revision)
                        when (edit) {
                            "note" -> assertThat(after.note).isEqualTo("new user note")
                            "photo" -> {
                                assertThat(after.note).isEqualTo("new photo attached")
                                assertThat(rig.db.mediaAssetDao().getByClientUuid(PHOTO)?.deletedAt).isNull()
                                assertThat(rig.db.mediaAssetDao().getByClientUuid(PHOTO)).isNotNull()
                            }
                            "selection" -> assertThat(after.effectiveWakeObservationClientUuid).isEqualTo(LIVE_WAKE)
                            else -> assertThat(after.deletedAt).isEqualTo(revision)
                        }
                    } finally {
                        release.complete(Unit)
                        if (!sync.isCompleted) sync.cancelAndJoin()
                    }
                }
                if (reachedEnd) break
            }
            check(reachedEnd) { "$edit exceeded 16 pending-read boundaries; scheduling coverage is incomplete" }
        }
    }

    @Test(timeout = 60_000)
    fun rolledBackBranchRetirementKeepsOnlySpoolCopyUntilRoomCommitSucceeds() = runBlocking {
        val inject = AtomicBoolean(false)
        val injected = AtomicBoolean(false)
        RoomPortRig.open(cacheAdapter = { actual ->
            object : ConflictSnapshotCacheDao by actual {
                override suspend fun deleteFrozenMediaSpoolManifest(mutationId: String) {
                    actual.deleteFrozenMediaSpoolManifest(mutationId)
                    if (inject.compareAndSet(true, false)) {
                        injected.set(true)
                        throw IOException("after journal delete before Room commit")
                    }
                }
            }
        }).use { rig ->
            val baby = rig.seedBaby()
            val recordId = rig.db.recordDao().upsert(RecordEntity(
                clientUuid = RECORD, babyId = baby, type = "formula", timestamp = 100,
                payloadJson = """{"amount_ml":120}""", schemaVersion = 2,
                createdByMembershipId = SESSION.membershipId, updatedAt = 100, syncDirty = true,
            ))
            val source = rig.photo("published.jpg")
            rig.db.mediaAssetDao().upsert(MediaAssetEntity(
                clientUuid = PHOTO, recordId = recordId, localUri = source.path,
                remoteUri = SESSION.receiptFor(PHOTO), mime = "image/jpeg",
                byteSize = source.length(), width = 2, height = 2, sha256 = digest(source.readBytes()),
                createdAt = 100, updatedAt = 100,
            ))
            // A published receipt alone cannot authorize reuse. Reconstruct the
            // durable provenance written with this exact canonical local file.
            rig.db.conflictSnapshotCacheDao().putTransportJournal(
                "canonical-media-bytes-v1:$PHOTO", source.path, 100,
            )
            rig.backend.branch = true
            rig.port.sync(SyncTrigger.LocalWrite).getOrThrow()
            val frozen = requireNotNull(rig.backend.lastUnit)
            val before = requireNotNull(rig.spool.recoverGroup(frozen.mutationId)).group
            // Existing family bytes use an empty PUT; its receipt must nevertheless
            // bind the non-empty frozen JPEG, not the HTTP body length zero.
            assertThat(rig.backend.uploadedMedia.single().first).isEqualTo(PHOTO)
            assertThat(rig.backend.uploadedMedia.single().second).isEmpty()
            assertThat(before.items.single().byteSize).isEqualTo(source.length())
            assertThat(before.items.single().sha256).isEqualTo(digest(source.readBytes()))
            val bytes = rig.spool.open(frozen.mutationId, before.items.single()).openStream().use { it.readBytes() }
            assertThat(source.delete()).isTrue() // Only the immutable local spool copy remains.
            rig.backend.mediaBytes = bytes
            rig.backend.branch = false
            // Pull carries the server-authored projection, not the submitted mutation body.
            val pulledRoot = JsonObject(
                Json.parseToJsonElement(deviceStableRoot(frozen, SESSION)).jsonObject - "updated_at",
            )
            assertThat(parseRecordWire(pulledRoot).createdByMembershipId).isEqualTo(SESSION.membershipId)
            rig.backend.nextPull = PullResult(
                listOf(SyncEntity(
                    type = "record", clientUuid = RECORD, updatedAt = 200,
                    payloadJson = pulledRoot.toString(),
                    versionId = "resolved-version", media = frozen.media,
                )), 1, SESSION.pullGeneration, false,
            )
            inject.set(true)
            val failedRetirement = rig.port.sync(SyncTrigger.PullToRefresh)
            val retirementError = failedRetirement.exceptionOrNull()
            if (!injected.get()) {
                throw AssertionError(
                    "Sync failed before the intended post-DELETE Room rollback fault; " +
                        "mutation=${frozen.mutationId}; error=$retirementError",
                    retirementError,
                )
            }
            assertThat(injected.get()).isTrue()
            assertThat(retirementError).isInstanceOf(IOException::class.java)
            assertThat(retirementError?.message).isEqualTo("after journal delete before Room commit")
            assertThat(rig.db.conflictSnapshotCacheDao().getFrozenMediaSpoolManifest(frozen.mutationId)).isNotNull()
            val retained = requireNotNull(rig.spool.recoverGroup(frozen.mutationId)).group
            assertThat(rig.spool.open(frozen.mutationId, retained.items.single()).openStream().use { it.readBytes() }).isEqualTo(bytes)

            rig.port.sync(SyncTrigger.PullToRefresh).getOrThrow()
            assertThat(rig.db.conflictSnapshotCacheDao().getFrozenMediaSpoolManifest(frozen.mutationId)).isNull()
            assertThat(rig.spool.recoverGroup(frozen.mutationId)).isNull()
        }
    }
}

private class RoomPortRig private constructor(
    val root: File,
    val db: LeziDatabase,
    val transactions: DatabaseTransactionRunner,
    val spool: FileImmutableMediaSpool,
    val backend: RoomPortBackend,
    val port: RealSyncPort,
    private val foreground: ProcessForegroundState,
    private val preferencesScope: CoroutineScope,
) : AutoCloseable {
    suspend fun seedBaby(): Long = db.babyDao().upsert(BabyEntity(
        familyId = 1, nickname = "test baby", birthdayEpochDay = 1, themeColorArgb = 0,
        clientUuid = BABY, updatedAt = 1, syncDirty = false, familyAuthority = true, baseVersion = "baby-base",
    ))
    suspend fun seedSleep(): Long {
        val baby = seedBaby()
        val id = db.recordDao().upsert(RecordEntity(
            clientUuid = RECORD, babyId = baby, type = "sleep", timestamp = 100,
            payloadJson = RecordPayloadCodec.encode(RecordPayloadDocument(RecordType.SLEEP, SleepPayload(), CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION)),
            schemaVersion = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION, createdByMembershipId = SESSION.membershipId,
            effectiveWakeObservationClientUuid = DEAD_WAKE, updatedAt = 100, syncDirty = true,
        ))
        db.wakeObservationDao().upsert(WakeObservationEntity(
            clientUuid = DEAD_WAKE, sleepRecordClientUuid = RECORD, wakeTimestamp = 200,
            observerMembershipId = SESSION.membershipId, withdrawn = true, updatedAt = 101, syncDirty = false,
            baseVersion = "withdrawn-wake-base",
        ))
        db.wakeObservationDao().upsert(WakeObservationEntity(
            clientUuid = LIVE_WAKE, sleepRecordClientUuid = RECORD, wakeTimestamp = 220,
            observerMembershipId = SESSION.membershipId, updatedAt = 102, syncDirty = false,
            baseVersion = "live-wake-base",
        ))
        return id
    }
    fun photo(name: String): File = File(root, name).also { file ->
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also { bitmap ->
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
            bitmap.recycle()
        }
    }
    override fun close() { foreground.setForeground(false); preferencesScope.cancel(); db.close(); root.deleteRecursively() }

    companion object {
        suspend fun open(
            recordAdapter: (RecordDao) -> RecordDao = { it },
            cacheAdapter: (ConflictSnapshotCacheDao) -> ConflictSnapshotCacheDao = { it },
        ): RoomPortRig {
            val application = InstrumentationRegistry.getInstrumentation().targetContext
            val root = File(application.cacheDir, "sync-room-race-${System.nanoTime()}").apply { mkdirs() }
            val context = object : ContextWrapper(application) {
                override fun getFilesDir(): File = root
                override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
            }
            val db = Room.inMemoryDatabaseBuilder(context, LeziDatabase::class.java).build()
            val transactions = DatabaseModule.transactionRunner(db)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val preferences = DataStoreSyncPreferences(
                PreferenceDataStoreFactory.create(scope = scope) { File(root, "session.preferences_pb") }, InMemorySecureRefreshTokenStore(),
            )
            preferences.rememberEndpoint(TrustedEndpointProfile.systemPki(SESSION.baseUrl))
            preferences.saveSession(SESSION)
            val files = AndroidSyncMediaFileStore(context)
            val spool = FileImmutableMediaSpool(files, File(root, "spool"), 64L * 1024 * 1024, 8L * 1024 * 1024)
            val foreground = ProcessForegroundState().apply { setForeground(true) }
            val backend = RoomPortBackend()
            val startup = CompletableDeferred<Unit>()
            val port = RealSyncPort(
                backend, preferences, SetupProbe { _, _ -> SetupProbeResult.Failed.Unreachable }, ForegroundSyncGate(),
                db.pendingPublishDao(), recordAdapter(db.recordDao()), db.carePlanDao(), db.babyDao(), db.mediaAssetDao(),
                db.customItemDao(), db.familyDao(), PolicyClock { System.currentTimeMillis() }, foreground, files, spool,
                ReferenceAwareMediaFileCleanup(db.mediaAssetDao(), db.mediaReferenceDao(), files, transactions, MediaLocalPathGate()),
                transactions, DatabaseModule.pendingReplicaCleanupStore(db),
                localClearRecoveryGate = LocalClearRecoveryGate { startup.complete(Unit); null },
                fulfillmentCandidateDao = db.fulfillmentCandidateDao(),
                fulfillmentAuthoritySettlement = FulfillmentAuthoritySettlement(db.carePlanDao(), db.fulfillmentCandidateDao(), transactions),
                wakeObservationDao = db.wakeObservationDao(), conflictSummaryDao = db.conflictSummaryDao(),
                conflictSnapshotCacheDao = cacheAdapter(db.conflictSnapshotCacheDao()),
                appUpdateCacheDir = File(root, "updates"),
            )
            startup.await()
            return RoomPortRig(root, db, transactions, spool, backend, port, foreground, scope)
        }
    }
}

private class RoomPortBackend : DeviceCausalBackend() {
    var branch = false
    var lastUnit: CausalMutationUnit? = null
    var mediaBytes: ByteArray? = null
    override suspend fun getMedia(session: SyncSession, clientUuid: String): ByteArray =
        requireNotNull(mediaBytes).copyOf()
    var nextPull = PullResult(emptyList(), 0, SESSION.pullGeneration, false)
    override suspend fun authenticatedHandshake(session: SyncSession) = AuthenticatedSyncHandshake(
        AUTHENTICATED_SYNC_PROTOCOL_VERSION, "device-test", true, REQUIRED_CAUSAL_WIRE_CAPABILITIES,
        SyncHandshakePrincipal(session.membershipId, session.deviceId, session.role), "directory-test",
        SyncHandshakeLimits(200, 9 * 1024 * 1024, 8 * 1024 * 1024, 500, 64, 10L * 1024 * 1024),
        SyncHandshakeCompression(setOf("gzip", "identity")), SyncHandshakeRetryHints(true),
    )
    override suspend fun memberDirectory(session: SyncSession) = FamilyMemberDirectorySnapshot(
        "directory-test", listOf(FamilyMember("Test owner", session.role, true, session.membershipId)),
    )
    override suspend fun pull(session: SyncSession, page: PullPageRequest) = nextPull
    override suspend fun causalCommit(session: SyncSession, units: List<CausalMutationUnit>): CausalCommitBatchResult {
        lastUnit = units.last()
        return CausalCommitBatchResult(session.pullGeneration, units.map { unit ->
            CausalCommitUnitResult(
                status = if (branch) CausalCommitStatus.BRANCHED else CausalCommitStatus.ACCEPTED,
                mutationId = unit.mutationId, requestHash = causalMutationContentHash(unit),
                stableVersionId = "stable-version", stableRootJson = deviceStableRoot(unit, session), stableMedia = unit.media,
                stableDeleted = unit.deleted,
                stableDeletedAt = if (unit.deleted) requireNotNull(
                    Json.parseToJsonElement(unit.rootJson).jsonObject["updated_at"]?.jsonPrimitive?.longOrNull,
                ) else null,
                conflictId = if (branch) "conflict-test" else null, branchVersionId = if (branch) "branch-test" else null,
            )
        })
    }
}

private val SESSION = SyncSession(
    familyId = "room-test-family", membershipId = "room-test-owner", deviceId = "room-test-device",
    role = FamilyRole.Owner, accessToken = "test-access", refreshToken = "test-refresh", accessExpiresAtEpochSeconds = Long.MAX_VALUE,
    pullGeneration = "room-generation", serverHost = "room.example.test", serverPort = 443,
)
private const val BABY = "11111111-1111-4111-8111-111111111111"
private const val RECORD = "22222222-2222-4222-8222-222222222222"
private const val DEAD_WAKE = "33333333-3333-4333-8333-333333333333"
private const val LIVE_WAKE = "44444444-4444-4444-8444-444444444444"
private const val PHOTO = "55555555-5555-4555-8555-555555555555"
private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
