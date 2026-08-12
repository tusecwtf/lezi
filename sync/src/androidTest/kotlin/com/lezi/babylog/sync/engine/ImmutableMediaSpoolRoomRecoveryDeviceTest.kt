package com.lezi.babylog.sync.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.BabyEntity
import com.lezi.babylog.core.database.CarePlanEntity
import com.lezi.babylog.core.database.DatabaseModule
import com.lezi.babylog.core.database.DatabaseTransactionRunner
import com.lezi.babylog.core.database.LeziDatabase
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.sync.backend.AtomicBundleDraft
import com.lezi.babylog.sync.backend.AuthenticatedSyncHandshake
import com.lezi.babylog.sync.backend.BundleCommitResult
import com.lezi.babylog.sync.backend.BundleStageStatus
import com.lezi.babylog.sync.backend.CausalCommitBatchResult
import com.lezi.babylog.sync.backend.CausalCommitStatus
import com.lezi.babylog.sync.backend.CausalCommitUnitResult
import com.lezi.babylog.sync.backend.CausalMediaPreimageReceipt
import com.lezi.babylog.sync.backend.CausalMutationUnit
import com.lezi.babylog.sync.backend.DisplayNameUpdateResult
import com.lezi.babylog.sync.backend.FamilyMemberDirectorySnapshot
import com.lezi.babylog.sync.backend.PullPageRequest
import com.lezi.babylog.sync.backend.PullResult
import com.lezi.babylog.sync.backend.SessionBootstrapResult
import com.lezi.babylog.sync.backend.SyncBackend
import com.lezi.babylog.sync.media.AndroidSyncMediaFileStore
import com.lezi.babylog.sync.media.FileImmutableMediaSpool
import com.lezi.babylog.sync.media.ImmutableMediaSpool
import com.lezi.babylog.sync.media.ImmutableMediaSpoolFaultInjector
import com.lezi.babylog.sync.media.ImmutableMediaSpoolFaultPoint
import com.lezi.babylog.sync.media.ImmutableMediaSpoolGroup
import com.lezi.babylog.sync.media.ImmutableMediaSpoolItem
import com.lezi.babylog.sync.media.ImmutableMediaSpoolRecovery
import com.lezi.babylog.sync.media.ImmutableMediaSpoolSource
import com.lezi.babylog.sync.media.SyncMediaUploadSource
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.SyncSession
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImmutableMediaSpoolRoomRecoveryDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databaseName = "immutable-media-spool-room.db"
    private val spoolRoot = File(context.filesDir, "h18-device-spool")
    private val sourceDirectory = File(context.filesDir, "record-media/h18-device")

    @After
    fun cleanup() {
        context.deleteDatabase(databaseName)
        spoolRoot.walkBottomUp().forEach(File::delete)
        sourceDirectory.walkBottomUp().forEach(File::delete)
    }

    /** Production Android adapter + production spool + real Room/CausalSettlement transaction. */
    @Test
    fun partialSpoolRollbackAndReopenPublishWithoutMediaPermissionOrSource() = runBlocking {
        assertThat(context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES))
            .isNotEqualTo(PackageManager.PERMISSION_GRANTED)
        sourceDirectory.mkdirs()
        val sourceFile = File(sourceDirectory, "source.jpg")
        Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888).also { bitmap ->
            sourceFile.outputStream().use { output ->
                assertThat(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)).isTrue()
            }
            bitmap.recycle()
        }
        val mediaFiles = AndroidSyncMediaFileStore(context)
        var database = database()
        try {
            seedFacts(database, sourceFile)
            val faultingSpool = fileSpool(
                mediaFiles,
                ImmutableMediaSpoolFaultInjector { point ->
                    if (point == ImmutableMediaSpoolFaultPoint.AfterMediaTempSync) {
                        error("injected process death after durable media temp")
                    }
                },
            )
            val firstFailure = runCatching {
                settlement(database, DeviceCausalBackend(), faultingSpool)
                    .settle(SESSION, listOf(candidate(database)))
            }.exceptionOrNull()
            assertThat(firstFailure).hasMessageThat().contains("injected process death")
            assertThat(database.conflictSnapshotCacheDao().getFrozenMediaSpoolManifest(MUTATION_ID))
                .isNull()
            assertThat(sourceFile.delete()).isTrue()

            val recoveredSpool = fileSpool(mediaFiles)
            val rollbackRunner = object : DatabaseTransactionRunner {
                override suspend fun <T> run(block: suspend () -> T): T =
                    database.runInTransaction<T> {
                        runBlocking {
                            val result = block()
                            if (database.conflictSnapshotCacheDao()
                                    .getFrozenMediaSpoolManifest(MUTATION_ID) != null
                            ) {
                                error("injected Room rollback after manifest write")
                            }
                            result
                        }
                    }
            }
            val rollbackFailure = runCatching {
                settlement(
                    database = database,
                    backend = DeviceCausalBackend(),
                    spool = recoveredSpool,
                    transactionRunner = rollbackRunner,
                ).settle(SESSION, listOf(candidate(database)))
            }.exceptionOrNull()
            assertThat(rollbackFailure).hasMessageThat().contains("injected Room rollback")
            assertThat(database.conflictSnapshotCacheDao().getFrozenMediaSpoolManifest(MUTATION_ID))
                .isNull()
            assertThat(recoveredSpool.recoverGroup(MUTATION_ID))
                .isInstanceOf(ImmutableMediaSpoolRecovery.Complete::class.java)

            database.close()
            database = database()
            val backend = DeviceCausalBackend()
            val reopenedSpool = fileSpool(mediaFiles)
            val terminalRollbackRunner = object : DatabaseTransactionRunner {
                override suspend fun <T> run(block: suspend () -> T): T =
                    database.runInTransaction<T> {
                        runBlocking {
                            val result = block()
                            val row = database.conflictSnapshotCacheDao()
                                .getFrozenMediaSpoolManifest(MUTATION_ID)
                            val phase = row?.let {
                                decodeCausalMediaSettlementOrNull(it.payloadJson)?.phase
                            }
                            if (phase == CausalMediaSettlementPhase.CleanupAccepted) {
                                error("injected terminal transaction rollback")
                            }
                            result
                        }
                    }
            }
            val terminalFailure = runCatching {
                settlement(
                    database = database,
                    backend = backend,
                    spool = reopenedSpool,
                    transactionRunner = terminalRollbackRunner,
                ).settle(SESSION, listOf(candidate(database)))
            }.exceptionOrNull()
            assertThat(terminalFailure).hasMessageThat().contains("terminal transaction rollback")
            val unknownRow = requireNotNull(
                database.conflictSnapshotCacheDao().getFrozenMediaSpoolManifest(MUTATION_ID),
            )
            assertThat(decodeCausalMediaSettlementOrNull(unknownRow.payloadJson)?.phase)
                .isEqualTo(CausalMediaSettlementPhase.CommitUnknown)
            assertThat(database.recordDao().getByClientUuid(RECORD_ID)?.syncDirty).isTrue()
            assertThat(reopenedSpool.recoverGroup(MUTATION_ID))
                .isInstanceOf(ImmutableMediaSpoolRecovery.Complete::class.java)

            database.close()
            database = database()
            settlement(database, backend, reopenedSpool)
                .settle(SESSION, listOf(candidate(database)))

            val manifestRow = database.conflictSnapshotCacheDao()
                .getFrozenMediaSpoolManifest(MUTATION_ID)
            assertThat(manifestRow).isNull()
            assertThat(backend.uploadedBytes).hasSize(1)
            assertThat(reopenedSpool.recoverGroup(MUTATION_ID)).isNull()
            assertThat(database.recordDao().getByClientUuid(RECORD_ID)?.syncDirty).isFalse()
        } finally {
            database.close()
        }
    }

    /** Real Room proof for the H22 Baby + avatar frozen-envelope transaction. */
    @Test
    fun babyAvatarCommitsExactSpoolBytesAndSettlesRoomAtomically() = runBlocking {
        sourceDirectory.mkdirs()
        val sourceFile = File(sourceDirectory, "baby-avatar.jpg")
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also { bitmap ->
            sourceFile.outputStream().use { output ->
                assertThat(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)).isTrue()
            }
            bitmap.recycle()
        }
        val exactBytes = sourceFile.readBytes()
        val database = database()
        try {
            val babyId = database.babyDao().upsert(
                BabyEntity(
                    familyId = 1,
                    nickname = "头像宝宝",
                    birthdayEpochDay = 1,
                    themeColorArgb = 0,
                    avatarMediaUuid = AVATAR_MEDIA_ID,
                    avatarPath = sourceFile.relativeTo(context.filesDir).path,
                    clientUuid = AVATAR_BABY_ID,
                    updatedAt = 200,
                    syncDirty = true,
                    familyAuthority = true,
                    baseVersion = "v-baby-avatar-0",
                    mutationId = AVATAR_MUTATION_ID,
                ),
            )
            database.mediaAssetDao().upsert(
                MediaAssetEntity(
                    babyId = babyId,
                    clientUuid = AVATAR_MEDIA_ID,
                    kind = "avatar",
                    localUri = sourceFile.relativeTo(context.filesDir).path,
                    mime = "image/jpeg",
                    createdAt = 200,
                    updatedAt = 200,
                    syncDirty = true,
                ),
            )
            val backend = DeviceCausalBackend()

            settlement(
                database = database,
                backend = backend,
                spool = fileSpool(AndroidSyncMediaFileStore(context)),
            ).settle(
                SESSION,
                listOf(
                    PublishCandidate(
                        planId = 1,
                        entityType = "baby",
                        clientUuid = AVATAR_BABY_ID,
                        payloadJson = "{}",
                        updatedAt = 200,
                    ),
                    PublishCandidate(
                        planId = 2,
                        entityType = "media",
                        clientUuid = AVATAR_MEDIA_ID,
                        payloadJson = "{}",
                        updatedAt = 200,
                        localMediaUri = sourceFile.relativeTo(context.filesDir).path,
                    ),
                ),
            )

            assertThat(backend.uploadedBytes.single()).isEqualTo(exactBytes)
            assertThat(database.babyDao().getByClientUuid(AVATAR_BABY_ID)?.syncDirty).isFalse()
            assertThat(database.mediaAssetDao().getByClientUuid(AVATAR_MEDIA_ID)?.syncDirty)
                .isFalse()
            assertThat(
                database.conflictSnapshotCacheDao()
                    .getFrozenMediaSpoolManifest(AVATAR_MUTATION_ID),
            ).isNull()
        } finally {
            database.close()
        }
    }

    /** Real Room success-path proof for H23 CarePlan attachment settlement. */
    @Test
    fun carePlanThreeAttachmentsCommitExactSpoolBytesAndSettleRoom() = runBlocking {
        sourceDirectory.mkdirs()
        val sourceFiles = (0 until 3).map { index ->
            File(sourceDirectory, "care-plan-$index.jpg").also { file ->
                Bitmap.createBitmap(index + 2, 2, Bitmap.Config.ARGB_8888).also { bitmap ->
                    file.outputStream().use { output ->
                        assertThat(bitmap.compress(Bitmap.CompressFormat.JPEG, 90 + index, output))
                            .isTrue()
                    }
                    bitmap.recycle()
                }
            }
        }
        val exactBytes = sourceFiles.map(File::readBytes)
        val database = database()
        try {
            val babyId = database.babyDao().upsert(
                BabyEntity(
                    familyId = 1,
                    nickname = "计划宝宝",
                    birthdayEpochDay = 1,
                    themeColorArgb = 0,
                    clientUuid = CARE_PLAN_BABY_ID,
                    updatedAt = 300,
                    syncDirty = false,
                    familyAuthority = true,
                    baseVersion = "v-care-plan-baby",
                ),
            )
            val planId = database.carePlanDao().upsert(
                CarePlanEntity(
                    clientUuid = CARE_PLAN_ID,
                    babyId = babyId,
                    type = "formula",
                    scheduledAt = 9_000_000_000_000,
                    scheduledZoneId = "Asia/Shanghai",
                    payloadJson = """{"amount_ml":120}""",
                    createdByMembershipId = "membership-a",
                    updatedAt = 300,
                    syncDirty = true,
                    baseVersion = "v-care-plan-0",
                    mutationId = CARE_PLAN_MUTATION_ID,
                ),
            )
            val mediaIds = sourceFiles.mapIndexed { index, file ->
                "00000000-0000-4000-8000-00000000029${index + 1}".also { mediaUuid ->
                    database.mediaAssetDao().upsert(
                        MediaAssetEntity(
                            carePlanId = planId,
                            clientUuid = mediaUuid,
                            kind = "log",
                            localUri = file.relativeTo(context.filesDir).path,
                            mime = "image/jpeg",
                            createdAt = 300,
                            updatedAt = 300,
                            syncDirty = true,
                        ),
                    )
                }
            }
            val backend = DeviceCausalBackend()

            settlement(
                database = database,
                backend = backend,
                spool = fileSpool(AndroidSyncMediaFileStore(context)),
            ).settle(
                SESSION,
                listOf(
                    PublishCandidate(
                        planId = planId,
                        entityType = "care_plan",
                        clientUuid = CARE_PLAN_ID,
                        payloadJson = "{}",
                        updatedAt = 300,
                    ),
                ) + mediaIds.mapIndexed { index, mediaUuid ->
                    PublishCandidate(
                        planId = planId + index + 1L,
                        entityType = "media",
                        clientUuid = mediaUuid,
                        payloadJson = "{}",
                        updatedAt = 300,
                        localMediaUri = sourceFiles[index].relativeTo(context.filesDir).path,
                    )
                },
            )

            assertThat(backend.uploadedBytes).hasSize(3)
            backend.uploadedBytes.zip(exactBytes).forEach { (actual, expected) ->
                assertThat(actual).isEqualTo(expected)
            }
            assertThat(database.carePlanDao().getByClientUuid(CARE_PLAN_ID)?.syncDirty).isFalse()
            mediaIds.forEach { mediaUuid ->
                assertThat(database.mediaAssetDao().getByClientUuid(mediaUuid)?.syncDirty)
                    .isFalse()
            }
            assertThat(
                database.conflictSnapshotCacheDao()
                    .getFrozenMediaSpoolManifest(CARE_PLAN_MUTATION_ID),
            ).isNull()
        } finally {
            database.close()
        }
    }

    private suspend fun seedFacts(database: LeziDatabase, sourceFile: File) {
        val babyId = database.babyDao().upsert(
            BabyEntity(
                familyId = 1,
                nickname = "宝宝",
                birthdayEpochDay = 1,
                themeColorArgb = 0,
                clientUuid = BABY_ID,
                updatedAt = 1,
                syncDirty = false,
                familyAuthority = true,
                baseVersion = "v-baby",
            ),
        )
        val recordId = database.recordDao().upsert(
            RecordEntity(
                clientUuid = RECORD_ID,
                babyId = babyId,
                type = "formula",
                timestamp = 100,
                payloadJson = """{"amount_ml":90}""",
                schemaVersion = 2,
                updatedAt = 100,
                syncDirty = true,
                baseVersion = "v-record-0",
                mutationId = MUTATION_ID,
            ),
        )
        database.mediaAssetDao().upsert(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = MEDIA_ID,
                kind = "log",
                localUri = sourceFile.relativeTo(context.filesDir).path,
                mime = "image/jpeg",
                createdAt = 100,
                updatedAt = 100,
                syncDirty = true,
            ),
        )
    }

    private suspend fun candidate(database: LeziDatabase): PublishCandidate {
        val record = requireNotNull(database.recordDao().getByClientUuid(RECORD_ID))
        return PublishCandidate(
            planId = record.id,
            entityType = "record",
            clientUuid = record.clientUuid,
            payloadJson = record.payloadJson,
            updatedAt = record.updatedAt,
            deletedAt = record.deletedAt,
        )
    }

    private fun settlement(
        database: LeziDatabase,
        backend: SyncBackend,
        spool: ImmutableMediaSpool,
        transactionRunner: DatabaseTransactionRunner = DatabaseModule.transactionRunner(database),
    ) = CausalSettlement(
        backend = backend,
        recordDao = database.recordDao(),
        carePlanDao = database.carePlanDao(),
        babyDao = database.babyDao(),
        mediaDao = database.mediaAssetDao(),
        customItemDao = database.customItemDao(),
        wakeObservationDao = database.wakeObservationDao(),
        conflictSummaryDao = database.conflictSummaryDao(),
        conflictSnapshotCacheDao = database.conflictSnapshotCacheDao(),
        immutableMediaSpool = spool,
        transactionRunner = transactionRunner,
        requireRemoteAllowed = {},
    )

    private fun database(): LeziDatabase = Room.databaseBuilder(
        context,
        LeziDatabase::class.java,
        databaseName,
    ).build()

    private fun fileSpool(
        files: AndroidSyncMediaFileStore,
        faults: ImmutableMediaSpoolFaultInjector = ImmutableMediaSpoolFaultInjector {},
    ) = FileImmutableMediaSpool(
        mediaFiles = files,
        root = spoolRoot,
        capacityBytes = 64L * 1024L * 1024L,
        slotReservationBytes = 8L * 1024L * 1024L,
        faultInjector = faults,
    )
}

