package com.lezi.babylog.sync.clear
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.BabyEntity
import com.lezi.babylog.core.database.CarePlanEntity
import com.lezi.babylog.core.database.DatabaseTransactionRunner
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.PendingReplicaCleanup
import com.lezi.babylog.core.database.LocalDataClearScope
import com.lezi.babylog.core.database.PendingReplicaCleanupStore
import com.lezi.babylog.core.database.RecordEntity
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import com.lezi.babylog.sync.LocalClearWorkflow
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.SyncSession
import com.lezi.babylog.sync.MemoryBabyDao
import com.lezi.babylog.sync.MemoryCarePlanDao
import com.lezi.babylog.sync.MemoryMediaDao
import com.lezi.babylog.sync.MemoryRecordDao
import com.lezi.babylog.sync.MemorySyncPreferences
import com.lezi.babylog.sync.TestMediaFileStore

@OptIn(ExperimentalCoroutinesApi::class)
// Shared harness extracted for ticket 08.


internal suspend fun LocalReplicaClearCoordinator.clear(
    scope: LocalDataClearScope,
    clearRoom: suspend () -> Unit,
): Result<Unit> = clear(
    scope = scope,
    workflow = testLocalClearWorkflow(clearRoom = clearRoom),
    recoverDomain = { null },
)

internal fun testLocalClearWorkflow(
    clearRoom: suspend () -> Unit = {},
    finishCommitted: suspend () -> Unit = {},
): LocalClearWorkflow = object : LocalClearWorkflow {
    override suspend fun <T> withLocalExclusion(block: suspend () -> T): T = block()
    override suspend fun clearRoom() = clearRoom.invoke()
    override suspend fun finishCommitted() = finishCommitted.invoke()
}

internal class ClearRig(
    session: SyncSession = joinedClearSession(),
    val mediaFiles: TestMediaFileStore = TestMediaFileStore(),
) {
    val barrier = Mutex()
    val preferences = MemorySyncPreferences(session)
    val records = MemoryRecordDao()
    val carePlans = MemoryCarePlanDao()
    val babies = MemoryBabyDao()
    val media = MemoryMediaDao()
    val transactions = ClearTransactionRunner()
    val pending = MemoryPendingReplicaCleanupStore { transactions.depth }
    val coordinator = newCoordinator()

    fun newCoordinator() = LocalReplicaClearCoordinator(
        barrier = barrier,
        preferences = preferences,
        babyDao = babies,
        mediaDao = media,
        mediaFiles = mediaFiles,
        transactionRunner = transactions,
        pendingStore = pending,
    )
}

internal class ClearTransactionRunner : DatabaseTransactionRunner {
    var runCount = 0
    var depth = 0
    val failRunNumbers = mutableSetOf<Int>()

    override suspend fun <T> run(block: suspend () -> T): T {
        runCount += 1
        val currentRun = runCount
        if (failRunNumbers.remove(currentRun)) {
            error("transaction $currentRun failed")
        }
        depth += 1
        return try {
            block()
        } finally {
            depth -= 1
        }
    }
}

internal class MemoryPendingReplicaCleanupStore(
    private val transactionDepth: () -> Int,
) : PendingReplicaCleanupStore {
    var pending: PendingReplicaCleanup? = null
    var stageFailure: Throwable? = null
    val stageDepths = mutableListOf<Int>()
    val deleteDepths = mutableListOf<Int>()

    override suspend fun load(): PendingReplicaCleanup? = pending

    override suspend fun stage(pending: PendingReplicaCleanup) {
        stageDepths += transactionDepth()
        stageFailure?.let { throw it }
        check(this.pending == null)
        this.pending = pending
    }

    override suspend fun delete() {
        deleteDepths += transactionDepth()
        pending = null
    }
}

internal class GatedDeleteMediaFileStore : TestMediaFileStore() {
    val deleteStarted = CompletableDeferred<Unit>()
    val allowDelete = CompletableDeferred<Unit>()

    override suspend fun delete(localUri: String) {
        deleteStarted.complete(Unit)
        allowDelete.await()
        super.delete(localUri)
    }
}

internal class TransactionObservingMediaFileStore(
    private val transactionDepth: () -> Int,
) : TestMediaFileStore() {
    val deleteDepths = mutableListOf<Int>()

    override suspend fun delete(localUri: String) {
        deleteDepths += transactionDepth()
        super.delete(localUri)
    }
}

internal fun media(
    clientUuid: String,
    kind: String,
    localUri: String,
    carePlanId: Long? = null,
) = MediaAssetEntity(
    clientUuid = clientUuid,
    kind = kind,
    recordId = if (carePlanId == null && kind == "log") 1 else null,
    carePlanId = carePlanId,
    babyId = 1L.takeIf { kind == "avatar" },
    localUri = localUri,
    createdAt = 1,
)

internal fun record(
    babyId: Long = 1,
    payloadJson: String = "{}",
) = RecordEntity(
    clientUuid = "record-local",
    babyId = babyId,
    type = "formula",
    timestamp = 120,
    payloadJson = payloadJson,
    updatedAt = 120,
)

internal fun carePlan(payloadJson: String) = CarePlanEntity(
    id = 1,
    clientUuid = "plan-local",
    babyId = 1,
    type = "formula",
    scheduledAt = 120,
    scheduledZoneId = "Asia/Shanghai",
    payloadJson = payloadJson,
    updatedAt = 120,
)

internal fun baby(avatarPath: String?) = BabyEntity(
    familyId = 1,
    nickname = "本地宝宝",
    birthdayEpochDay = 20_000,
    themeColorArgb = 0,
    clientUuid = "baby-local",
    updatedAt = 100,
    avatarPath = avatarPath,
)

internal fun joinedClearSession() = SyncSession(
    familyId = "family-a",
    accessToken = "token",
    deviceId = "device-a",
    role = FamilyRole.Owner,
    serverHost = "192.168.1.20",
    serverPort = 8787,
)
