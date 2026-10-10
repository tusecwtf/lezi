package com.lezi.babylog.core.ui

import com.lezi.babylog.core.common.productUiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The submitted draft is also the identity of the result; it never owns a later draft. */
data class CustomItemSaveDraft(val targetId: Long?, val name: String, val iconSlot: Int)

data class CustomItemSaveCommandState(
    val draft: CustomItemSaveDraft? = null,
    val saving: Boolean = false,
    val completed: Boolean = false,
    val error: String? = null,
)

/** Retain in the screen ViewModel, not in a dialog callback. */
class CustomItemSaveCommand {
    private val mutableState = MutableStateFlow(CustomItemSaveCommandState())
    val state = mutableState.asStateFlow()

    suspend fun save(draft: CustomItemSaveDraft, write: suspend () -> String?): Boolean {
        val current = mutableState.value
        if (current.saving || !mutableState.compareAndSet(current,
                CustomItemSaveCommandState(draft = draft, saving = true))) return false
        try {
            val error = write()
            mutableState.value = CustomItemSaveCommandState(draft = draft, completed = true, error = error)
        } catch (cancelled: CancellationException) {
            mutableState.value = CustomItemSaveCommandState(draft = draft)
            throw cancelled
        } catch (error: Throwable) {
            mutableState.value = CustomItemSaveCommandState(draft = draft, completed = true,
                error = productUiError(error, "没有保存成功，请保留草稿并重试"))
        }
        return true
    }

    fun consume() {
        if (!mutableState.value.saving) mutableState.value = CustomItemSaveCommandState()
    }
}
