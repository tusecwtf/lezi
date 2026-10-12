package com.lezi.babylog.core.common

import com.lezi.babylog.core.common.deadline.RealtimeDeadline
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Elapsed cap for inspect + keystore + snapshot + migrate + verify. Midpoint of the 30–60s band. */
const val LOCAL_DATA_GATE_MAX_ELAPSED_MILLIS = 45_000L

/** A persistence domain that must be snapshotted before an upgrade step mutates it. */
enum class LocalDataDomain {
    Room,
    Settings,
    Credentials,
    Media,
}

/** One adjacent, idempotent local-data contract migration. */
interface LocalDataUpgradeStep {
    val fromContractVersion: Int
    val toContractVersion: Int
    val affectedDomains: Set<LocalDataDomain>

    suspend fun migrate()

    suspend fun verify()
}

sealed interface LocalDataUpgradePlan {
    data object Ready : LocalDataUpgradePlan

    data class Upgrade(
        val steps: List<LocalDataUpgradeStep>,
    ) : LocalDataUpgradePlan

    data class Blocked(
        val reason: LocalDataUpgradeBlockReason,
    ) : LocalDataUpgradePlan
}

enum class LocalDataUpgradeBlockReason {
    UnsupportedLegacy,
    NewerData,
    InsufficientSpace,
    InconsistentData,
    MissingMigration,
    MigrationFailed,
    VerificationFailed,
    TimedOut,
}

class LocalDataUpgradePlanner(
    private val currentContractVersion: Int,
    private val minimumMigratableContractVersion: Int,
    steps: Set<LocalDataUpgradeStep>,
) {
    private val stepsBySource = steps.groupBy(LocalDataUpgradeStep::fromContractVersion)

    fun planFrom(sourceContractVersion: Int): LocalDataUpgradePlan {
        if (sourceContractVersion == currentContractVersion) return LocalDataUpgradePlan.Ready
        if (sourceContractVersion > currentContractVersion) {
            return LocalDataUpgradePlan.Blocked(LocalDataUpgradeBlockReason.NewerData)
        }
        if (sourceContractVersion < minimumMigratableContractVersion) {
            return LocalDataUpgradePlan.Blocked(LocalDataUpgradeBlockReason.UnsupportedLegacy)
        }

        val path = mutableListOf<LocalDataUpgradeStep>()
        var cursor = sourceContractVersion
        while (cursor < currentContractVersion) {
            val candidates = stepsBySource[cursor].orEmpty()
                .filter { it.toContractVersion == cursor + 1 }
            if (candidates.size != 1) {
                return LocalDataUpgradePlan.Blocked(LocalDataUpgradeBlockReason.MissingMigration)
            }
            val step = candidates.single()
            path += step
            cursor = step.toContractVersion
        }
        return LocalDataUpgradePlan.Upgrade(path)
    }
}

sealed interface LocalDataUpgradeState {
    data object Checking : LocalDataUpgradeState

    data class Snapshotting(
        val fromContractVersion: Int,
        val toContractVersion: Int,
    ) : LocalDataUpgradeState

    data class Migrating(
        val fromContractVersion: Int,
        val toContractVersion: Int,
    ) : LocalDataUpgradeState

    data class Ready(
        val contractVersion: Int,
    ) : LocalDataUpgradeState

    data class Blocked(
        val reason: LocalDataUpgradeBlockReason,
        val detail: String,
    ) : LocalDataUpgradeState
}

data class LocalDataInspection(
    val contractVersion: Int,
    val baselineMarkerRequired: Boolean = false,
)

/** Platform persistence operations used by the process-wide gate. */
interface LocalDataUpgradeEnvironment {
    suspend fun inspect(): LocalDataInspection

    suspend fun prepareSnapshot(step: LocalDataUpgradeStep)

    suspend fun commitContract(contractVersion: Int)

    suspend fun cleanupSnapshots()

    suspend fun verifyCurrent()

