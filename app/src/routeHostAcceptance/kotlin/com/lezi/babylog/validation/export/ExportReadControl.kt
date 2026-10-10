package com.lezi.babylog.validation.export

import com.lezi.babylog.core.database.ProjectedRecordEntity
import com.lezi.babylog.core.database.RecordWakeProjectionDao
import com.lezi.babylog.validation.host.HeldRouteRead
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/** Test-only control of an exact synthetic export range; every other DAO call is delegated. */
@Singleton
class ExportReadControl @Inject constructor() {
    data class Request(val babyId: Long, val startInclusive: Long, val endExclusive: Long)

    private data class NextRead(
        val request: Request,
        val held: HeldRouteRead,
        val fail: Boolean,
    )

    private val watched = CopyOnWriteArrayList<Request>()
    private val reads = CopyOnWriteArrayList<Request>()
    private val heldReads = CopyOnWriteArrayList<HeldRouteRead>()
    private val next = AtomicReference<NextRead?>(null)

    fun holdNext(request: Request, fail: Boolean = false): HeldRouteRead {
        val held = HeldRouteRead()
        check(next.compareAndSet(null, NextRead(request, held, fail)))
        watched.addIfAbsent(request)
        heldReads.add(held)
        return held
    }

    fun count(request: Request): Int = reads.count { it == request }

    fun wrap(delegate: RecordWakeProjectionDao): RecordWakeProjectionDao =
        object : RecordWakeProjectionDao by delegate {
            override suspend fun loadRecordProjection(
                babyId: Long,
                startInclusive: Long,
                endExclusive: Long,
            ): List<ProjectedRecordEntity> {
                val request = Request(babyId, startInclusive, endExclusive)
                if (request in watched) reads.add(request)
                val selected = next.get()
                if (selected != null && selected.request == request && next.compareAndSet(selected, null)) {
                    selected.held.pause()
                    if (selected.fail) throw IOException("synthetic export projection read failure")
                }
                return delegate.loadRecordProjection(babyId, startInclusive, endExclusive)
            }
        }

    fun reset() {
        next.set(null)
        heldReads.forEach(HeldRouteRead::release)
        heldReads.clear()
        watched.clear()
        reads.clear()
    }
}
