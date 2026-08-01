package com.lezi.babylog.feature.onboarding

import java.lang.reflect.Modifier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Public seams for ticket 29 (self-confirmed design notes):
 *
 * - [OnboardingViewModel]: thin lifecycle over FamilyWizardController plus local
 *   createBaby. Does not own a second QR/session state machine.
 * - Step/dialog/QR composables are UI only; product order and error mapping stay
 *   on the shared controller + pure scan parse seam.
 *
 * Tests hit declared public methods only — not private helpers or file paths.
 */
class OnboardingHostCommandSurfaceTest {
    @Test
    fun onboardingViewModelThinDelegatesControllerIncludingMemberLoginQrAndCreateBaby() {
        val surface = publicMethods(OnboardingViewModel::class.java)

        for (method in listOf(
            "getFamilyWizardState",
            "getVerifiedEndpoint",
            "getPendingMemberLogin",
            "getReclaimedFamilyEmpty",
            "submitFamilyWizard",
            "connectEndpoint",
            "trustCertificate",
            "keepOffline",
            "forgetEndpoint",
            "retryInitialFamilyDataRecovery",
            "checkMemberApproval",
            "cancelMemberApproval",
            "verifyMemberLoginQr",
            "cancelMemberLoginQr",
            "claimMemberLoginQr",
            "consumeFamilyWizardCompletion",
            "createBaby",
        )) {
            assertTrue("missing public method: $method", surface.contains(method))
        }
        // No second wizard/session machine surface on the host.
        for (method in listOf(
            "beginFamilyWizard",
            "createMemberLoginQr",
            "refreshMembers",
            "approveNewMemberLogin",
            "deleteFamily",
            "leave",
            "openOptionalAppUpdate",
        )) {
            assertFalse("unexpected public method: $method", surface.contains(method))
        }
    }

    private fun publicMethods(type: Class<*>): Set<String> =
        type.declaredMethods
            .asSequence()
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
            .map { it.name }
            .toSet()
}
