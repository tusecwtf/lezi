package com.lezi.babylog.domain.family

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.sync.CreateFamilyResult
import com.lezi.babylog.sync.InitialFamilyDataRecovery
import com.lezi.babylog.sync.session.SyncSession
import com.lezi.babylog.sync.session.SyncSessionPresentation
import com.lezi.babylog.sync.session.toPresentation
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FamilyWizardSessionPresentationTest {
    @Test
    fun completedAndRetryableCommittedStatesDiscardOwnerCredentials() = runTest {
        val owner = ownerSession().copy(
            accessToken = "retained-access-must-not-escape",
            refreshToken = "retained-refresh-must-not-escape",
            serverHost = "nas.example.test",
        )
        for (recovery in listOf(
            InitialFamilyDataRecovery.Complete,
            InitialFamilyDataRecovery.RetryRequired(),
        )) {
            val gateway = RecordingFamilyWizardGateway(
                createResult = Result.success(CreateFamilyResult(owner, false, recovery)),
                recoveryResult = Result.failure(IllegalStateException("offline")),
            )
            val controller = FamilyWizardController(gateway)
            controller.submit(snapshot(FamilyWizardEntry.Account, FamilyWizardMode.Create), "once")
            val completed = controller.state.value as FamilyWizardState.Completed
            assertThat(completed.outcome.session).isEqualTo(owner.toPresentation())
            assertThat(completed.outcome.session.isJoined).isTrue()
            assertThat(completed.toString()).doesNotContain(owner.accessToken)
            assertThat(completed.toString()).doesNotContain(owner.refreshToken)
            if (recovery is InitialFamilyDataRecovery.RetryRequired) {
                controller.retryReclaimedDataRecovery()
                val retry = controller.state.value as FamilyWizardState.RetryableFailure
                assertThat(retry.committedOutcome!!.session).isEqualTo(owner.toPresentation())
                assertThat(retry.toString()).doesNotContain(owner.accessToken)
                assertThat(retry.toString()).doesNotContain(owner.refreshToken)
            }
        }
    }

    @Test
    fun everyRetainedWizardOutcomeExposesOnlyPresentationSession() {
        val outcomes = listOf(
            FamilyWizardOutcome.Created::class.java,
            FamilyWizardOutcome.Reclaimed::class.java,
            FamilyWizardOutcome.OwnerLoggedIn::class.java,
            FamilyWizardOutcome.MemberApproved::class.java,
            FamilyWizardOutcome.MemberLoginQrClaimed::class.java,
        )
        assertThat(FamilyWizardOutcome::class.java.getMethod("getSession").returnType)
            .isEqualTo(SyncSessionPresentation::class.java)
        for (type in outcomes) {
            assertThat(type.getMethod("getSession").returnType)
                .isEqualTo(SyncSessionPresentation::class.java)
            assertThat(type.declaredFields.none { it.type == SyncSession::class.java }).isTrue()
        }
    }
}
