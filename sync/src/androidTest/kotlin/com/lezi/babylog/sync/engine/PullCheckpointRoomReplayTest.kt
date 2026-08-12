package com.lezi.babylog.sync.engine

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.BabyEntity
import com.lezi.babylog.core.database.DatabaseModule
import com.lezi.babylog.core.database.FamilyEntity
import com.lezi.babylog.core.database.LeziDatabase
import com.lezi.babylog.core.database.LocalDataClearScope
import com.lezi.babylog.core.database.MediaLocalPathGate
import com.lezi.babylog.core.database.fulfillment.FulfillmentAuthoritySettlement
import com.lezi.babylog.core.database.causal.ConflictSnapshotCacheDao
import com.lezi.babylog.core.database.causal.ConflictSnapshotCacheEntity
import com.lezi.babylog.sync.FamilyMember
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.backend.AUTHENTICATED_SYNC_PROTOCOL_VERSION
import com.lezi.babylog.sync.backend.AuthenticatedSyncHandshake
import com.lezi.babylog.sync.backend.AtomicBundleDraft
import com.lezi.babylog.sync.backend.BundleCommitResult
import com.lezi.babylog.sync.backend.BundleStageStatus
import com.lezi.babylog.sync.backend.DisplayNameUpdateResult
import com.lezi.babylog.sync.backend.FamilyMemberDirectorySnapshot
import com.lezi.babylog.sync.backend.PullPageRequest
import com.lezi.babylog.sync.backend.PullResult
import com.lezi.babylog.sync.backend.REQUIRED_CAUSAL_WIRE_CAPABILITIES
import com.lezi.babylog.sync.backend.SessionBootstrapResult
import com.lezi.babylog.sync.backend.SyncBackend
import com.lezi.babylog.sync.backend.SyncEntity
import com.lezi.babylog.sync.backend.SyncHandshakeCompression
import com.lezi.babylog.sync.backend.SyncHandshakeLimits
import com.lezi.babylog.sync.backend.SyncHandshakePrincipal
import com.lezi.babylog.sync.backend.SyncHandshakeRetryHints
import com.lezi.babylog.sync.media.LocalMediaInfo
import com.lezi.babylog.sync.media.PreparedMedia
import com.lezi.babylog.sync.media.ReferenceAwareMediaFileCleanup
import com.lezi.babylog.sync.media.SyncMediaFileStore
import com.lezi.babylog.sync.session.DataStoreSyncPreferences
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.InMemorySecureRefreshTokenStore
import com.lezi.babylog.sync.session.PolicyClock
import com.lezi.babylog.sync.session.SyncSession
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PullCheckpointRoomReplayTest {
    @Test
    fun resetReceiptRollsBackWithRootsAndSurvivesRoomReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = System.nanoTime()
        val databaseName = "replica-reset-$suffix.db"
        val preferencesFile = File(context.filesDir, "datastore/replica-reset-$suffix.preferences_pb")
        preferencesFile.parentFile?.mkdirs()
        val tokens = InMemorySecureRefreshTokenStore()
        var database = Room.databaseBuilder(context, LeziDatabase::class.java, databaseName).build()
        var dataScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            var preferences = DataStoreSyncPreferences(
                PreferenceDataStoreFactory.create(scope = dataScope) { preferencesFile },
                tokens,
            )
            preferences.saveSession(SESSION)
            preferences.saveFamilyMemberDirectorySnapshot(DIRECTORY_GENERATION, emptyList())
            database.familyDao().insert(FamilyEntity(id = 1, ownerUserId = 1, createdAt = 0))
            database.babyDao().upsert(
                BabyEntity(
                    familyId = 1,
                    nickname = "重置前副本",
                    birthdayEpochDay = 20_000,
                    themeColorArgb = 0,
                    clientUuid = BABY_A,
                    updatedAt = 100,
                    syncDirty = false,
                    baseVersion = "v-before-reset",
                ),
            )
            val failingCache = FailingResetReceiptCache(database.conflictSnapshotCacheDao())

            val failure = runCatching {
                engine(database, preferences, PullOnlyBackend(ArrayDeque()), failingCache)
                    .resetLocalSyncReceipts(SESSION, invalidateCurrentReceipts = true)
            }.exceptionOrNull()

            assertThat(failure).hasMessageThat().isEqualTo("reset receipt write interrupted")
            assertThat(database.babyDao().getByClientUuid(BABY_A)!!.syncDirty).isFalse()

            engine(database, preferences, PullOnlyBackend(ArrayDeque()))
                .resetLocalSyncReceipts(SESSION, invalidateCurrentReceipts = true)
            assertThat(database.babyDao().getByClientUuid(BABY_A)!!.syncDirty).isTrue()

            database.close()
            dataScope.coroutineContext[Job]?.cancelAndJoin()
            dataScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            database = Room.databaseBuilder(context, LeziDatabase::class.java, databaseName).build()
            preferences = DataStoreSyncPreferences(
                PreferenceDataStoreFactory.create(scope = dataScope) { preferencesFile },
                tokens,
            )
            val backend = PullOnlyBackend(
                ArrayDeque(
                    listOf(
                        PullResult(
                            listOf(remoteBaby(BABY_A, "服务端权威副本", 200).copy(versionId = "v-remote")),
                            1,
                            GENERATION,
                            false,
                        ),
                    ),
                ),
            )

            val outcome = engine(database, preferences, backend)
                .synchronize(SESSION, SyncTrigger.PullToRefresh)

            assertThat(outcome).isEqualTo(ReplicaSyncOutcome.Synchronized)
            val recovered = requireNotNull(database.babyDao().getByClientUuid(BABY_A))
            assertThat(recovered.nickname).isEqualTo("服务端权威副本")
            assertThat(recovered.syncDirty).isFalse()
        } finally {
            database.close()
            dataScope.coroutineContext[Job]?.cancelAndJoin()
            context.deleteDatabase(databaseName)
            preferencesFile.delete()
        }
    }

    @Test
    fun failedSecondPageRollsBackRoomAndReopensFromTheLastDurableCursor() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = System.nanoTime()
        val databaseName = "pull-checkpoint-$suffix.db"
        val preferencesFile = File(context.filesDir, "datastore/pull-checkpoint-$suffix.preferences_pb")
        preferencesFile.parentFile?.mkdirs()
        val tokens = InMemorySecureRefreshTokenStore()
        var database = Room.databaseBuilder(context, LeziDatabase::class.java, databaseName).build()
        var dataScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            var preferences = DataStoreSyncPreferences(
                PreferenceDataStoreFactory.create(scope = dataScope) { preferencesFile },
                tokens,
            )
            preferences.saveSession(SESSION)
            preferences.saveFamilyMemberDirectorySnapshot(DIRECTORY_GENERATION, emptyList())
            database.familyDao().insert(FamilyEntity(id = 1, ownerUserId = 1, createdAt = 0))
            val firstBackend = PullOnlyBackend(
                ArrayDeque(
                    listOf(
                        PullResult(listOf(remoteBaby(BABY_A, "宝宝 A", 100)), 1, GENERATION, true),
                        PullResult(
                            listOf(
                                remoteBaby(BABY_B, "宝宝 B", 200),
                                SyncEntity("baby", BABY_C, "{}", 201),
                            ),
                            2,
                            GENERATION,
                            false,
                        ),
                    ),
                ),
            )

            val failure = runCatching {
                engine(database, preferences, firstBackend)
                    .synchronize(SESSION, SyncTrigger.PullToRefresh)
            }.exceptionOrNull()

            assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(preferences.session.first().pullCursor).isEqualTo(1)
            assertThat(database.babyDao().getByClientUuid(BABY_A)).isNotNull()
            assertThat(database.babyDao().getByClientUuid(BABY_B)).isNull()
            assertThat(database.babyDao().getByClientUuid(BABY_C)).isNull()
            assertThat(firstBackend.requests.map { it.first.pullCursor to it.second.pageIndex })
                .containsExactly(0L to 0, 1L to 1)
                .inOrder()

            database.close()
            dataScope.coroutineContext[Job]?.cancelAndJoin()
            dataScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            database = Room.databaseBuilder(context, LeziDatabase::class.java, databaseName).build()
            preferences = DataStoreSyncPreferences(
                PreferenceDataStoreFactory.create(scope = dataScope) { preferencesFile },
                tokens,
            )
            val restored = preferences.session.first()
            assertThat(restored.pullCursor).isEqualTo(1)
            val retryBackend = PullOnlyBackend(
                ArrayDeque(
                    listOf(
                        PullResult(
                            listOf(
                                remoteBaby(BABY_A, "宝宝 A", 100),
                                remoteBaby(BABY_B, "宝宝 B", 200),
                            ),
                            2,
                            GENERATION,
                            false,
                        ),
                    ),
                ),
            )

            val outcome = engine(database, preferences, retryBackend).synchronize(
                restored,
                SyncTrigger.PullToRefresh,
            )

            assertThat(outcome).isEqualTo(ReplicaSyncOutcome.Synchronized)
            assertThat(retryBackend.requests.map { it.first.pullCursor to it.second.pageIndex })
                .containsExactly(1L to 0)
            assertThat(preferences.session.first().pullCursor).isEqualTo(2)
            assertThat(database.babyDao().listAllIncludingDeleted().map { it.clientUuid })
                .containsExactly(BABY_A, BABY_B)
        } finally {
            database.close()
            dataScope.coroutineContext[Job]?.cancelAndJoin()
            context.deleteDatabase(databaseName)
            preferencesFile.delete()
        }
    }

    private fun engine(
        database: LeziDatabase,
        preferences: DataStoreSyncPreferences,
        backend: SyncBackend,
        conflictCache: ConflictSnapshotCacheDao = database.conflictSnapshotCacheDao(),
    ): ReplicaSyncEngine {
        val transactionRunner = DatabaseModule.transactionRunner(database)
        val mediaFiles = NoMediaFileStore
        return ReplicaSyncEngine(
            backend = backend,
            preferences = preferences,
            recordDao = database.recordDao(),
            carePlanDao = database.carePlanDao(),
            babyDao = database.babyDao(),
            mediaDao = database.mediaAssetDao(),
            customItemDao = database.customItemDao(),
            familyDao = database.familyDao(),
            clock = PolicyClock { 1_000 },
            mediaFiles = mediaFiles,
            immutableMediaSpool = NoMediaImmutableSpool,
            mediaFileCleanup = ReferenceAwareMediaFileCleanup(
                database.mediaAssetDao(),
                database.mediaReferenceDao(),
                mediaFiles,
                transactionRunner,
                MediaLocalPathGate(),
            ),
            transactionRunner = transactionRunner,
            carePlanAppliedListener = CarePlanFamilyAppliedListener { },
            fulfillmentCandidateDao = database.fulfillmentCandidateDao(),
            fulfillmentAuthoritySettlement = FulfillmentAuthoritySettlement(
                database.carePlanDao(),
                database.fulfillmentCandidateDao(),
                transactionRunner,
            ),
            requireRemoteAllowed = {},
            wakeObservationDao = database.wakeObservationDao(),
            conflictSummaryDao = database.conflictSummaryDao(),
            conflictSnapshotCacheDao = conflictCache,
            sourceRelationDao = database.sourceRelationDao(),
        )
    }

    private fun remoteBaby(clientUuid: String, nickname: String, updatedAt: Long) = SyncEntity(
        type = "baby",
        clientUuid = clientUuid,
        payloadJson =
            """{"nickname":"$nickname","sex":null,"birthday":"2024-01-01","birth_weight_grams":null,"avatar_media_uuid":null}""",
        updatedAt = updatedAt,
    )

    private class PullOnlyBackend(
        private val pages: ArrayDeque<PullResult>,
    ) : SyncBackend {
        val requests = mutableListOf<Pair<SyncSession, PullPageRequest>>()

        override suspend fun pull(session: SyncSession, page: PullPageRequest): PullResult {
            requests += session to page
            return pages.removeFirst().copy(pageIndex = page.pageIndex)
        }

        override suspend fun authenticatedHandshake(
            session: SyncSession,
        ): AuthenticatedSyncHandshake = HANDSHAKE

        override suspend fun create(
            baseUrl: String,
            deviceId: String,
            displayName: String?,
            createRequestId: String,
            bootstrapSecret: String?,
            familyName: String?,
        ): SessionBootstrapResult = unsupported()

        override suspend fun memberDirectory(
            session: SyncSession,
        ): FamilyMemberDirectorySnapshot = FamilyMemberDirectorySnapshot(
            DIRECTORY_GENERATION,
            emptyList<FamilyMember>(),
        )

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

        override suspend fun commitBundle(
            session: SyncSession,
            bundleId: String,
        ): BundleCommitResult = unsupported()

        private fun unsupported(): Nothing = error("unused backend seam")
    }

    private class FailingResetReceiptCache(
        private val delegate: ConflictSnapshotCacheDao,
    ) : ConflictSnapshotCacheDao by delegate {
        override suspend fun upsert(entity: ConflictSnapshotCacheEntity) {
            if (entity.conflictId.startsWith("replica-reset-receipt:")) {
                error("reset receipt write interrupted")
            }
            delegate.upsert(entity)
        }
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
        const val GENERATION = "generation-a"
        const val DIRECTORY_GENERATION =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val BABY_A = "00000000-0000-4000-8000-000000000201"
        const val BABY_B = "00000000-0000-4000-8000-000000000202"
        const val BABY_C = "00000000-0000-4000-8000-000000000203"
        val SESSION = SyncSession(
            familyId = "family-a",
            accessToken = "token",
            refreshToken = "refresh-token",
            deviceId = "device-a",
            membershipId = "membership-a",
            role = FamilyRole.Owner,
            pullGeneration = GENERATION,
            serverHost = "127.0.0.1",
        )
        val HANDSHAKE = AuthenticatedSyncHandshake(
            protocolVersion = AUTHENTICATED_SYNC_PROTOCOL_VERSION,
            serverVersion = "test",
            ready = true,
            capabilities = REQUIRED_CAUSAL_WIRE_CAPABILITIES,
            principal = SyncHandshakePrincipal(
                membershipId = SESSION.membershipId,
                deviceId = SESSION.deviceId,
                role = SESSION.role,
            ),
            directoryGeneration = DIRECTORY_GENERATION,
            limits = SyncHandshakeLimits(200, 9 * 1024 * 1024, 8 * 1024 * 1024, 500, 64, 10L),
            compression = SyncHandshakeCompression(setOf("gzip", "identity")),
            retryHints = SyncHandshakeRetryHints(retryAfter = true),
        )
    }
}
