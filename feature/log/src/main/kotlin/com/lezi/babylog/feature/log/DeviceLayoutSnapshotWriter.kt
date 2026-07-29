package com.lezi.babylog.feature.log

import com.lezi.babylog.core.model.DeviceLayoutSnapshot
import com.lezi.babylog.core.model.normalizeDeviceLayoutSnapshot
import com.lezi.babylog.core.model.requireCurrentDeviceLayoutVersion
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal sealed interface DeviceLayoutWriteState {
    data class Saved(val snapshot: DeviceLayoutSnapshot? = null) : DeviceLayoutWriteState
    data class Saving(val snapshot: DeviceLayoutSnapshot) : DeviceLayoutWriteState
    data class Failed(
        val sequence: Long,
        val snapshot: DeviceLayoutSnapshot,
        val cause: Throwable,
    ) : DeviceLayoutWriteState
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
        }
    }

    @Synchronized
    fun submit(snapshot: DeviceLayoutSnapshot) {
        val normalized = normalizeDeviceLayoutSnapshot(snapshot)
        requireCurrentDeviceLayoutVersion(normalized)
        val nextSequence = sequence.incrementAndGet()
        latestRequestedSequence = nextSequence
        mutableState.value = DeviceLayoutWriteState.Saving(normalized)
        check(commands.trySend(Command.Persist(nextSequence, normalized)).isSuccess) {
            "Device layout writer is closed"
        }
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
        check(commands.trySend(Command.Barrier(target, result)).isSuccess) {
            "Device layout writer is closed"
        }
        return target to result
    }

    /** Retry only the latest failed complete snapshot; never replay a superseded failure. */
    suspend fun retryLatest(): Result<Unit> {
        val failed = state.value as? DeviceLayoutWriteState.Failed
            ?: return Result.success(Unit)
        submit(failed.snapshot)
        return flush()
    }
}
