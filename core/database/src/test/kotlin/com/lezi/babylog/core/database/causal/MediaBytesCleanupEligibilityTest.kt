package com.lezi.babylog.core.database.causal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MediaBytesCleanupEligibilityTest {
    @Test
    fun bytesStayWhileAnyMediaAssetOrReferenceHolderExists() {
        assertThat(mediaBytesEligibleForCleanup(1, 0)).isFalse()
        assertThat(mediaBytesEligibleForCleanup(0, 1)).isFalse()
        assertThat(
            mediaBytesEligibleForCleanup(
                activeMediaAssetReferences = 0,
                mediaReferenceHolders = 0,
            ),
        ).isTrue()
    }

    @Test
    fun branchAndDuplicateSourceHoldersBlockCleanupEvenWithoutLiveAsset() {
        // Simulated: live asset tombstoned (0) but conflict_branch + duplicate_source remain.
        assertThat(mediaBytesEligibleForCleanup(0, 2)).isFalse()
    }
}
