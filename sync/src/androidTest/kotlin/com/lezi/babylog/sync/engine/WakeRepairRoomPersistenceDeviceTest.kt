package com.lezi.babylog.sync.engine

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.BabyEntity
import com.lezi.babylog.core.database.DatabaseModule
import com.lezi.babylog.core.database.LeziDatabase
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.MediaLocalPathGate
import com.lezi.babylog.core.database.RecordDao
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.database.causal.WakeObservationEntity
import com.lezi.babylog.core.model.CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION
import com.lezi.babylog.core.model.RecordPayloadCodec
import com.lezi.babylog.core.model.RecordPayloadDocument
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.SleepPayload
import com.lezi.babylog.sync.backend.CausalCommitBatchResult
import com.lezi.babylog.sync.backend.CausalCommitStatus
import com.lezi.babylog.sync.backend.CausalCommitUnitResult
import com.lezi.babylog.sync.backend.CausalMutationUnit
import com.lezi.babylog.sync.media.AndroidSyncMediaFileStore
import com.lezi.babylog.sync.media.FileImmutableMediaSpool
import com.lezi.babylog.sync.media.ReferenceAwareMediaFileCleanup
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.SyncSession
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * US-081: exact repair census latch, generated Room DAOs, named SQLite file and synthetic JPEG.
 * Close/reopen proves persistence, not OS process death. The backend is an idempotent receipt
 * observer, not a server-ACL or public SyncPort integration proof (see SyncPortRoomRaceDeviceTest).
 */
@RunWith(AndroidJUnit4::class)
class WakeRepairRoomPersistenceDeviceTest {
    @Test(timeout = 120_000)
    fun newerNotePhotoSelectionAndDeletionSurvivePausedRepairAndReopen() = runBlocking {
        for (edit in Edit.values()) exerciseLabeledRepairRace(edit, freezeFirst = false)
    }

    @Test(timeout = 120_000)
    fun frozenEnvelopeReplaysExactlyBeforeNewerIntentAfterRepairAndReopen() = runBlocking {
        for (edit in Edit.values()) exerciseLabeledRepairRace(edit, freezeFirst = true)
    }

    private suspend fun exerciseLabeledRepairRace(edit: Edit, freezeFirst: Boolean) {
        try {
            exerciseRepairRace(edit, freezeFirst)
        } catch (failure: AssertionError) {
            throw AssertionError("US-081 edit=$edit, frozen=$freezeFirst", failure)
        }
    }

