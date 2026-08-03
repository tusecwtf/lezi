package com.lezi.babylog.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lezi.babylog.core.common.productUiError
import com.lezi.babylog.core.common.SingleFlightAction
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.CreateBabyInput
import com.lezi.babylog.domain.family.FamilyWizardController
import com.lezi.babylog.domain.family.FamilyWizardEntry
import com.lezi.babylog.domain.family.FamilyWizardJoinRole
import com.lezi.babylog.domain.family.FamilyWizardMode
import com.lezi.babylog.domain.family.FamilyWizardOutcome
import com.lezi.babylog.domain.family.FamilyWizardSnapshot
import com.lezi.babylog.domain.family.FamilyWizardState
import com.lezi.babylog.domain.family.FamilyWizardStep
import com.lezi.babylog.domain.family.SyncFamilyWizardGateway
import com.lezi.babylog.sync.InitialFamilyDataRecovery
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.qr.MemberLoginQrPayload
import com.lezi.babylog.sync.session.CertificateTrustCandidate
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Thin onboarding host over [FamilyWizardController], including ticket-23
 * member-login QR verify/claim/cancel/retry. Does not own a second wizard state machine.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val careLog: CareLog,
    private val sync: SyncPort,
) : ViewModel() {
    private val createBabyAction = SingleFlightAction()
    private val familyWizard = FamilyWizardController(
        gateway = SyncFamilyWizardGateway(sync, careLog),
        initialSnapshot = FamilyWizardSnapshot.empty(FamilyWizardEntry.Onboarding),
    )
    private val mutableReclaimedFamilyEmpty = MutableStateFlow<Boolean?>(null)
    val familyWizardState = familyWizard.state
    val reclaimedFamilyEmpty = mutableReclaimedFamilyEmpty.asStateFlow()
    val creatingBaby = createBabyAction.busy
    val verifiedEndpoint = sync.verifiedEndpoint()
    val pendingMemberLogin = sync.pendingMemberLogin().stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        null,
    )

    init {
        viewModelScope.launch {
            sync.memberLoginChecks().collect(familyWizard::observeMemberLoginCheck)
        }
        viewModelScope.launch {
            combine(pendingMemberLogin, sync.session()) { pending, session -> pending to session }
                .collect { (pending, session) ->
                    familyWizard.reconcilePendingMemberApproval(
                        FamilyWizardSnapshot.empty(FamilyWizardEntry.Onboarding).copy(
                            mode = FamilyWizardMode.Join,
                            step = FamilyWizardStep.Identity,
                            host = session.serverHost,
                            portText = session.serverPort.toString(),
                            scheme = session.serverScheme,
                            joinRole = FamilyWizardJoinRole.Member,
                        ),
                        pending,
                    )
                }
        }
    }

    fun submitFamilyWizard(
        snapshot: FamilyWizardSnapshot,
        bootstrapSecret: String = "",
        ownerTakeover: Boolean = false,
    ) {
        viewModelScope.launch {
            mutableReclaimedFamilyEmpty.value = null
            familyWizard.submit(snapshot, bootstrapSecret, ownerTakeover)
            updateRecoveredFamilyEmptiness()
        }
    }

    fun connectEndpoint(endpointDraft: String) {
        viewModelScope.launch {
            familyWizard.connectEndpoint(FamilyWizardEntry.Onboarding, endpointDraft)
        }
    }

    fun trustCertificate(candidate: CertificateTrustCandidate) {
        viewModelScope.launch {
            familyWizard.trustCertificate(FamilyWizardEntry.Onboarding, candidate)
        }
    }

    fun keepOffline() {
        familyWizard.keepOffline(FamilyWizardEntry.Onboarding)
    }

    fun forgetEndpoint() {
        viewModelScope.launch { familyWizard.forgetEndpoint(FamilyWizardEntry.Onboarding) }
    }

    /** Shared retry for all CreateSession recovery outcomes (owner reclaim, member QR, …). */
    fun retryInitialFamilyDataRecovery() {
        viewModelScope.launch {
            familyWizard.retryReclaimedDataRecovery()
            updateRecoveredFamilyEmptiness()
        }
    }

    fun checkMemberApproval() {
        viewModelScope.launch { familyWizard.checkMemberApproval() }
    }

    fun cancelMemberApproval() {
        viewModelScope.launch { familyWizard.cancelMemberApproval() }
    }

    fun verifyMemberLoginQr(payload: MemberLoginQrPayload) {
        viewModelScope.launch {
            familyWizard.verifyMemberLoginQr(FamilyWizardEntry.Onboarding, payload)
        }
    }

    fun cancelMemberLoginQr() {
        viewModelScope.launch { familyWizard.cancelMemberLoginQr() }
    }

    fun claimMemberLoginQr(payload: MemberLoginQrPayload, deviceName: String) {
        viewModelScope.launch {
            familyWizard.claimMemberLoginQr(payload, deviceName)
            updateRecoveredFamilyEmptiness()
        }
    }

    fun consumeFamilyWizardCompletion(): FamilyWizardOutcome? =
        familyWizard.consumeCompletion()

    private suspend fun updateRecoveredFamilyEmptiness() {
        val outcome = (familyWizard.state.value as? FamilyWizardState.Completed)?.outcome
        if ((outcome is FamilyWizardOutcome.Reclaimed ||
                outcome is FamilyWizardOutcome.OwnerLoggedIn) &&
            outcome.dataRecovery == InitialFamilyDataRecovery.Complete
        ) {
            mutableReclaimedFamilyEmpty.value = careLog.listBabies().isEmpty()
        }
    }

    fun createBaby(
        nickname: String,
        sex: String?,
        birthdayEpochDay: Long,
        birthWeightGrams: Int?,
        themeColorArgb: Int,
        onDone: (String?) -> Unit,
    ) {
        viewModelScope.launch {
            createBabyAction.run {
                try {
                    careLog.createBaby(
                        CreateBabyInput(
                            nickname = nickname.trim(),
                            sex = sex,
                            birthdayEpochDay = birthdayEpochDay,
                            birthWeightGrams = birthWeightGrams,
                            themeColorArgb = themeColorArgb,
                        ),
                    )
                    onDone(null)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    onDone(productUiError(t, "创建失败"))
                }
            }
        }
    }
}
