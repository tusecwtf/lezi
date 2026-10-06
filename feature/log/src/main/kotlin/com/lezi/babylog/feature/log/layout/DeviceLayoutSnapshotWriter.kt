package com.lezi.babylog.feature.log.layout
import com.lezi.babylog.core.model.DeviceLayoutSnapshot
import com.lezi.babylog.core.model.normalizeDeviceLayoutSnapshot
import com.lezi.babylog.core.model.requireCurrentDeviceLayoutVersion
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.timeline.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.composer.*
import com.lezi.babylog.feature.log.photo.*

internal sealed interface DeviceLayoutWriteState {
    data class Saved(val snapshot: DeviceLayoutSnapshot? = null) : DeviceLayoutWriteState
    data class Saving(val snapshot: DeviceLayoutSnapshot) : DeviceLayoutWriteState
    data class Failed(
        val sequence: Long,
        val snapshot: DeviceLayoutSnapshot,
        val cause: Throwable,
    ) : DeviceLayoutWriteState
}

/** Completion owned by one exact normalized snapshot write. */
internal data class DeviceLayoutWriteReceipt(
    val sequence: Long,
    val snapshot: DeviceLayoutSnapshot,
    val result: Deferred<Result<Unit>>,
)

/**
 * User-facing save feedback for the snapshot currently shown by the editor.
 * A completion for an older snapshot is deliberately silent; the writer's
 * sequence gate and this snapshot check together prevent stale success speech.
 */
internal fun layoutWriteAnnouncement(
    prefs: DeviceLayoutPrefs,
    state: DeviceLayoutWriteState,
    hasSubmittedIntent: Boolean,
): String? {
    if (!hasSubmittedIntent) return null
    val current = normalizeDeviceLayoutSnapshot(prefs.toSnapshot())
    val stateSnapshot = when (state) {
        is DeviceLayoutWriteState.Saved -> state.snapshot
        is DeviceLayoutWriteState.Saving -> state.snapshot
        is DeviceLayoutWriteState.Failed -> state.snapshot
    } ?: return null
    if (normalizeDeviceLayoutSnapshot(stateSnapshot) != current) return null
    return when (state) {
        is DeviceLayoutWriteState.Saving -> "正在保存布局"
        is DeviceLayoutWriteState.Saved -> "布局已保存"
        is DeviceLayoutWriteState.Failed -> "布局没有保存成功，下次进入会用默认布局，可重试"
    }
}

/**
 * Single FIFO writer for layout intents. Every submitted value is already a complete snapshot;
 * therefore a later successful write can safely include an earlier failed intent.
 */
