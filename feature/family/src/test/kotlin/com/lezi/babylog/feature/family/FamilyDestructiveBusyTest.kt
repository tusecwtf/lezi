package com.lezi.babylog.feature.family

import com.lezi.babylog.feature.family.components.FamilyDestructiveAction
import com.lezi.babylog.feature.family.components.FamilyDestructiveActionGate
import com.lezi.babylog.feature.family.components.familyDestructiveConfirmPresentation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FamilyDestructiveBusyTest {
    @Test
    fun busyConfirmIsDisabledAndNotDismissibleWithActionCopy() {
        val copies = mapOf(
            FamilyDestructiveAction.LeaveFamily to "退出中…",
            FamilyDestructiveAction.LogoutDevice to "退出中…",
            FamilyDestructiveAction.RevokeDevice to "撤销中…",
            FamilyDestructiveAction.DeleteBaby to "删除中…",
            FamilyDestructiveAction.MergeBaby to "合并中…",
            FamilyDestructiveAction.DeleteFamily to "正在删除…",
        )

        copies.forEach { (action, expectedCopy) ->
            val presentation = familyDestructiveConfirmPresentation(action, busy = true)
            assertEquals(expectedCopy, presentation.label)
            assertFalse(presentation.enabled)
            assertFalse(presentation.dismissible)
        }
    }

    @Test
    fun rapidDoubleConfirmRunsOnlyOneMutationAndFailureReleasesRetry() = runTest {
        val gate = FamilyDestructiveActionGate()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var mutationCalls = 0
        val first = async {
            gate.run {
                mutationCalls += 1
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()

        assertFalse(gate.run { mutationCalls += 1 })
        assertEquals(1, mutationCalls)
        assertTrue(gate.busy.value)

        release.complete(Unit)
        assertTrue(first.await())
        assertFalse(gate.busy.value)

        val failure = runCatching { gate.run { error("backend failed") } }.exceptionOrNull()
        assertTrue(failure != null)
        assertFalse(gate.busy.value)
        assertTrue(gate.run {})
    }
}
