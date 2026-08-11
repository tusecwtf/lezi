package com.lezi.babylog.sync.engine

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.BabyEntity
import com.lezi.babylog.core.database.DatabaseModule
import com.lezi.babylog.core.database.LeziDatabase
import com.lezi.babylog.core.database.LocalDataClearScope
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.sync.backend.AuthenticatedSyncHandshake
import com.lezi.babylog.sync.backend.AtomicBundleDraft
import com.lezi.babylog.sync.backend.BundleCommitResult
import com.lezi.babylog.sync.backend.BundleStageStatus
import com.lezi.babylog.sync.backend.CausalBatchResult
import com.lezi.babylog.sync.backend.CausalCommitStatus
import com.lezi.babylog.sync.backend.CausalMutationUnit
import com.lezi.babylog.sync.backend.CausalUnitResult
import com.lezi.babylog.sync.backend.DisplayNameUpdateResult
import com.lezi.babylog.sync.backend.FamilyMemberDirectorySnapshot
import com.lezi.babylog.sync.backend.PullPageRequest
import com.lezi.babylog.sync.backend.PullResult
import com.lezi.babylog.sync.backend.SessionBootstrapResult
import com.lezi.babylog.sync.backend.SyncBackend
import com.lezi.babylog.sync.media.LocalMediaInfo
import com.lezi.babylog.sync.media.PreparedMedia
import com.lezi.babylog.sync.media.SyncMediaFileStore
import com.lezi.babylog.sync.media.SyncMediaUploadSource
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.SyncSession
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordCommitFirstRoomReplayTest {
    @Test
    fun lostResponseReopensRoomAndReplaysExactEnvelopeBeforeNextEpoch() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "record-commit-first-${System.nanoTime()}.db"
        var database = Room.databaseBuilder(context, LeziDatabase::class.java, databaseName).build()
        var lostEnvelope: CausalMutationUnit? = null
        try {
            try {
                val babyId = database.babyDao().upsert(
                    BabyEntity(
                        familyId = 1,
                        nickname = "宝宝",
                        birthdayEpochDay = 1,
                        themeColorArgb = 0,
                        clientUuid = BABY_UUID,
                        updatedAt = 1,
                        syncDirty = false,
                        familyAuthority = true,
                        baseVersion = "v-baby",
                    ),
                )
                database.recordDao().upsert(
                    RecordEntity(
                        clientUuid = RECORD_UUID,
                        babyId = babyId,
                        type = "formula",
                        timestamp = 100,
                        note = "epoch-1",
                        payloadJson = """{"amount_ml":90}""",
                        schemaVersion = 2,
                        updatedAt = 100,
                        syncDirty = true,
                        baseVersion = "v-record-0",
                    ),
                )

                settlement(database, CommitOnlyBackend { units ->
                    lostEnvelope = units.single()
                    throw IOException("response lost after server commit")
                }).settle(SESSION, listOf(candidate(database)))
            } catch (expected: IOException) {
                assertThat(expected).hasMessageThat().contains("response lost")
            }

            val frozen = requireNotNull(lostEnvelope)
            assertThat(database.conflictSnapshotCacheDao().getFrozenMutation("record", RECORD_UUID))
                .isNotNull()
            val epochOne = requireNotNull(database.recordDao().getByClientUuid(RECORD_UUID))
            database.recordDao().update(
                epochOne.copy(
                    note = "epoch-2",
                    updatedAt = 200,
                    syncDirty = true,
                    mutationId = null,
                ),
            )

            database.close()
            database = Room.databaseBuilder(context, LeziDatabase::class.java, databaseName).build()
            var commitCall = 0
            val backend = CommitOnlyBackend { units ->
                val unit = units.single()
                commitCall += 1
                if (commitCall == 1) {
                    assertThat(unit).isEqualTo(frozen)
                    accepted(unit, "v-epoch-1")
                } else {
                    assertThat(unit.mutationId).isNotEqualTo(frozen.mutationId)
                    assertThat(unit.baseVersion).isEqualTo("v-epoch-1")
                    assertThat(unit.rootJson).contains("\"note\":\"epoch-2\"")
                    accepted(unit, "v-epoch-2")
                }
            }
            val reopened = settlement(database, backend)
            reopened.settle(SESSION, listOf(candidate(database)))

            val afterOldTerminal = requireNotNull(database.recordDao().getByClientUuid(RECORD_UUID))
            assertThat(afterOldTerminal.note).isEqualTo("epoch-2")
            assertThat(afterOldTerminal.updatedAt).isEqualTo(200)
            assertThat(afterOldTerminal.syncDirty).isTrue()
            assertThat(afterOldTerminal.mutationId).isNull()
            assertThat(afterOldTerminal.baseVersion).isEqualTo("v-epoch-1")
            assertThat(database.conflictSnapshotCacheDao().getFrozenMutation("record", RECORD_UUID))
                .isNull()

            reopened.settle(SESSION, listOf(candidate(database)))

            val settled = requireNotNull(database.recordDao().getByClientUuid(RECORD_UUID))
            assertThat(commitCall).isEqualTo(2)
            assertThat(settled.note).isEqualTo("epoch-2")
            assertThat(settled.syncDirty).isFalse()
            assertThat(settled.baseVersion).isEqualTo("v-epoch-2")
            assertThat(database.conflictSnapshotCacheDao().getFrozenMutation("record", RECORD_UUID))
                .isNull()
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    private suspend fun candidate(database: LeziDatabase): PublishCandidate {
        val record = requireNotNull(database.recordDao().getByClientUuid(RECORD_UUID))
        return PublishCandidate(
            planId = record.id,
            entityType = "record",
            clientUuid = record.clientUuid,
            payloadJson = record.payloadJson,
            updatedAt = record.updatedAt,
            deletedAt = record.deletedAt,
        )
    }

    private fun settlement(database: LeziDatabase, backend: SyncBackend) = CausalSettlement(
        backend = backend,
        recordDao = database.recordDao(),
        carePlanDao = database.carePlanDao(),
        babyDao = database.babyDao(),
        mediaDao = database.mediaAssetDao(),
        customItemDao = database.customItemDao(),
        wakeObservationDao = database.wakeObservationDao(),
        conflictSummaryDao = database.conflictSummaryDao(),
        conflictSnapshotCacheDao = database.conflictSnapshotCacheDao(),
        mediaFiles = NoMediaFileStore,
        transactionRunner = DatabaseModule.transactionRunner(database),
        requireRemoteAllowed = {},
    )

    private fun accepted(unit: CausalMutationUnit, stableVersion: String) = CausalBatchResult(
        generation = SESSION.pullGeneration,
        cursor = SESSION.pullCursor,
        results = listOf(
            CausalUnitResult(
                status = CausalCommitStatus.ACCEPTED,
                mutationId = unit.mutationId,
                requestHash = causalMutationContentHash(unit),
                generation = SESSION.pullGeneration,
                stableVersionId = stableVersion,
                stableRootJson = unit.rootJson,
                stableMedia = unit.media,
            ),
        ),
    )

    private class CommitOnlyBackend(
        private val commit: suspend (List<CausalMutationUnit>) -> CausalBatchResult,
    ) : SyncBackend {
        override fun supportsCausalWire() = true

        override suspend fun causalCommit(
            session: SyncSession,
            units: List<CausalMutationUnit>,
        ): CausalBatchResult = commit(units)

        override suspend fun create(
            baseUrl: String,
            deviceId: String,
            displayName: String?,
            createRequestId: String,
            bootstrapSecret: String?,
            familyName: String?,
        ): SessionBootstrapResult = unsupported()

        override suspend fun pull(session: SyncSession, page: PullPageRequest): PullResult = unsupported()
        override suspend fun authenticatedHandshake(
            session: SyncSession,
        ): AuthenticatedSyncHandshake = unsupported()
        override suspend fun memberDirectory(
            session: SyncSession,
        ): FamilyMemberDirectorySnapshot = unsupported()
        override suspend fun updateMyDisplayName(
            session: SyncSession,
            displayName: String,
        ): DisplayNameUpdateResult = unsupported()

        override suspend fun renameFamily(session: SyncSession, familyName: String?) = unsupported()
        override suspend fun leave(session: SyncSession) = unsupported()
        override suspend fun removeMember(session: SyncSession, membershipId: String) = unsupported()
        override suspend fun deleteFamily(
            session: SyncSession,
            familyName: String,
            rootPassword: String,
        ) = unsupported()

        override suspend fun getMedia(session: SyncSession, clientUuid: String): ByteArray =
            unsupported()

        override suspend fun stageBundle(
            session: SyncSession,
            draft: AtomicBundleDraft,
        ): BundleStageStatus = unsupported()

        override suspend fun putBundleMedia(
            session: SyncSession,
            bundleId: String,
            clientUuid: String,
            source: SyncMediaUploadSource,
        ): BundleStageStatus = unsupported()

        override suspend fun commitBundle(
            session: SyncSession,
            bundleId: String,
        ): BundleCommitResult = unsupported()

        private fun unsupported(): Nothing = error("unused backend seam")
    }

    private object NoMediaFileStore : SyncMediaFileStore {
        override suspend fun inspect(localUri: String): LocalMediaInfo? = null
        override suspend fun prepareUpload(localUri: String): PreparedMedia = error("no media")
        override suspend fun saveDownloaded(
            clientUuid: String,
            kind: String,
            bytes: ByteArray,
            mime: String?,
        ): String = error("no media")

        override suspend fun delete(localUri: String) = Unit
        override suspend fun sweepUnreferenced(
            scope: LocalDataClearScope,
            retainedLocalUris: Set<String>,
        ) = Unit
    }

    private companion object {
        const val BABY_UUID = "00000000-0000-4000-8000-000000000101"
        const val RECORD_UUID = "00000000-0000-4000-8000-000000000102"
        val SESSION = SyncSession(
            familyId = "family-a",
            accessToken = "token",
            deviceId = "device-a",
            membershipId = "membership-a",
            role = FamilyRole.Owner,
            pullCursor = 7,
            pullGeneration = "generation-a",
            serverHost = "127.0.0.1",
        )
    }
}
