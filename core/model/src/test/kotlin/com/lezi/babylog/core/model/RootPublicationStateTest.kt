package com.lezi.babylog.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RootPublicationStateTest {
    @Test
    fun missingOrInvalidReceiptFailsClosedAsNeverPublished() {
        assertThat(rootPublicationState(localUpdatedAt = 200, familyPublishedUpdatedAt = null))
            .isEqualTo(RootPublicationState.NEVER_PUBLISHED)
        assertThat(rootPublicationState(localUpdatedAt = 200, familyPublishedUpdatedAt = 0))
            .isEqualTo(RootPublicationState.NEVER_PUBLISHED)
        assertThat(rootPublicationState(localUpdatedAt = 200, familyPublishedUpdatedAt = 201))
            .isEqualTo(RootPublicationState.NEVER_PUBLISHED)
        assertThat(rootPublicationState(localUpdatedAt = 0, familyPublishedUpdatedAt = 0))
            .isEqualTo(RootPublicationState.NEVER_PUBLISHED)
        assertThat(rootPublicationState(localUpdatedAt = -1, familyPublishedUpdatedAt = -1))
            .isEqualTo(RootPublicationState.NEVER_PUBLISHED)
    }

    @Test
    fun olderPositiveReceiptMeansFamilyStillSeesPreviousVersion() {
        assertThat(rootPublicationState(localUpdatedAt = 200, familyPublishedUpdatedAt = 199))
            .isEqualTo(RootPublicationState.PREVIOUS_VERSION_PUBLISHED)
    }

    @Test
    fun matchingReceiptConfirmsCurrentVersion() {
        assertThat(rootPublicationState(localUpdatedAt = 200, familyPublishedUpdatedAt = 200))
            .isEqualTo(RootPublicationState.CURRENT_VERSION_PUBLISHED)
    }
}
