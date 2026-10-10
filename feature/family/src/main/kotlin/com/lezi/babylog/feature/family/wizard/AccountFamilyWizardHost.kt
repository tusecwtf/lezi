package com.lezi.babylog.feature.family.wizard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.family.FamilyWizardController
import com.lezi.babylog.domain.family.FamilyWizardEntry
import com.lezi.babylog.domain.family.FamilyWizardOutcome
import com.lezi.babylog.domain.family.FamilyWizardSnapshot
import com.lezi.babylog.domain.family.FamilyWizardState
import com.lezi.babylog.domain.family.SyncFamilyWizardGateway
import com.lezi.babylog.sync.session.CertificateTrustCandidate
import com.lezi.babylog.sync.session.DEFAULT_SERVER_PORT
import com.lezi.babylog.sync.session.DEFAULT_SERVER_SCHEME
import com.lezi.babylog.sync.qr.MemberLoginQrPayload
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.PendingMemberLogin
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal fun memberApprovalRequestForDisplay(
    overviewRequest: PendingMemberLogin?,
): PendingMemberLogin? = overviewRequest

internal fun memberApprovalCancelSucceeded(state: FamilyWizardState): Boolean =
    state is FamilyWizardState.Editing

/**
 * Technical endpoint fields for wizard draft seed only.
 * Kept off [com.lezi.babylog.feature.family.overview.AccountOverviewUi] (技术凭证不回流).
 */
data class WizardEndpointSeed(
    val baseUrl: String = "",
    val serverHost: String = "",
    val serverPort: Int = DEFAULT_SERVER_PORT,
    val serverScheme: String = DEFAULT_SERVER_SCHEME,
)

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

    /** Session endpoint projection for join/create draft seed (not account overview). */
    val endpointSeed: StateFlow<WizardEndpointSeed> = sync.sessionPresentation().map { session ->
        WizardEndpointSeed(
            baseUrl = session.baseUrl,
            serverHost = session.serverHost,
            serverPort = session.serverPort,
            serverScheme = session.serverScheme,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WizardEndpointSeed())

    init {
        viewModelScope.launch {
            sync.memberLoginChecks().collect(familyWizard::observeMemberLoginCheck)
        }
        viewModelScope.launch {
            combine(sync.pendingMemberLogin(), sync.sessionPresentation()) { pending, session ->
                pending to session
            }.collect { (pending, session) ->
                familyWizard.reconcilePendingMemberApproval(
                    FamilyWizardSnapshot.empty(FamilyWizardEntry.Account).copy(
                        mode = com.lezi.babylog.domain.family.FamilyWizardMode.Join,
                        step = com.lezi.babylog.domain.family.FamilyWizardStep.Identity,
                        host = session.serverHost,
                        portText = session.serverPort.toString(),
                        scheme = session.serverScheme,
                        joinRole = com.lezi.babylog.domain.family.FamilyWizardJoinRole.Member,
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

    fun clearPresentedFailure() = familyWizard.clearPresentedFailure()

    fun retryLastStep(
        bootstrapSecret: String = "",
        ownerTakeover: Boolean = false,
        memberQrDeviceName: String = "",
    ) {
        viewModelScope.launch {
            familyWizard.retryLastStep(bootstrapSecret, ownerTakeover, memberQrDeviceName)
        }
    }

    fun checkMemberApproval() {
        viewModelScope.launch { familyWizard.checkMemberApproval() }
    }

    fun cancelMemberApproval(onCancelled: () -> Unit = {}) {
        viewModelScope.launch {
            familyWizard.cancelMemberApproval()
            if (memberApprovalCancelSucceeded(familyWizardState.value)) {
                onCancelled()
            }
        }
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