private class DeviceCausalBackend : SyncBackend {
    val uploadedBytes = mutableListOf<ByteArray>()

    override fun supportsCausalWire() = true

    override suspend fun putCausalMediaPreimage(
        session: SyncSession,
        mediaUuid: String,
        source: SyncMediaUploadSource,
        sha256: String,
    ): CausalMediaPreimageReceipt {
        uploadedBytes += source.readAll()
        return CausalMediaPreimageReceipt(
            mediaUuid = mediaUuid,
            status = "staged",
            byteSize = source.contentLength,
            sha256 = sha256,
            expiresAtEpochSeconds = Long.MAX_VALUE,
        )
    }

    override suspend fun causalCommit(
        session: SyncSession,
        units: List<CausalMutationUnit>,
    ) = batch(session, units, CausalCommitStatus.ACCEPTED)

    private fun batch(
        session: SyncSession,
        units: List<CausalMutationUnit>,
        status: String,
    ): CausalCommitBatchResult = CausalCommitBatchResult(
        generation = session.pullGeneration,
        results = units.map { unit ->
            CausalCommitUnitResult(
                status = status,
                mutationId = unit.mutationId,
                requestHash = causalMutationContentHash(unit),
                replay = false,
                stableVersionId = "v-record-1",
                stableRootJson = unit.rootJson,
                stableMedia = unit.media,
                stableDeleted = false,
                stableDeletedAt = null,
            )
        },
    )

