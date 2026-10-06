package com.lezi.babylog

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.feature.widget.WidgetComposerTarget
import org.junit.Test

class ExternalNavigationTrustPolicyTest {
    @Test
    fun browsableFulfillAndWidgetStayUnauthorizedUntilUserConfirms() {
        val fulfill = UntrustedExternalNavigation.Fulfill(
            PendingFulfillPlan(planId = null, clientUuid = "plan-uuid"),
        )
        val widget = UntrustedExternalNavigation.WidgetComposer(
            WidgetComposerTarget(babyId = 42, type = RecordType.FORMULA),
        )

        assertThat(authorizeExternalNavigation(fulfill, userConfirmed = false)).isNull()
        assertThat(authorizeExternalNavigation(widget, userConfirmed = false)).isNull()
        assertThat(authorizeExternalNavigation(fulfill, userConfirmed = true)).isEqualTo(
            AuthorizedExternalNavigation.Fulfill(fulfill.target),
        )
        assertThat(authorizeExternalNavigation(widget, userConfirmed = true)).isEqualTo(
            AuthorizedExternalNavigation.WidgetComposer(widget.target),
        )
    }

    @Test
    fun browsableCarePlanRequiresViewActionAndOneCanonicalUuidSegment() {
        val canonical = FulfillIntentSnapshot(
            action = "android.intent.action.VIEW",
            scheme = "lezi",
            host = "care-plan",
            pathSegments = listOf("plan-uuid"),
            planIdExtra = null,
            clientUuidExtra = null,
        )

        assertThat(decodeUntrustedFulfill(canonical)).isEqualTo(
            PendingFulfillPlan(planId = null, clientUuid = "plan-uuid"),
        )
        assertThat(
            decodeUntrustedFulfill(canonical.copy(action = "android.intent.action.SEND")),
        ).isNull()
        assertThat(
            decodeUntrustedFulfill(canonical.copy(pathSegments = listOf("plan", "extra"))),
        ).isNull()
        assertThat(
            decodeUntrustedFulfill(canonical.copy(clientUuidExtra = "conflict")),
        ).isNull()
    }

    @Test
    fun localReminderRequiresCanonicalRouteAndMatchingPlanIdentity() {
        val canonical = FulfillIntentSnapshot(
            action = "android.intent.action.MAIN",
            scheme = "lezi",
            host = "local-reminder",
            pathSegments = listOf("care-plan", "73", "fulfill"),
            planIdExtra = 73,
            clientUuidExtra = "plan-uuid",
        )

        assertThat(decodeUntrustedFulfill(canonical)).isEqualTo(
            PendingFulfillPlan(planId = 73, clientUuid = "plan-uuid"),
        )
        assertThat(
            decodeUntrustedFulfill(canonical.copy(pathSegments = listOf("care-plan", "74", "fulfill"))),
        ).isNull()
        assertThat(
            decodeUntrustedFulfill(
                canonical.copy(
                    scheme = null,
                    host = null,
                    pathSegments = emptyList(),
                ),
            ),
        ).isNull()
    }
}
