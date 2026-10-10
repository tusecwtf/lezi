package com.lezi.babylog.domain.carelog

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.*
import com.lezi.babylog.core.database.causal.WakeObservationDao
import com.lezi.babylog.core.database.causal.WakeObservationEntity
import com.lezi.babylog.core.database.fulfillment.FulfillmentAuthoritySettlement
import com.lezi.babylog.core.datastore.SettingsDataSource
import com.lezi.babylog.core.model.CarePlan
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.CreateBabyInput
import com.lezi.babylog.domain.careplan.ReminderCleanupPort
import com.lezi.babylog.domain.localdata.CalendarReminderMutationGuard
import com.lezi.babylog.domain.localdata.LocalDataMutationEpoch
import com.lezi.babylog.sync.NoOpSyncPort
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.session.PolicyClock
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*

/**
 * A disposable, file-backed current Room schema, with production provider callbacks,
 * DataStore, path guards, domain coordinators, and real photo bytes. No business database
 * is opened. Only notification/reminder observers and named persistence barriers differ.
 * Closing/reopening this rig is NOT an Android process-death simulation.
 */
internal class CareLogRoomAcceptanceRig : AutoCloseable {
    private val application = InstrumentationRegistry.getInstrumentation().targetContext
    val root: File = File(application.cacheDir, "carelog-room-${java.util.UUID.randomUUID()}")
        .apply { check(mkdirs()) }
    private val databaseFile = File(root, "lezi.db")
    private val context = object : ContextWrapper(application) {
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        override fun getDatabasePath(name: String): File = File(root, File(name).name)
        override fun openOrCreateDatabase(
            name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
        ): SQLiteDatabase = super.openOrCreateDatabase(getDatabasePath(name).path, mode, factory)
        override fun openOrCreateDatabase(
            name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
            errorHandler: DatabaseErrorHandler?,
        ): SQLiteDatabase = super.openOrCreateDatabase(
            getDatabasePath(name).path, mode, factory, errorHandler,
        )
    }
    private val preferencesJob = SupervisorJob()
    private val settings = SettingsDataSource(
        PreferenceDataStoreFactory.create(scope = CoroutineScope(preferencesJob + Dispatchers.IO)) {
            File(root, "settings.preferences_pb")
        },
    )
    val boundaries = CareLogRoomBoundaries()
    val notificationCount = AtomicInteger()
    private val activeTransactions = AtomicInteger()
    private val notifications = object : SyncPort by NoOpSyncPort() {
        override fun notifyLocalChanges() {
            check(activeTransactions.get() == 0) { "sync notified before outer Room commit" }
            notificationCount.incrementAndGet()
        }
        override fun requestSync(trigger: SyncTrigger) = notifyLocalChanges()
    }
    var db: LeziDatabase = DatabaseModule.provideDatabase(context)
        private set
    lateinit var care: CareLog
        private set

    init { wireCareLog() }