    private suspend fun exerciseRepairRace(edit: Edit, freezeFirst: Boolean) {
        WakeRepairRoomRig.open().use { rig ->
            rig.seedSleep(selectedWithdrawn = !freezeFirst)
            val initial = rig.record()
            if (freezeFirst) {
                rig.backend.loseNextResponse = true
                val failure = runCatching { rig.publishCurrent() }.exceptionOrNull()
                assertThat(failure).isInstanceOf(IOException::class.java)
                assertThat(failure).hasMessageThat().isEqualTo(LOST_RESPONSE)
                rig.withdrawSelectedWake()
            }
            val frozen = rig.db.conflictSnapshotCacheDao().getFrozenMutation("record", RECORD)
            if (freezeFirst) {
                assertThat(frozen).isNotNull()
                val envelope = decodeFrozenCommitEnvelope(requireNotNull(frozen).payloadJson)
                assertThat(envelope.contentEpoch).isEqualTo(initial.updatedAt)
                assertThat(envelope.mutation).isEqualTo(rig.backend.observed.single())
                assertThat(envelope.requestHash).isEqualTo(causalMutationContentHash(envelope.mutation))
            } else {
                assertThat(frozen).isNull()
            }

            val saved = raceAtRepairRead(rig, edit)
            val repaired = rig.record()
            assertSavedIntent(rig, edit, saved)
            when (edit) {
                Edit.Note, Edit.Photo -> {
                    assertThat(repaired.effectiveWakeObservationClientUuid).isNull()
                    assertThat(repaired.updatedAt).isGreaterThan(saved.updatedAt)
                }
                Edit.Selection, Edit.Deletion -> assertThat(repaired).isEqualTo(saved)
            }
            assertThat(repaired.updatedAt).isAtLeast(saved.updatedAt)
            assertThat(saved.updatedAt).isGreaterThan(initial.updatedAt)
            assertThat(repaired.syncDirty).isTrue()
            assertThat(repaired.mutationId).isNull()
            // The repair-only pass cannot rewrite, replace or retire a frozen request.
            assertThat(rig.db.conflictSnapshotCacheDao().getFrozenMutation("record", RECORD))
                .isEqualTo(frozen)
            assertThat(rig.backend.observed).hasSize(if (freezeFirst) 1 else 0)

            rig.reopen()
            assertThat(rig.record()).isEqualTo(repaired)
            assertSavedIntent(rig, edit, saved)
            assertThat(rig.db.conflictSnapshotCacheDao().getFrozenMutation("record", RECORD))
                .isEqualTo(frozen)

            if (freezeFirst) {
                val old = decodeFrozenCommitEnvelope(requireNotNull(frozen).payloadJson)
                rig.publishCurrent()
                val replay = rig.backend.observed.last()
                assertThat(replay).isEqualTo(old.mutation)
                assertThat(causalMutationContentHash(replay)).isEqualTo(old.requestHash)
                val afterReplay = rig.record()
                assertSavedIntent(rig, edit, saved)
                assertThat(afterReplay.updatedAt).isEqualTo(repaired.updatedAt)
                assertThat(afterReplay.syncDirty).isTrue()
                assertThat(afterReplay.mutationId).isNull()
                assertThat(afterReplay.baseVersion).isEqualTo(rig.backend.versionFor(replay))
                assertThat(rig.db.conflictSnapshotCacheDao().getFrozenMutation("record", RECORD))
                    .isNull()
                // Reopen once more between settling old intent and freezing the newer epoch.
                rig.reopen()
                assertThat(rig.record()).isEqualTo(afterReplay)
            }

            rig.publishCurrent()
            val published = rig.backend.observed.last()
            val publishedRoot = Json.parseToJsonElement(published.rootJson).jsonObject
            assertThat(publishedRoot.getValue("updated_at").jsonPrimitive.longOrNull)
                .isEqualTo(repaired.updatedAt)
            assertThat(publishedRoot.getValue("note").jsonPrimitive.contentOrNull).isEqualTo(saved.note)
            assertThat(published.deleted).isEqualTo(edit == Edit.Deletion)
            assertThat(publishedRoot.getValue("effective_wake_observation_client_uuid")
                .jsonPrimitive.contentOrNull).isEqualTo(if (edit == Edit.Selection) LIVE_WAKE else null)
            if (freezeFirst) {
                val old = decodeFrozenCommitEnvelope(requireNotNull(frozen).payloadJson).mutation
                assertThat(published.mutationId).isNotEqualTo(old.mutationId)
                assertThat(published.baseVersion).isEqualTo(rig.backend.versionFor(old))
            }
            if (edit == Edit.Photo) {
                val media = requireNotNull(rig.db.mediaAssetDao().getByClientUuid(PHOTO))
                val item = published.media.single()
                assertThat(item.mediaUuid).isEqualTo(PHOTO)
                assertThat(item.sha256).isEqualTo(media.sha256)
                assertThat(item.byteSize).isEqualTo(media.byteSize)
                val upload = rig.backend.uploadedMedia.single()
                assertThat(upload.first).isEqualTo(PHOTO)
                assertThat(sha256(upload.second)).isEqualTo(item.sha256)
                assertThat(upload.second.size.toLong()).isEqualTo(item.byteSize)
                assertThat(File(media.localUri).readBytes()).isEqualTo(upload.second)
                assertThat(media.deletedAt).isNull()
                assertThat(media.recordId).isEqualTo(saved.id)
            } else {
                assertThat(published.media).isEmpty()
                assertThat(rig.backend.uploadedMedia).isEmpty()
            }
            val settled = rig.record()
            assertThat(settled.note).isEqualTo(saved.note)
            assertThat(settled.deletedAt).isEqualTo(saved.deletedAt)
            assertThat(settled.updatedAt).isEqualTo(repaired.updatedAt)
            assertThat(settled.syncDirty).isFalse()
            assertThat(settled.baseVersion).isEqualTo(rig.backend.versionFor(published))
            assertThat(rig.backend.observed).hasSize(if (freezeFirst) 3 else 1)
            assertThat(rig.db.conflictSnapshotCacheDao().getFrozenMutation("record", RECORD)).isNull()
            rig.reopen()
            assertThat(rig.record()).isEqualTo(settled)
        }
    }

