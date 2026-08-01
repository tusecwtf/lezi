package com.lezi.babylog.feature.family.wizard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.FamilyWizardController
import com.lezi.babylog.domain.FamilyWizardEntry
import com.lezi.babylog.domain.FamilyWizardOutcome
import com.lezi.babylog.domain.FamilyWizardSnapshot
import com.lezi.babylog.domain.FamilyWizardState
import com.lezi.babylog.domain.SyncFamilyWizardGateway
import com.lezi.babylog.sync.CertificateTrustCandidate
import com.lezi.babylog.sync.MemberLoginQrPayload
import com.lezi.babylog.sync.SyncPort
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Thin account-entry host over [FamilyWizardController], including ticket-23
 * member-login QR verify/claim/cancel/retry. Does not own roster or app-update.
 */
@HiltViewModel
class AccountFamilyWizardHost @Inject constructor(
    private val sync: SyncPort,
    careLog: CareLog,
) : ViewModel() {
    private val familyWizard = FamilyWizardController(
        gateway = SyncFamilyWizardGateway(sync, careLog),
        initialSnapshot = FamilyWizardSnapshot.empty(FamilyWizardEntry.Account),
    )
    val familyWizardState = familyWizard.state
    val verifiedEndpoint = sync.verifiedEndpoint()

    init {
        viewModelScope.launch {
            sync.memberLoginChecks().collect(familyWizard::observeMemberLoginCheck)
        }
        viewModelScope.launch {
            combine(sync.pendingMemberLogin(), sync.session()) { pending, session ->
                pending to session
            }.collect { (pending, session) ->
                if (pending != null) {
                    familyWizard.restorePendingMemberApproval(
                        FamilyWizardSnapshot.empty(FamilyWizardEntry.Account).copy(
                            mode = com.lezi.babylog.domain.FamilyWizardMode.Join,
                            step = com.lezi.babylog.domain.FamilyWizardStep.Identity,
                            host = session.serverHost,
                            portText = session.serverPort.toString(),
                            scheme = session.serverScheme,
                            joinRole = com.lezi.babylog.domain.FamilyWizardJoinRole.Member,
                        ),
                        pending,
                    )
                }
            }
        }
    }

    fun submitFamilyWizard(
        snapshot: FamilyWizardSnapshot,
        bootstrapSecret: String = "",
        ownerTakeover: Boolean = false,
    ) {
        viewModelScope.launch {
            familyWizard.submit(snapshot, bootstrapSecret, ownerTakeover)
        }
    }

    fun beginFamilyWizard(snapshot: FamilyWizardSnapshot) {
        familyWizard.begin(snapshot)
    }

    fun connectEndpoint(endpointDraft: String) {
        viewModelScope.launch {
            familyWizard.connectEndpoint(FamilyWizardEntry.Account, endpointDraft)
        }
    }

    fun trustCertificate(candidate: CertificateTrustCandidate) {
        viewModelScope.launch {
            familyWizard.trustCertificate(FamilyWizardEntry.Account, candidate)
        }
    }

    fun keepOffline() {
        familyWizard.keepOffline(FamilyWizardEntry.Account)
    }

    fun forgetEndpoint() {
        viewModelScope.launch { familyWizard.forgetEndpoint(FamilyWizardEntry.Account) }
    }

    fun consumeFamilyWizardCompletion(): FamilyWizardOutcome? =
        familyWizard.consumeCompletion()

    fun checkMemberApproval() {
        viewModelScope.launch { familyWizard.checkMemberApproval() }
    }

    fun cancelMemberApproval() {
        viewModelScope.launch { familyWizard.cancelMemberApproval() }
    }

    fun verifyMemberLoginQr(payload: MemberLoginQrPayload) {
        viewModelScope.launch {
            familyWizard.verifyMemberLoginQr(FamilyWizardEntry.Account, payload)
        }
    }

    fun cancelMemberLoginQr() {
        viewModelScope.launch { familyWizard.cancelMemberLoginQr() }
    }

    fun claimMemberLoginQr(payload: MemberLoginQrPayload, deviceName: String) {
        viewModelScope.launch {
            familyWizard.claimMemberLoginQr(payload, deviceName)
        }
    }

    fun retryMemberLoginQrRecovery() {
        viewModelScope.launch {
            familyWizard.retryReclaimedDataRecovery()
        }
    }
}
