package com.lezi.babylog.feature.family.overview

import com.lezi.babylog.core.common.SingleFlightAction
import com.lezi.babylog.core.common.productUiError
import com.lezi.babylog.sync.session.FamilyRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A result belongs only to the baby and family identity from which the user switched. */
internal data class CurrentBabySwitchContext(
    val babyId: Long?,
    val babyClientUuid: String?,
    val familyId: String,
    val membershipId: String,
    val role: FamilyRole,
    val enabled: Boolean,
)

internal fun AccountOverviewUi.currentBabySwitchContext() = CurrentBabySwitchContext(
    babyId = current?.id,
    babyClientUuid = current?.clientUuid,
    familyId = familyId,
    membershipId = membershipId,
    role = role,
    enabled = enabled,
)

internal data class CurrentBabySwitchFailure(
    val context: CurrentBabySwitchContext,
    val reason: String,
    val retainedSelection: Boolean,
) {
    fun messageFor(overview: AccountOverviewUi): String? {
        if (context != overview.currentBabySwitchContext()) return null
        if (!retainedSelection) return "无法确认宝宝切换。$reason"
        val currentName = overview.current?.nickname?.takeIf { it.isNotBlank() }
        return if (currentName == null) {
            "切换宝宝没有完成。$reason"
        } else {
            "切换宝宝没有完成，当前宝宝仍是「$currentName」。$reason"
        }
    }
}

internal data class CurrentBabySwitchState(
    val switching: Boolean = false,
    val failure: CurrentBabySwitchFailure? = null,
)

/** UI-only feedback; CareLog retains exclusive ownership of permission/clear-epoch checks. */
internal class CurrentBabySwitchAction(
    private val setCurrent: suspend (Long) -> Unit,
    private val selectedId: suspend () -> Long?,
) {
    private val action = SingleFlightAction()
    private val mutableState = MutableStateFlow(CurrentBabySwitchState())
    val state = mutableState.asStateFlow()

    suspend fun switch(id: Long, context: CurrentBabySwitchContext) = action.run {
        mutableState.value = CurrentBabySwitchState(switching = true)
        var writeCompleted = false
        var confirmedSelection: Long? = null
        try {
            setCurrent(id)
            writeCompleted = true
            confirmedSelection = selectedId()
            check(confirmedSelection == id) { "宝宝档案状态已变化，请重新选择" }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            currentCoroutineContext().ensureActive()
            mutableState.value = CurrentBabySwitchState(
                switching = true,
                failure = CurrentBabySwitchFailure(
                    context = context,
                    reason = productUiError(error, "请稍后重试"),
                    retainedSelection = !writeCompleted ||
                        (confirmedSelection != null && confirmedSelection == context.babyId),
                ),
            )
        } finally {
            mutableState.value = mutableState.value.copy(switching = false)
        }
    }

    /** Once the scope changes, an old failure must not reappear on returning to that baby. */
    fun invalidateFor(context: CurrentBabySwitchContext) {
        val previous = mutableState.value
        if (previous.failure?.context?.let { it != context } == true) {
            mutableState.compareAndSet(previous, previous.copy(failure = null))
        }
    }
}