    private suspend fun raceAtRepairRead(rig: WakeRepairRoomRig, edit: Edit): RecordEntity = coroutineScope {
        val captured = CompletableDeferred<RecordEntity>()
        val release = CompletableDeferred<Unit>()
        val actual = rig.db.recordDao()
        val reads = mutableListOf<RecordEntity>()
        val writes = mutableListOf<RecordEntity>()
        val latchedDao = object : RecordDao by actual {
            override suspend fun listPendingSync(): List<RecordEntity> {
                val snapshot = actual.listPendingSync()
                check(!captured.isCompleted) { "repair-only pass unexpectedly repeated its census" }
                captured.complete(snapshot.single { it.clientUuid == RECORD })
                release.await()
                return snapshot
            }

            override suspend fun getByClientUuid(uuid: String): RecordEntity? =
                actual.getByClientUuid(uuid).also { if (uuid == RECORD && it != null) reads += it }

            override suspend fun update(record: RecordEntity) {
                actual.update(record)
                writes += record
            }
        }
        // settle's first pending read belongs to healInvalidEffectiveWakes. Empty candidates
        // stop after maintenance, so this is an exact seam rather than a numbered read guess.
        val repair = async(Dispatchers.IO) { rig.settlement(latchedDao).settle(SESSION, emptyList()) }
        try {
            val stale = withTimeout(10_000) { captured.await() }
            assertThat(stale.effectiveWakeObservationClientUuid).isEqualTo(SELECTED_WAKE)
            // The writer is a sibling of repair and does not inherit its Room transaction.
            // Pausing a SELECT inside the write lease instead would deadlock this interleaving.
            val saved = withTimeout(10_000) { rig.saveEdit(edit) }
            assertThat(saved.updatedAt).isGreaterThan(stale.updatedAt)
            assertThat(rig.record()).isEqualTo(saved)
            release.complete(Unit)
            withTimeout(10_000) { repair.await() }
            assertThat(reads.first()).isEqualTo(saved)
            if (edit == Edit.Note || edit == Edit.Photo) {
                assertThat(writes).containsExactly(rig.record())
            } else {
                assertThat(writes).isEmpty()
            }
            saved
        } finally {
            withContext(NonCancellable) {
                release.complete(Unit)
                repair.cancelAndJoin()
            }
        }
    }

    private suspend fun assertSavedIntent(rig: WakeRepairRoomRig, edit: Edit, saved: RecordEntity) {
        val current = rig.record()
        assertThat(current.note).isEqualTo(saved.note)
        assertThat(current.deletedAt).isEqualTo(saved.deletedAt)
        assertThat(current.updatedAt).isAtLeast(saved.updatedAt)
        assertThat(current.payloadJson).isEqualTo(saved.payloadJson)
        assertThat(current.timestamp).isEqualTo(saved.timestamp)
        if (edit == Edit.Selection) {
            assertThat(current.effectiveWakeObservationClientUuid).isEqualTo(LIVE_WAKE)
        }
        if (edit == Edit.Photo) {
            val media = requireNotNull(rig.db.mediaAssetDao().getByClientUuid(PHOTO))
            assertThat(media.recordId).isEqualTo(saved.id)
            assertThat(media.deletedAt).isNull()
            assertThat(media.updatedAt).isEqualTo(saved.updatedAt)
            assertThat(File(media.localUri).readBytes()).isEqualTo(rig.photoBytes)
            assertThat(media.sha256).isEqualTo(sha256(rig.photoBytes))
        }
    }

    private enum class Edit { Note, Photo, Selection, Deletion }

