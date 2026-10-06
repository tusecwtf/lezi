package com.lezi.babylog.domain.localdata

import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-local exclusion seam between destructive local clear and domain writes.
 *
 * This is deliberately non-waiting. RealSyncPort enters clear while holding its
 * sync mutex, while a completed domain mutation may next request reference-aware
 * cleanup through that same mutex. Waiting on either side would create
 * `syncMutex -> clear epoch -> writer -> syncMutex`. Atomic admission instead
 * makes the losing operation fail before any Room/file mutation begins.
 */
@Singleton
class LocalDataMutationEpoch @Inject constructor() {
    private val state = AtomicReference(EpochState())

    /** Capture the process-local data generation owned by a long-lived feature session. */
    fun currentGeneration(): Long = state.get().generation

    suspend fun <T> withMutation(block: suspend () -> T): T {
        enterMutation(expectedGeneration = null)
        return try {
            block()
        } finally {
            leaveMutation()
        }
    }

    /**
     * Admit a write only when no destructive clear has completed since the
     * feature captured [expectedGeneration]. The check and admission CAS are one
     * operation, so clear cannot slip between them.
     */
    suspend fun <T> withMutationInGeneration(
        expectedGeneration: Long,
        block: suspend () -> T,
    ): T {
        enterMutation(expectedGeneration)
        return try {
            block()
        } finally {
            leaveMutation()
        }
    }

    suspend fun <T> withClearEpoch(block: suspend () -> T): T {
        enterClear()
        return try {
            block()
        } finally {
            leaveClear()
        }
    }

    private fun enterMutation(expectedGeneration: Long?) {
        while (true) {
            val current = state.get()
            if (current.clearing) throw LocalDataClearInProgressException()
            if (expectedGeneration != null && current.generation != expectedGeneration) {
                throw LocalDataEpochInvalidatedException()
            }
            val next = current.copy(activeMutations = current.activeMutations + 1)
            if (state.compareAndSet(current, next)) return
        }
    }

    private fun leaveMutation() {
        while (true) {
            val current = state.get()
            check(current.activeMutations > 0) { "本机数据写入 epoch 计数失衡" }
            val next = current.copy(activeMutations = current.activeMutations - 1)
            if (state.compareAndSet(current, next)) return
        }
    }

    private fun enterClear() {
        while (true) {
            val current = state.get()
            if (current.clearing) throw LocalDataClearInProgressException()
            if (current.activeMutations > 0) {
                throw LocalDataMutationInProgressException()
            }
            val next = current.copy(clearing = true, generation = current.generation + 1)
            if (state.compareAndSet(current, next)) return
        }
    }

    private fun leaveClear() {
        while (true) {
            val current = state.get()
            check(current.clearing) { "本机清空 epoch 计数失衡" }
            val next = current.copy(clearing = false, generation = current.generation + 1)
            if (state.compareAndSet(current, next)) return
        }
    }
}

private data class EpochState(
    val generation: Long = 0L,
    val activeMutations: Int = 0,
    val clearing: Boolean = false,
)

class LocalDataClearInProgressException :
    IllegalStateException("本机数据正在清空，请稍后重试")

class LocalDataMutationInProgressException :
    IllegalStateException("正在保存本机数据，请稍后重试清空")

class LocalDataEpochInvalidatedException :
    IllegalStateException("本机数据会话已失效，请重新进入")