    private fun wireCareLog() {
        val realTransactions = DatabaseModule.transactionRunner(db)
        val transactions = object : DatabaseTransactionRunner {
            override suspend fun <T> run(block: suspend () -> T): T {
                if (currentCoroutineContext()[InsideCareLogRoomTransaction] != null) {
                    return realTransactions.run(block)
                }
                boundaries.hit("transaction.before")
                activeTransactions.incrementAndGet()
                val result = try {
                    realTransactions.run {
                        withContext(InsideCareLogRoomTransaction()) {
                            val value = block()
                            boundaries.hit("transaction.beforeCommit")
                            value
                        }
                    }
                } finally {
                    activeTransactions.decrementAndGet()
                }
                boundaries.hit("transaction.afterCommit")
                return result
            }
        }
        val rawRecords = db.recordDao()
        val records = object : RecordDao by rawRecords {
            override suspend fun upsert(record: RecordEntity): Long = rawRecords.upsert(record).also {
                boundaries.hit("record.upsert")
            }
            override suspend fun update(record: RecordEntity) {
                rawRecords.update(record)
                boundaries.hit("record.update")
            }
        }
        val policy = DatabaseModule.privateSpoolPathPolicy(context)
        val rawMedia = DatabaseModule.mediaAssetDao(db, policy, transactions)
        val media = object : MediaAssetDao by rawMedia {
            override suspend fun upsert(asset: MediaAssetEntity): Long = rawMedia.upsert(asset).also {
                boundaries.hit("media.upsert")
            }
            override suspend fun update(asset: MediaAssetEntity) {
                rawMedia.update(asset)
                boundaries.hit("media.update")
            }
        }
        val rawWakes = db.wakeObservationDao()
        val wakes = object : WakeObservationDao by rawWakes {
            override suspend fun upsert(entity: WakeObservationEntity): Long = rawWakes.upsert(entity).also {
                boundaries.hit("wake.upsert")
            }
            override suspend fun update(entity: WakeObservationEntity) {
                rawWakes.update(entity)
                boundaries.hit("wake.update")
            }
        }
        val rawPlans = db.carePlanDao()
        val plans = object : CarePlanDao by rawPlans {
            override suspend fun upsert(plan: CarePlanEntity): Long = rawPlans.upsert(plan).also {
                boundaries.hit("plan.upsert")
            }
            override suspend fun update(plan: CarePlanEntity) {
                rawPlans.update(plan)
                boundaries.hit("plan.update")
            }
        }
        val rawCandidates = db.fulfillmentCandidateDao()
        val candidates = object : FulfillmentCandidateDao by rawCandidates {
            override suspend fun upsert(candidate: FulfillmentCandidateEntity): Long =
                rawCandidates.upsert(candidate).also { boundaries.hit("candidate.upsert") }
            override suspend fun update(candidate: FulfillmentCandidateEntity) {
                rawCandidates.update(candidate)
                boundaries.hit("candidate.update")
            }
        }
        care = CareLog(
            babyDao = DatabaseModule.babyDao(db, policy, transactions),
            recordDao = records,
            carePlanDao = plans,
            customItemDao = db.customItemDao(),
            localUserDao = db.localUserDao(),
            familyDao = db.familyDao(),
            membershipDao = db.membershipDao(),
            mediaAssetDao = media,
            settings = settings,
            syncPort = notifications,
            reminderCleanup = object : ReminderCleanupPort {
                override suspend fun scheduleCarePlan(plan: CarePlan): Boolean = false
                override suspend fun cancelCarePlan(carePlanId: Long) = Unit
            },
            transactionRunner = transactions,
            fulfillmentCandidateDao = candidates,
            fulfillmentAuthoritySettlement = FulfillmentAuthoritySettlement(plans, candidates, transactions),
            calendarReminderMutationGuard = CalendarReminderMutationGuard(),
            clock = PolicyClock { System.currentTimeMillis() },
            mediaPathGate = MediaLocalPathGate(),
            localDataMutationEpoch = LocalDataMutationEpoch(),
            wakeObservationDao = wakes,
            conflictSummaryDao = db.conflictSummaryDao(),
            conflictSnapshotCacheDao = db.conflictSnapshotCacheDao(),
            sourceRelationDao = db.sourceRelationDao(),
            recordWakeProjectionDao = db.timelineWindowDao(),
        )
    }

    suspend fun createBaby(): Long = care.createBaby(CreateBabyInput("Room fixture", birthdayEpochDay = 1))

    fun photo(name: String): File = File(context.filesDir, name).also { file ->
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(0xFF000000.toInt() or (name.hashCode() and 0x00FFFFFF))
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }

    suspend fun snapshot(): CareLogRoomSnapshot = CareLogRoomSnapshot(
        db.recordDao().listAllIncludingDeleted(),
        db.wakeObservationDao().listAllIncludingDeleted(),
        db.mediaAssetDao().listAllIncludingDeleted(),
        db.carePlanDao().listAllIncludingDeleted(),
        db.fulfillmentCandidateDao().listAllIncludingDeleted(),
    )

    fun assertProductionSchema() {
        assertThat(databaseFile.isFile).isTrue()
        assertThat(db.openHelper.writableDatabase.version).isEqualTo(LeziDatabase.VERSION)
        db.openHelper.readableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type='trigger' AND name LIKE 'media_assets_owner_%'",
        ).use { assertThat(it.count).isEqualTo(2) }
    }

    fun reopen() {
        boundaries.reset()
        db.close()
        check(databaseFile.isFile) { "test must reopen the same persistent Room file" }
        db = DatabaseModule.provideDatabase(context)
        wireCareLog()
    }

    override fun close() {
        boundaries.reset()
        runBlocking { preferencesJob.cancelAndJoin() }
        db.close()
        check(root.deleteRecursively()) { "could not remove the isolated test fixture" }
    }
}

internal data class CareLogRoomSnapshot(
    val records: List<RecordEntity>,
    val wakes: List<WakeObservationEntity>,
    val media: List<MediaAssetEntity>,
    val plans: List<CarePlanEntity>,
    val candidates: List<FulfillmentCandidateEntity>,
)

/** One writer is instrumented at a time; another public CareLog can commit while before is paused. */
internal class CareLogRoomBoundaries {
    val trace = mutableListOf<String>()
    private val counts = mutableMapOf<String, Int>()
    var onBoundary: suspend (String) -> Unit = {}
    suspend fun hit(name: String) {
        val index = (counts[name] ?: 0) + 1
        counts[name] = index
        val point = "$name#$index"
        trace += point
        onBoundary(point)
    }
    fun reset() {
        trace.clear()
        counts.clear()
        onBoundary = {}
    }
}

private class InsideCareLogRoomTransaction : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<InsideCareLogRoomTransaction>
}