    private class WakeRepairRoomRig private constructor(val root: File, private val context: Context) : AutoCloseable {
        private val databaseFile = File(root, "wake-repair.db")
        var db: LeziDatabase = openDatabase()
            private set
        val backend = WakeRepairBackend()
        private val files = AndroidSyncMediaFileStore(context)
        private val spool = FileImmutableMediaSpool(files, File(root, "spool"), 8L * 1024 * 1024, 1024L * 1024)
        private val photo = File(root, "record-media/new-photo.jpg").also { file ->
            check(file.parentFile!!.mkdirs())
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also { bitmap ->
                try {
                    file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
                } finally {
                    bitmap.recycle()
                }
            }
        }
        val photoBytes: ByteArray = photo.readBytes()
        private val transactions get() = DatabaseModule.transactionRunner(db)

        private fun openDatabase(): LeziDatabase =
            Room.databaseBuilder(context, LeziDatabase::class.java, databaseFile.absolutePath).build()

        suspend fun reopen() {
            assertThat(databaseFile.isFile).isTrue()
            db.close()
            db = openDatabase()
            // Force opening the same file; no in-memory replacement or migration fallback.
            assertThat(record().clientUuid).isEqualTo(RECORD)
            assertThat(db.openHelper.readableDatabase.version).isEqualTo(LeziDatabase.VERSION)
        }

        suspend fun seedSleep(selectedWithdrawn: Boolean) = transactions.run {
            val babyId = db.babyDao().upsert(BabyEntity(
                familyId = 1, nickname = "synthetic baby", birthdayEpochDay = 1, themeColorArgb = 0,
                clientUuid = BABY, updatedAt = 1, syncDirty = false,
                familyAuthority = true, baseVersion = "baby-base",
            ))
            db.recordDao().upsert(RecordEntity(
                clientUuid = RECORD, babyId = babyId, type = "sleep", timestamp = 100, note = "original note",
                payloadJson = RecordPayloadCodec.encode(RecordPayloadDocument(
                    RecordType.SLEEP, SleepPayload(), CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
                )),
                schemaVersion = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
                createdByMembershipId = SESSION.membershipId, effectiveWakeObservationClientUuid = SELECTED_WAKE,
                updatedAt = 100, syncDirty = true, baseVersion = "sleep-base",
            ))
            db.wakeObservationDao().upsert(WakeObservationEntity(
                clientUuid = SELECTED_WAKE, sleepRecordClientUuid = RECORD, wakeTimestamp = 200,
                observerMembershipId = SESSION.membershipId, withdrawn = selectedWithdrawn,
                updatedAt = 101, syncDirty = false, baseVersion = "selected-wake-base",
            ))
            db.wakeObservationDao().upsert(WakeObservationEntity(
                clientUuid = LIVE_WAKE, sleepRecordClientUuid = RECORD, wakeTimestamp = 220,
                observerMembershipId = SESSION.membershipId, updatedAt = 102,
                syncDirty = false, baseVersion = "live-wake-base",
            ))
        }

        suspend fun withdrawSelectedWake() = transactions.run {
            val wake = requireNotNull(db.wakeObservationDao().getByClientUuid(SELECTED_WAKE))
            db.wakeObservationDao().update(wake.copy(withdrawn = true, updatedAt = wake.updatedAt + 1))
        }

        suspend fun record(): RecordEntity = requireNotNull(db.recordDao().getByClientUuid(RECORD))

        suspend fun saveEdit(edit: Edit): RecordEntity = transactions.run {
            val current = record()
            // Ahead of wall clock, so a stale repair cannot accidentally look monotonic.
            val epoch = maxOf(System.currentTimeMillis() + 60_000, current.updatedAt + 10)
            val changed = when (edit) {
                Edit.Note -> current.copy(note = "new user note")
                Edit.Photo -> {
                    db.mediaAssetDao().upsert(MediaAssetEntity(
                        clientUuid = PHOTO, recordId = current.id, localUri = photo.path,
                        mime = "image/jpeg", byteSize = photoBytes.size.toLong(), width = 2, height = 2,
                        sha256 = sha256(photoBytes), createdAt = epoch, updatedAt = epoch,
                    ))
                    current
                }
                Edit.Selection -> current.copy(effectiveWakeObservationClientUuid = LIVE_WAKE)
                Edit.Deletion -> current.copy(deletedAt = epoch)
            }.copy(updatedAt = epoch, syncDirty = true, mutationId = null)
            db.recordDao().update(changed)
            changed
        }

