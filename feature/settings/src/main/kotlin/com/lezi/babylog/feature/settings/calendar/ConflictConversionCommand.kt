package com.lezi.babylog.feature.settings.calendar

import com.lezi.babylog.core.common.productUiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class ConflictConversionState(
    val candidateUuid: String? = null,
    val busy: Boolean = false,
    val recordId: Long? = null,
    val saveError: String? = null,
    val refreshWarning: String? = null,
)

/** A successful commit is authoritative even if the following presentation read fails. */
internal class ConflictConversionCommand(
    private val convert: suspend (String) -> Long,
    private val refresh: suspend (String) -> Unit,
) {
    private val mutableState = MutableStateFlow(ConflictConversionState())
    val state = mutableState.asStateFlow()
    private val committed = mutableMapOf<String, Long>()

    suspend fun convert(candidateUuid: String) {
        val before = mutableState.value
        if (before.busy || !mutableState.compareAndSet(before,
                ConflictConversionState(candidateUuid, busy = true, recordId = committed[candidateUuid]))) return
        try {
            val recordId = committed[candidateUuid] ?: try {
                convert.invoke(candidateUuid).also { committed[candidateUuid] = it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.value = ConflictConversionState(candidateUuid,
                    saveError = productUiError(error, "转换没有完成，这条记录保持原样，可重试"))
                return
            }
            mutableState.value = ConflictConversionState(candidateUuid, busy = true, recordId = recordId)
            try {
                refresh(candidateUuid)
                mutableState.value = ConflictConversionState(candidateUuid, recordId = recordId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                mutableState.value = ConflictConversionState(candidateUuid, recordId = recordId,
                    refreshWarning = "已转为独立护理记录，详情暂时无法刷新，请重试刷新")
            }
        } finally {
            mutableState.value = mutableState.value.copy(busy = false)
        }
    }
}
