package com.lezi.babylog.feature.growth

import com.lezi.babylog.core.common.productUiError
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.domain.growth.GrowthMeasurementLifecycle
import com.lezi.babylog.domain.growth.GrowthMeasurementSaveResult
import com.lezi.babylog.domain.growth.SaveGrowthMeasurement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class GrowthMeasurementDraft(
    val recordId: Long? = null,
    val valueText: String = "",
    val note: String = "",
    val measuredAt: Long,
)

sealed interface GrowthMeasurementWriteOperation {
    data class Saving(val recordId: Long?) : GrowthMeasurementWriteOperation
    data class Deleting(val recordId: Long) : GrowthMeasurementWriteOperation
}

data class GrowthMeasurementEditorState(
    val draft: GrowthMeasurementDraft? = null,
    val operation: GrowthMeasurementWriteOperation? = null,
    val deleteConfirmationOpen: Boolean = false,
    val fieldError: String? = null,
    val operationError: String? = null,
)

class GrowthMeasurementWriteCoordinator(
    private val scope: CoroutineScope,
    private val measurements: GrowthMeasurementLifecycle,
    private val currentBabyId: suspend () -> Long?,
    private val nowMillis: () -> Long,
) {
    private val mutableState = MutableStateFlow(GrowthMeasurementEditorState())
    val state: StateFlow<GrowthMeasurementEditorState> = mutableState.asStateFlow()

    fun openDraft(draft: GrowthMeasurementDraft): Boolean {
        while (true) {
            val current = mutableState.value
            if (current.operation != null) return false
            if (mutableState.compareAndSet(current, GrowthMeasurementEditorState(draft = draft))) {
                return true
            }
        }
    }

    fun updateDraft(draft: GrowthMeasurementDraft): Boolean {
        while (true) {
            val current = mutableState.value
            if (current.operation != null || current.draft == null) return false
            if (
                mutableState.compareAndSet(
                    current,
                    current.copy(
                        draft = draft,
                        deleteConfirmationOpen = false,
                        fieldError = null,
                        operationError = null,
                    ),
                )
            ) {
                return true
            }
        }
    }

    fun closeDraft(): Boolean {
        while (true) {
            val current = mutableState.value
            if (current.operation != null) return false
            if (mutableState.compareAndSet(current, GrowthMeasurementEditorState())) return true
        }
    }

    fun reportEditorMessage(message: String?) {
        while (true) {
            val current = mutableState.value
            if (current.operation != null || current.draft == null) return
            if (
                mutableState.compareAndSet(
                    current,
                    current.copy(fieldError = null, operationError = message),
                )
            ) {
                return
            }
        }
    }

    fun requestDelete(): Boolean {
        while (true) {
            val current = mutableState.value
            if (current.operation != null || current.draft?.recordId == null) return false
            if (
                mutableState.compareAndSet(
                    current,
                    current.copy(deleteConfirmationOpen = true, operationError = null),
                )
            ) {
                return true
            }
        }
    }

    fun cancelDelete(): Boolean {
        while (true) {
            val current = mutableState.value
            if (current.operation != null) return false
            if (
                mutableState.compareAndSet(
                    current,
                    current.copy(deleteConfirmationOpen = false, operationError = null),
                )
            ) {
                return true
            }
        }
    }

    fun submitSave(type: RecordType): Boolean {
        while (true) {
            val current = mutableState.value
            val draft = current.draft ?: return false
            if (current.operation != null) return false
            val value = draft.valueText.toDoubleOrNull()
            val validationError = if (value == null) {
                "请填写有效数值"
            } else {
                measurements.validationError(type, value)
            }
            if (validationError != null) {
                if (
                    mutableState.compareAndSet(
                        current,
                        current.copy(fieldError = validationError, operationError = null),
                    )
                ) {
                    return false
                }
                continue
            }
            val submittedAt = nowMillis()
            if (draft.measuredAt > submittedAt) {
                if (
                    mutableState.compareAndSet(
                        current,
                        current.copy(
                            fieldError = null,
                            operationError = "测量时刻不能晚于现在",
                        ),
                    )
                ) {
                    return false
                }
                continue
            }
            val operation = GrowthMeasurementWriteOperation.Saving(draft.recordId)
            if (
                !mutableState.compareAndSet(
                    current,
                    current.copy(operation = operation, fieldError = null, operationError = null),
                )
            ) {
                continue
            }
            scope.launch {
                try {
                    val babyId = currentBabyId()
                    if (babyId == null) {
                        mutableState.value = current.copy(operationError = "请先添加宝宝")
                        return@launch
                    }
                    when (
                        val result = measurements.save(
                            SaveGrowthMeasurement(
                                babyId = babyId,
                                type = type,
                                displayValue = checkNotNull(value),
                                measuredAt = draft.measuredAt,
                                note = draft.note.ifBlank { null },
                                existingRecordId = draft.recordId,
                                nowMillis = submittedAt,
                            ),
                        )
                    ) {
                        is GrowthMeasurementSaveResult.Saved -> {
                            mutableState.value = GrowthMeasurementEditorState()
                        }
                        is GrowthMeasurementSaveResult.Rejected -> {
                            mutableState.value = current.copy(operationError = result.message)
                        }
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    mutableState.value = current.copy(
                        operationError = productUiError(error, "保存失败，请重试"),
                    )
                }
            }
            return true
        }
    }

    fun submitDelete(): Boolean {
        while (true) {
            val current = mutableState.value
            val recordId = current.draft?.recordId ?: return false
            if (!current.deleteConfirmationOpen || current.operation != null) return false
            val operation = GrowthMeasurementWriteOperation.Deleting(recordId)
            if (
                !mutableState.compareAndSet(
                    current,
                    current.copy(operation = operation, operationError = null),
                )
            ) {
                continue
            }
            scope.launch {
                try {
                    val babyId = currentBabyId()
                    if (babyId == null) {
                        mutableState.value = current.copy(
                            deleteConfirmationOpen = true,
                            operationError = "请先添加宝宝",
                        )
                        return@launch
                    }
                    if (measurements.delete(babyId, recordId)) {
                        mutableState.value = GrowthMeasurementEditorState()
                    } else {
                        mutableState.value = current.copy(
                            deleteConfirmationOpen = true,
                            operationError = "删除失败，测量记录可能已不存在，请重试",
                        )
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    mutableState.value = current.copy(
                        deleteConfirmationOpen = true,
                        operationError = productUiError(error, "删除失败，请重试"),
                    )
                }
            }
            return true
        }
    }
}
