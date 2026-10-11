package com.lezi.babylog.feature.family.overview

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.model.Baby
import com.lezi.babylog.domain.BabyProfilePermissionException
import com.lezi.babylog.domain.localdata.LocalDataClearInProgressException
import com.lezi.babylog.domain.localdata.LocalDataEpochInvalidatedException
import com.lezi.babylog.feature.family.FamilyIdentityUi
import com.lezi.babylog.sync.session.FamilyRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CurrentBabySwitchActionTest {
    @Test
    fun permissionAndEpochFailuresKeepTheConfirmedBabyAndExplainIt() = runTest {
        for (failure in listOf(
            BabyProfilePermissionException(),
            LocalDataClearInProgressException(),
            LocalDataEpochInvalidatedException(),
        )) {
            val writes = mutableListOf<Long>()
            val overview = overview()
            val action = CurrentBabySwitchAction(
                setCurrent = { writes += it; throw failure },
                selectedId = { overview.current?.id },
            )

            action.switch(2, overview.currentBabySwitchContext())

            assertThat(writes).containsExactly(2L) // No rollback write races a clear or permission change.
            assertThat(overview.current?.id).isEqualTo(1L)
            assertThat(action.state.value.switching).isFalse()
            assertThat(action.state.value.failure?.messageFor(overview))
                .isEqualTo("切换宝宝没有完成，当前宝宝仍是「年年」。${failure.message}")
        }
    }

    @Test
    fun vanishedTargetNoOpAlsoExplainsTheUnchangedSelection() = runTest {
        val overview = overview()
        val action = CurrentBabySwitchAction(setCurrent = {}, selectedId = { 1L })

        action.switch(404, overview.currentBabySwitchContext())

        assertThat(action.state.value.failure?.messageFor(overview))
            .contains("当前宝宝仍是「年年」")
        assertThat(action.state.value.failure?.reason).isEqualTo("宝宝档案状态已变化，请重新选择")
    }

    @Test
    fun successfulRetryClearsThePriorFailure() = runTest {
        var selected = 1L
        var fail = true
        val overview = overview()
        val action = CurrentBabySwitchAction(
            setCurrent = { if (fail) error("IO failure") else selected = it },
            selectedId = { selected },
        )
        action.switch(2, overview.currentBabySwitchContext())
        assertThat(action.state.value.failure?.reason).isEqualTo("请稍后重试")

        fail = false
        action.switch(2, overview.currentBabySwitchContext())

        assertThat(selected).isEqualTo(2L)
        assertThat(action.state.value.failure).isNull()
        assertThat(action.state.value.switching).isFalse()
    }

    @Test
    fun failedConfirmationAfterACompletedWriteDoesNotClaimTheOldBabyWasRetained() = runTest {
        val overview = overview()
        var selected = 1L
        val action = CurrentBabySwitchAction(
            setCurrent = { selected = it },
            selectedId = { error("read failed") },
        )

        action.switch(2, overview.currentBabySwitchContext())

        assertThat(selected).isEqualTo(2L)
        assertThat(action.state.value.failure?.messageFor(overview))
            .isEqualTo("无法确认宝宝切换。请稍后重试")
    }

    @Test
    fun cancellationPropagatesAndReleasesTheSwitchGate() = runTest {
        val cancelled = CancellationException("view model cleared")
        var fail = true
        var selected = 1L
        val action = CurrentBabySwitchAction(
            setCurrent = { if (fail) throw cancelled else selected = it },
            selectedId = { selected },
        )
        val context = overview().currentBabySwitchContext()

        val thrown = runCatching { action.switch(2, context) }.exceptionOrNull()

        assertThat(thrown).isSameInstanceAs(cancelled)
        assertThat(action.state.value.failure).isNull()
        assertThat(action.state.value.switching).isFalse()
        fail = false
        assertThat(action.switch(2, context)).isTrue()
    }

    @Test
    fun rapidRepeatedTapsDoNotRunParallelSwitchesOrStackFailures() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val writes = mutableListOf<Long>()
        val action = CurrentBabySwitchAction(
            setCurrent = {
                writes += it
                entered.complete(Unit)
                release.await()
                error("IO failure")
            },
            selectedId = { 1L },
        )
        val context = overview().currentBabySwitchContext()
        val first = async { action.switch(2, context) }
        entered.await()
        assertThat(action.state.value.switching).isTrue()

        assertThat(action.switch(3, context)).isFalse()
        release.complete(Unit)
        first.await()

        assertThat(writes).containsExactly(2L)
        assertThat(action.state.value.failure).isNotNull()
        assertThat(action.state.value.switching).isFalse()
    }

    @Test
    fun aChangedBabyFamilyOrPermissionScopeInvalidatesOldFailurePermanently() = runTest {
        val original = overview()
        val changes = listOf(
            original.copy(current = original.current?.copy(clientUuid = "new-generation-baby")),
            original.copy(identity = original.identity.copy(familyId = "other-family")),
            original.copy(identity = original.identity.copy(membershipId = "other-member")),
            original.copy(identity = original.identity.copy(role = FamilyRole.Member)),
            original.copy(identity = original.identity.copy(enabled = false)),
        )
        for (changed in changes) {
            val action = CurrentBabySwitchAction(
                setCurrent = { error("IO failure") },
                selectedId = { 1L },
            )
            action.switch(2, original.currentBabySwitchContext())
            assertThat(action.state.value.failure?.messageFor(changed)).isNull()

            action.invalidateFor(changed.currentBabySwitchContext())
            action.invalidateFor(original.currentBabySwitchContext())

            assertThat(action.state.value.failure).isNull()
        }
    }

    private fun overview() = AccountOverviewUi(
        identity = FamilyIdentityUi(
            enabled = true,
            familyId = "family-1",
            membershipId = "member-1",
            role = FamilyRole.Owner,
        ),
        current = Baby(
            id = 1,
            familyId = 1,
            nickname = "年年",
            birthdayEpochDay = 20_000,
            themeColorArgb = 0xff6688aa.toInt(),
            clientUuid = "baby-1",
            updatedAt = 1,
        ),
    )
}