        fun settlement(records: RecordDao = db.recordDao()): CausalSettlement {
            val cleanup = ReferenceAwareMediaFileCleanup(
                db.mediaAssetDao(), db.mediaReferenceDao(), files, transactions, MediaLocalPathGate(),
            )
            return CausalSettlement(
                backend = backend, recordDao = records, carePlanDao = db.carePlanDao(),
                babyDao = db.babyDao(), mediaDao = db.mediaAssetDao(), customItemDao = db.customItemDao(),
                wakeObservationDao = db.wakeObservationDao(), conflictSummaryDao = db.conflictSummaryDao(),
                conflictSnapshotCacheDao = db.conflictSnapshotCacheDao(), immutableMediaSpool = spool,
                mediaFiles = files, transactionRunner = transactions,
                cleanupUnownedMediaPaths = cleanup::cleanupUnreferencedPaths,
                requireRemoteAllowed = {},
            )
        }

        suspend fun publishCurrent() {
            val current = record()
            settlement().settle(SESSION, listOf(PublishCandidate(
                planId = current.id, entityType = "record", clientUuid = RECORD,
                payloadJson = current.payloadJson, updatedAt = current.updatedAt, deletedAt = current.deletedAt,
            )))
        }

        override fun close() {
            db.close()
            check(root.deleteRecursively()) { "could not remove synthetic wake-repair fixture" }
        }

        companion object {
            fun open(): WakeRepairRoomRig {
                val application = InstrumentationRegistry.getInstrumentation().targetContext
                val root = File(application.cacheDir, "wake-repair-room-${UUID.randomUUID()}")
                check(root.mkdirs())
                val context = object : ContextWrapper(application) {
                    override fun getFilesDir(): File = root
                    override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
                }
                return WakeRepairRoomRig(root, context)
            }
        }
    }

    private class WakeRepairBackend : DeviceCausalBackend() {
        val observed = mutableListOf<CausalMutationUnit>()
        private val receipts = linkedMapOf<String, CausalCommitUnitResult>()
        var loseNextResponse = false

        fun versionFor(unit: CausalMutationUnit): String = receipts.getValue(unit.mutationId).stableVersionId

        override suspend fun causalCommit(session: SyncSession, units: List<CausalMutationUnit>): CausalCommitBatchResult {
            observed += units
            val results = units.map { unit ->
                val hash = causalMutationContentHash(unit)
                receipts[unit.mutationId]?.also { assertThat(it.requestHash).isEqualTo(hash) }
                    ?: CausalCommitUnitResult(
                        status = CausalCommitStatus.ACCEPTED, mutationId = unit.mutationId, requestHash = hash,
                        stableVersionId = "wake-repair-version-${receipts.size + 1}",
                        stableRootJson = deviceStableRoot(unit, session), stableMedia = unit.media,
                        stableDeleted = unit.deleted,
                        stableDeletedAt = if (unit.deleted) requireNotNull(
                            Json.parseToJsonElement(unit.rootJson).jsonObject["updated_at"]?.jsonPrimitive?.longOrNull,
                        ) else null,
                    ).also { receipts[unit.mutationId] = it }
            }
            if (loseNextResponse) {
                loseNextResponse = false
                throw IOException(LOST_RESPONSE)
            }
            return CausalCommitBatchResult(session.pullGeneration, results)
        }
    }
}

private val SESSION = SyncSession(
    familyId = "wake-repair-family", membershipId = "wake-repair-owner", deviceId = "wake-repair-device",
    role = FamilyRole.Owner, accessToken = "synthetic-token", refreshToken = "synthetic-refresh",
    accessExpiresAtEpochSeconds = Long.MAX_VALUE, pullGeneration = "wake-repair-generation",
    serverHost = "wake-repair.example.test", serverPort = 443,
)
private const val BABY = "11111111-1111-4111-8111-111111111181"
private const val RECORD = "22222222-2222-4222-8222-222222222281"
private const val SELECTED_WAKE = "33333333-3333-4333-8333-333333333381"
private const val LIVE_WAKE = "44444444-4444-4444-8444-444444444481"
private const val PHOTO = "55555555-5555-4555-8555-555555555581"
private const val LOST_RESPONSE = "synthetic response loss after accepting frozen sleep"
private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