internal class DeviceLayoutSnapshotWriter(
    scope: CoroutineScope,
    private val persist: suspend (DeviceLayoutSnapshot) -> Unit,
) {
    private sealed interface Command {
        data class Persist(
            val sequence: Long,
            val snapshot: DeviceLayoutSnapshot,
            val result: CompletableDeferred<Result<Unit>>,
        ) : Command

        data class Barrier(
            val targetSequence: Long,
            val result: CompletableDeferred<Result<Unit>>,
        ) : Command
    }

    private val sequence = AtomicLong(0L)

    @Volatile
    private var latestRequestedSequence: Long = 0L

    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val mutableState = MutableStateFlow<DeviceLayoutWriteState>(DeviceLayoutWriteState.Saved())
    val state: StateFlow<DeviceLayoutWriteState> = mutableState.asStateFlow()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var lastCompletedSequence = 0L
            var lastCompletedResult = Result.success(Unit)
            for (command in commands) {
                when (command) {
                    is Command.Persist -> {
                        if (command.sequence == latestRequestedSequence) {
                            mutableState.value = DeviceLayoutWriteState.Saving(command.snapshot)
                        }
                        val result = try {
                            persist(command.snapshot)
                            Result.success(Unit)
                        } catch (failure: Throwable) {
                            Result.failure(failure)
                        }
                        lastCompletedSequence = command.sequence
                        lastCompletedResult = result
                        if (command.sequence == latestRequestedSequence) {
                            mutableState.value = result.fold(
                                onSuccess = { DeviceLayoutWriteState.Saved(command.snapshot) },
                                onFailure = {
                                    DeviceLayoutWriteState.Failed(
                                        sequence = command.sequence,
                                        snapshot = command.snapshot,
                                        cause = it,
                                    )
                                },
                            )
                        }
                        // A completed receipt promises that callers can already observe the
                        // terminal UI state for this write; publish state before waking waiters.
                        command.result.complete(result)
                    }
                    is Command.Barrier -> {
                        val result = if (command.targetSequence == 0L) {
                            Result.success(Unit)
                        } else if (lastCompletedSequence >= command.targetSequence) {
                            lastCompletedResult
                        } else {
                            Result.failure(
                                IllegalStateException("Layout write barrier passed before persistence"),
                            )
                        }
                        command.result.complete(result)
                    }
                }
            }
        }.invokeOnCompletion {
            commands.close()
        }
    }

    @Synchronized
    fun submit(snapshot: DeviceLayoutSnapshot): DeviceLayoutWriteReceipt {
        val normalized = normalizeDeviceLayoutSnapshot(snapshot)
        val nextSequence = sequence.incrementAndGet()
        val result = CompletableDeferred<Result<Unit>>()
        latestRequestedSequence = nextSequence
        val versionFailure = runCatching {
            requireCurrentDeviceLayoutVersion(normalized)
        }.exceptionOrNull()
        if (versionFailure != null) {
            return failClosed(nextSequence, normalized, result, versionFailure)
        }
        mutableState.value = DeviceLayoutWriteState.Saving(normalized)
        val sent = commands.trySend(Command.Persist(nextSequence, normalized, result))
        if (!sent.isSuccess) {
            return failClosed(
                nextSequence,
                normalized,
                result,
                IllegalStateException("Device layout writer is closed"),
            )
        }
        return DeviceLayoutWriteReceipt(
            sequence = nextSequence,
            snapshot = normalized,
            result = result,
        )
    }

    /** Wait until the newest snapshot at return time has durably succeeded or failed. */
    suspend fun flush(): Result<Unit> {
        while (true) {
            val (target, result) = enqueueBarrierAfterLatestRequest()
            val completed = result.await()
            if (latestRequestedSequence == target) return completed
        }
    }

    /**
     * Share [submit]'s monitor so a request cannot publish its sequence and then have this
     * barrier overtake the corresponding Persist command in the channel.
     */
    @Synchronized
    private fun enqueueBarrierAfterLatestRequest():
        Pair<Long, CompletableDeferred<Result<Unit>>> {
        val target = latestRequestedSequence
        val result = CompletableDeferred<Result<Unit>>()
        val sent = commands.trySend(Command.Barrier(target, result))
        if (!sent.isSuccess) {
            val cause = IllegalStateException("Device layout writer is closed")
            val snapshot = currentStateSnapshot()
                ?: normalizeDeviceLayoutSnapshot(DeviceLayoutSnapshot())
            failClosed(target, snapshot, result, cause)
            return target to result
        }
        return target to result
    }

    private fun currentStateSnapshot(): DeviceLayoutSnapshot? = when (val current = mutableState.value) {
        is DeviceLayoutWriteState.Saved -> current.snapshot
        is DeviceLayoutWriteState.Saving -> current.snapshot
        is DeviceLayoutWriteState.Failed -> current.snapshot
    }

    private fun failClosed(
        sequence: Long,
        snapshot: DeviceLayoutSnapshot,
        result: CompletableDeferred<Result<Unit>>,
        cause: Throwable,
    ): DeviceLayoutWriteReceipt {
        mutableState.value = DeviceLayoutWriteState.Failed(
            sequence = sequence,
            snapshot = snapshot,
            cause = cause,
        )
        result.complete(Result.failure(cause))
        return DeviceLayoutWriteReceipt(
            sequence = sequence,
            snapshot = snapshot,
            result = result,
        )
    }

    /** Retry only the latest failed complete snapshot; never replay a superseded failure. */
    suspend fun retryLatest(): Result<Unit> {
        val failed = state.value as? DeviceLayoutWriteState.Failed
            ?: return Result.success(Unit)
        submit(failed.snapshot)
        return flush()
    }
}