    override suspend fun create(
        baseUrl: String,
        deviceId: String,
        displayName: String?,
        createRequestId: String,
        bootstrapSecret: String?,
        familyName: String?,
    ): SessionBootstrapResult = unsupported()

    override suspend fun pull(session: SyncSession, page: PullPageRequest): PullResult = unsupported()
    override suspend fun authenticatedHandshake(session: SyncSession): AuthenticatedSyncHandshake = unsupported()
    override suspend fun memberDirectory(session: SyncSession): FamilyMemberDirectorySnapshot = unsupported()
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

    override suspend fun getMedia(session: SyncSession, clientUuid: String): ByteArray = unsupported()
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

internal object NoMediaImmutableSpool : ImmutableMediaSpool {
    override suspend fun freezeGroup(
        mutationId: String,
        sources: List<ImmutableMediaSpoolSource>,
    ): ImmutableMediaSpoolGroup = error("no media expected")

    override suspend fun recoverGroup(mutationId: String): ImmutableMediaSpoolRecovery? = null

    override suspend fun discardGroup(mutationId: String) = Unit

    override suspend fun open(
        mutationId: String,
        item: ImmutableMediaSpoolItem,
    ): SyncMediaUploadSource = error("no media expected")

    override suspend fun recoverAndSweep(
        retainedMutationIds: Set<String>,
    ): Map<String, ImmutableMediaSpoolRecovery> = emptyMap()
}

private fun SyncMediaUploadSource.readAll(): ByteArray = openStream().use { it.readBytes() }

private val SESSION = SyncSession(
    familyId = "family-device",
    accessToken = "token",
    deviceId = "device-a",
    role = FamilyRole.Owner,
    pullGeneration = "generation-a",
    membershipId = "membership-a",
    serverHost = "192.168.1.20",
    serverPort = 8765,
)
private const val MUTATION_ID = "00000000-0000-4000-8000-000000000018"
private const val MEDIA_ID = "00000000-0000-4000-8000-000000000180"
private const val RECORD_ID = "00000000-0000-4000-8000-000000000181"
private const val BABY_ID = "00000000-0000-4000-8000-000000000182"
private const val AVATAR_MUTATION_ID = "00000000-0000-4000-8000-000000000028"
private const val AVATAR_MEDIA_ID = "00000000-0000-4000-8000-000000000280"
private const val AVATAR_BABY_ID = "00000000-0000-4000-8000-000000000282"
private const val CARE_PLAN_MUTATION_ID = "00000000-0000-4000-8000-000000000029"
private const val CARE_PLAN_ID = "00000000-0000-4000-8000-000000000290"
private const val CARE_PLAN_BABY_ID = "00000000-0000-4000-8000-000000000292"
