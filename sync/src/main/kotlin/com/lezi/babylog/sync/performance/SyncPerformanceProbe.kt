package com.lezi.babylog.sync.performance

/** Fixed process-wide stages, not request, family, thread, or user identifiers. */
internal enum class SyncPerformanceStage(val id: Int) {
    ReplicaRound(0),
    Handshake(1),
    PullRequest(2),
    StageMediaDownload(3),
    ApplyPage(4),
    MissingMediaDownload(5),
    CaptureLocalChanges(6),
    SettlePublish(7),
    CleanupMedia(8),
}

internal data class SyncPerformanceSample(val durationNanos: Long, val succeeded: Int)

internal data class SyncPerformanceStageSnapshot(
    val stageId: Int,
    val completedCount: Long,
    val failedCount: Long,
    val activeSpans: Int,
    val totalNanos: Long,
    val maxNanos: Long,
    /** At most 256 completed samples, oldest retained sample first. */
    val samples: List<SyncPerformanceSample>,
)

/**
 * Explicit, process-wide diagnostic capture. Off until [startCapture] is called.
 * No automatic logging, persistence, scheduling, or payload/identity inputs.
 * Stages overlap: round and nested phase times must not be added together.
 */
internal object SyncPerformanceProbe {
    const val SAMPLES_PER_STAGE = 256

    @Volatile
    internal var active: Capture? = null
        private set

    /** Starts a fresh bounded capture; unfinished work from the old one is discarded. */
    @Synchronized
    fun startCapture(nanoTime: () -> Long = System::nanoTime) {
        active?.stop()
        active = Capture(nanoTime)
    }

    /** Completed samples only; activeSpans reports work still running at this snapshot. */
    @Synchronized
    fun snapshot(): List<SyncPerformanceStageSnapshot>? = active?.snapshot()

    /**
     * Freezes completed samples and reports spans still active at the stop boundary.
     * It does not wait for or cancel them. Their later completion is never recorded
     * in this result or any subsequent capture.
     */
    @Synchronized
    fun stopCapture(): List<SyncPerformanceStageSnapshot>? {
        val capture = active ?: return null
        active = null
        return capture.stop()
    }

    /** Inline so the disabled path allocates no span/lambda and never reads the clock. */
    inline fun <T> measure(stage: SyncPerformanceStage, block: () -> T): T {
        val candidate = active
        val capture = if (candidate != null && candidate.begin(stage.id)) candidate else null
        val startedAt = if (capture != null) capture.nanoTime() else 0L
        var succeeded = 1
        try {
            // One call site avoids copying a large phase body into multiple inline branches.
            return block()
        } catch (error: Throwable) {
            succeeded = 0
            throw error
        } finally {
            if (capture != null) capture.finish(stage.id, startedAt, succeeded)
        }
    }

    internal class Capture(val nanoTime: () -> Long) {
        private val stageCount = SyncPerformanceStage.entries.size
        private val durations = Array(stageCount) { LongArray(SAMPLES_PER_STAGE) }
        private val successes = Array(stageCount) { IntArray(SAMPLES_PER_STAGE) }
        private val next = IntArray(stageCount)
        private val retained = IntArray(stageCount)
        private val completed = LongArray(stageCount)
        private val failed = LongArray(stageCount)
        private val activeSpans = IntArray(stageCount)
        private val totals = LongArray(stageCount)
        private val maxima = LongArray(stageCount)
        private var stopped = false

        @Synchronized
        fun begin(stageId: Int): Boolean {
            if (stopped) return false
            activeSpans[stageId]++
            return true
        }

        fun finish(stageId: Int, startedAt: Long, succeeded: Int) {
            val elapsed = (nanoTime() - startedAt).coerceAtLeast(0L)
            synchronized(this) {
                activeSpans[stageId]--
                if (stopped) return
                completed[stageId]++
                failed[stageId] += 1 - succeeded
                totals[stageId] += elapsed
                maxima[stageId] = maxOf(maxima[stageId], elapsed)
                val index = next[stageId]
                durations[stageId][index] = elapsed
                successes[stageId][index] = succeeded
                next[stageId] = (index + 1) % SAMPLES_PER_STAGE
                retained[stageId] = minOf(retained[stageId] + 1, SAMPLES_PER_STAGE)
            }
        }

        @Synchronized
        fun snapshot(): List<SyncPerformanceStageSnapshot> = List(stageCount) { stageId ->
            SyncPerformanceStageSnapshot(
                stageId = stageId,
                completedCount = completed[stageId],
                failedCount = failed[stageId],
                activeSpans = activeSpans[stageId],
                totalNanos = totals[stageId],
                maxNanos = maxima[stageId],
                samples = List(retained[stageId]) { offset ->
                    val index = (next[stageId] - retained[stageId] + offset + SAMPLES_PER_STAGE) %
                        SAMPLES_PER_STAGE
                    SyncPerformanceSample(durations[stageId][index], successes[stageId][index])
                },
            )
        }

        @Synchronized
        fun stop(): List<SyncPerformanceStageSnapshot> {
            stopped = true
            return snapshot()
        }
    }
}
