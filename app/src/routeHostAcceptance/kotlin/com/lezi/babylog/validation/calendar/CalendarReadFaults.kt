package com.lezi.babylog.validation.calendar

import com.lezi.babylog.core.database.FulfillmentCandidateDao
import com.lezi.babylog.core.database.FulfillmentCandidateEntity
import com.lezi.babylog.validation.host.HeldRouteRead
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/** Faults or holds only a selected audit's postcommit reads; Room data and writes stay real. */
@Singleton
class CalendarReadFaults @Inject constructor() {
    enum class Mode { None, List, Detail }
    @Volatile var mode = Mode.None
    @Volatile var candidateUuid = ""
    val detailReads = AtomicInteger()
    val listFailures = AtomicInteger()
    val detailFailures = AtomicInteger()
    private val heldDetailRead = AtomicReference<HeldRouteRead?>()

    fun holdNextPostCommitDetailRead(): HeldRouteRead {
        check(candidateUuid.isNotBlank() && mode == Mode.None)
        return HeldRouteRead().also {
            check(heldDetailRead.compareAndSet(null, it)) { "An audit detail read is already armed" }
        }
    }

    fun clearHeldRead() {
        heldDetailRead.getAndSet(null)?.release()
    }

    fun wrap(delegate: FulfillmentCandidateDao): FulfillmentCandidateDao =
        object : FulfillmentCandidateDao by delegate {
            override suspend fun listConflictNotAdoptedForCarePlan(carePlanClientUuid: String): List<FulfillmentCandidateEntity> {
                val rows = delegate.listConflictNotAdoptedForCarePlan(carePlanClientUuid)
                if (mode == Mode.List && rows.any { it.clientUuid == candidateUuid && it.convertedRecordClientUuid.isNotBlank() }) {
                    listFailures.incrementAndGet()
                    throw IOException("synthetic postcommit audit list read failure")
                }
                return rows
            }

            override suspend fun getByClientUuid(clientUuid: String): FulfillmentCandidateEntity? {
                val row = delegate.getByClientUuid(clientUuid)
                if (clientUuid == candidateUuid) {
                    detailReads.incrementAndGet()
                    // Both convert prechecks see the empty pointer. The postcommit
                    // detail read can fail only after the real transaction published it.
                    if (mode == Mode.Detail && row?.convertedRecordClientUuid?.isNotBlank() == true) {
                        detailFailures.incrementAndGet()
                        throw IOException("synthetic postcommit audit detail read failure")
                    }
                    // Capture the real Room result, then hold just this selected
                    // postcommit detail read. Unconverted prechecks and newer
                    // candidate detail reads cannot consume the gate.
                    if (row?.convertedRecordClientUuid?.isNotBlank() == true) {
                        heldDetailRead.getAndSet(null)?.pause()
                    }
                }
                return row
            }
        }
}