    fun diagnosticContext(): String
}

class LocalDataUpgradeFailure(
    val reason: LocalDataUpgradeBlockReason,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Process-wide gate that prevents persistence consumers from opening data too early:
 * serializes inspection/migration and never exposes business storage before verification.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultLocalDataGate(
    currentContractVersion: Int,
    minimumMigratableContractVersion: Int,
    steps: Set<LocalDataUpgradeStep>,
    private val environment: LocalDataUpgradeEnvironment,
    private val maxElapsedMillis: Long = LOCAL_DATA_GATE_MAX_ELAPSED_MILLIS,
) {
    private val currentContractVersion = currentContractVersion
    private val planner = LocalDataUpgradePlanner(
        currentContractVersion = currentContractVersion,
        minimumMigratableContractVersion = minimumMigratableContractVersion,
        steps = steps,
    )
    private val mutex = Mutex()
    private val publicationMonitor = Any()
    private val attemptGeneration = AtomicInteger()
    private val inspectScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var inFlightPublished: CompletableDeferred<LocalDataUpgradeState>? = null
    private val mutableState = MutableStateFlow<LocalDataUpgradeState>(
        LocalDataUpgradeState.Checking,
    )

    val state: StateFlow<LocalDataUpgradeState> = mutableState.asStateFlow()

    suspend fun ensureReady(): Boolean {
        if (state.value is LocalDataUpgradeState.Ready) return true
        return retry() is LocalDataUpgradeState.Ready
    }

    suspend fun retry(): LocalDataUpgradeState {
        if (state.value is LocalDataUpgradeState.Ready) return state.value
        val start = synchronized(publicationMonitor) {
            if (state.value is LocalDataUpgradeState.Ready) {
                return@synchronized GateRetryStart.AlreadyReady
            }
            val current = inFlightPublished?.takeIf { !it.isCompleted }
            if (current != null) {
                GateRetryStart.Join(current)
            } else {
                val published = CompletableDeferred<LocalDataUpgradeState>()
                inFlightPublished = published
                GateRetryStart.Fresh(attemptGeneration.incrementAndGet(), published)
            }
        }
        return when (start) {
            GateRetryStart.AlreadyReady -> state.value
            is GateRetryStart.Join -> start.published.await()
            is GateRetryStart.Fresh -> runFreshGeneration(start.generation, start.published)
        }
    }

    private suspend fun runFreshGeneration(
        generation: Int,
        published: CompletableDeferred<LocalDataUpgradeState>,
    ): LocalDataUpgradeState {
        val work = inspectScope.launch {
            try {
                publishState(generation, published, retryGeneration(generation, published), terminal = true)
            } catch (cancelled: CancellationException) {
                synchronized(publicationMonitor) {
                    if (isCurrentPublication(generation, published) && !published.isCompleted) {
                        published.completeExceptionally(cancelled)
                    }
                }
                throw cancelled
            }
        }
        return RealtimeDeadline(maxElapsedMillis, "lezi-local-data-gate") {
            val timedOut = LocalDataUpgradeState.Blocked(
                reason = LocalDataUpgradeBlockReason.TimedOut,
                detail = "本地数据安全检查超时",
            )
            if (publishState(generation, published, timedOut, terminal = true)) work.cancel()
        }.use {
            published.await()
        }
    }

    private suspend fun retryGeneration(
        generation: Int,
        published: CompletableDeferred<LocalDataUpgradeState>,
    ): LocalDataUpgradeState {
        mutex.withLock {
            if (!publishState(generation, published, LocalDataUpgradeState.Checking, terminal = false)) {
                return publishedResult(published)
            }
        }
        val inspection = try {
            environment.inspect()
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            return publishBlock(
                generation = generation,
                published = published,
                reason = failure.localDataReason(LocalDataUpgradeBlockReason.InconsistentData),
                detail = failure.message.orEmpty().ifBlank { "无法读取本地数据状态" },
            )
        }
        return applyInspection(generation, inspection, published)
    }

    private suspend fun applyInspection(
        generation: Int,
        inspection: LocalDataInspection,
        published: CompletableDeferred<LocalDataUpgradeState>,
    ): LocalDataUpgradeState = mutex.withLock {
        synchronized(publicationMonitor) {
            if (!isCurrentPublication(generation, published) ||
                state.value is LocalDataUpgradeState.Ready || published.isCompleted
            ) {
                return@withLock publishedResult(published)
            }
        }

        when (val plan = planner.planFrom(inspection.contractVersion)) {
            LocalDataUpgradePlan.Ready -> {
                val verificationFailure = runCatching {
                    environment.verifyCurrent()
                }.failureOrRethrowCancellation()
                if (verificationFailure != null) {
                    return@withLock block(
                        generation = generation,
                        published = published,
                        reason = verificationFailure.localDataReason(
                            LocalDataUpgradeBlockReason.VerificationFailed,
                        ),
                        detail = verificationFailure.message.orEmpty()
                            .ifBlank { "当前本地数据校验失败" },
                    )
                }
                if (inspection.baselineMarkerRequired) {
                    val markerFailure = runCatching {
                        environment.commitContract(inspection.contractVersion)
                    }.failureOrRethrowCancellation()
                    if (markerFailure != null) {
                        return@withLock block(
                            generation = generation,
                            published = published,
                            reason = LocalDataUpgradeBlockReason.VerificationFailed,
                            detail = markerFailure.message.orEmpty()
                                .ifBlank { "无法写入本地数据契约标记" },
                        )
                    }
                }
                runCatching { environment.cleanupSnapshots() }.failureOrRethrowCancellation()
            }

            is LocalDataUpgradePlan.Blocked -> {
                return@withLock block(
                    generation = generation,
                    published = published,
                    reason = plan.reason,
                    detail = "本地数据契约 ${inspection.contractVersion} 无法升级到 $currentContractVersion",
                )
            }

            is LocalDataUpgradePlan.Upgrade -> {
                for (step in plan.steps) {
                    if (!publishState(
                            generation,
                            published,
                            LocalDataUpgradeState.Snapshotting(step.fromContractVersion, step.toContractVersion),
                            terminal = false,
                        )
                    ) return@withLock publishedResult(published)
                    val snapshotFailure = runCatching {
                        environment.prepareSnapshot(step)
                    }.failureOrRethrowCancellation()
                    if (snapshotFailure != null) {
                        return@withLock block(
                            generation = generation,
                            published = published,
                            reason = snapshotFailure.localDataReason(
                                LocalDataUpgradeBlockReason.MigrationFailed,
                            ),
                            detail = snapshotFailure.message.orEmpty()
                                .ifBlank { "本地数据快照失败" },
                        )
                    }

                    if (!publishState(
                            generation,
                            published,
                            LocalDataUpgradeState.Migrating(step.fromContractVersion, step.toContractVersion),
                            terminal = false,
                        )
                    ) return@withLock publishedResult(published)
                    val migrationFailure = runCatching {
                        step.migrate()
                    }.failureOrRethrowCancellation()
                    if (migrationFailure != null) {
                        return@withLock block(
                            generation = generation,
                            published = published,
                            reason = migrationFailure.localDataReason(
                                LocalDataUpgradeBlockReason.MigrationFailed,
                            ),
                            detail = migrationFailure.message.orEmpty()
                                .ifBlank { "本地数据迁移失败" },
                        )
                    }
                    val stepVerificationFailure = runCatching {
                        step.verify()
                    }.failureOrRethrowCancellation()
                    if (stepVerificationFailure != null) {
                        return@withLock block(
                            generation = generation,
                            published = published,
                            reason = stepVerificationFailure.localDataReason(
                                LocalDataUpgradeBlockReason.VerificationFailed,
                            ),
                            detail = stepVerificationFailure.message.orEmpty()
                                .ifBlank { "迁移结果校验失败" },
                        )
                    }
                    val commitFailure = runCatching {
                        environment.commitContract(step.toContractVersion)
                    }.failureOrRethrowCancellation()
                    if (commitFailure != null) {
                        return@withLock block(
                            generation = generation,
                            published = published,
                            reason = LocalDataUpgradeBlockReason.MigrationFailed,
                            detail = commitFailure.message.orEmpty()
                                .ifBlank { "迁移结果提交失败" },
                        )
                    }
                }
                val verificationFailure = runCatching {
                    environment.verifyCurrent()
                }.failureOrRethrowCancellation()
                if (verificationFailure != null) {
                    return@withLock block(
                        generation = generation,
                        published = published,
                        reason = verificationFailure.localDataReason(
                            LocalDataUpgradeBlockReason.VerificationFailed,
                        ),
                        detail = verificationFailure.message.orEmpty()
                            .ifBlank { "升级后的本地数据校验失败" },
                    )
                }
                runCatching { environment.cleanupSnapshots() }.failureOrRethrowCancellation()
            }
        }

        val ready = LocalDataUpgradeState.Ready(currentContractVersion)
        if (publishState(generation, published, ready, terminal = true)) ready else publishedResult(published)
    }

    fun diagnosticReport(): String = buildString {
        appendLine("state=${state.value}")
        append(
            runCatching(environment::diagnosticContext)
                .getOrElse { failure ->
                    "diagnostic=unavailable,type=${failure.javaClass.simpleName}"
                },
        )
    }

    private fun block(
        generation: Int,
        published: CompletableDeferred<LocalDataUpgradeState>,
        reason: LocalDataUpgradeBlockReason,
        detail: String,
    ): LocalDataUpgradeState {
        val blocked = LocalDataUpgradeState.Blocked(reason, detail)
        return if (publishState(generation, published, blocked, terminal = true)) blocked else publishedResult(published)
    }

    private fun publishBlock(
        generation: Int,
        published: CompletableDeferred<LocalDataUpgradeState>,
        reason: LocalDataUpgradeBlockReason,
        detail: String,
    ): LocalDataUpgradeState = block(generation, published, reason, detail)

    /** Only identity, state and non-suspending deferred publication belong in this monitor. */
    private fun publishState(
        generation: Int,
        published: CompletableDeferred<LocalDataUpgradeState>,
        next: LocalDataUpgradeState,
        terminal: Boolean,
    ): Boolean = synchronized(publicationMonitor) {
        if (!isCurrentPublication(generation, published) ||
            state.value is LocalDataUpgradeState.Ready || published.isCompleted
        ) return@synchronized false
        // Waiters must see the winning state before completion makes their await return.
        mutableState.value = next
        if (terminal) published.complete(next)
        true
    }

    /** Called only while holding publicationMonitor. */
    private fun isCurrentPublication(
        generation: Int,
        published: CompletableDeferred<LocalDataUpgradeState>,
    ): Boolean = attemptGeneration.get() == generation && inFlightPublished === published

    private fun publishedResult(
        published: CompletableDeferred<LocalDataUpgradeState>,
    ): LocalDataUpgradeState = synchronized(publicationMonitor) {
        if (published.isCompleted) published.getCompleted() else state.value
    }
}

private sealed interface GateRetryStart {
    data object AlreadyReady : GateRetryStart
    data class Join(val published: CompletableDeferred<LocalDataUpgradeState>) : GateRetryStart
    data class Fresh(
        val generation: Int,
        val published: CompletableDeferred<LocalDataUpgradeState>,
    ) : GateRetryStart
}

private fun Throwable.localDataReason(default: LocalDataUpgradeBlockReason) =
    (this as? LocalDataUpgradeFailure)?.reason ?: default

private fun Result<*>.failureOrRethrowCancellation(): Throwable? =
    exceptionOrNull()?.also { if (it is CancellationException) throw it }
