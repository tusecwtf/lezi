package com.lezi.babylog.feature.family

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.feature.family.overview.familyConflictBadgePresentation
import org.junit.Test

class FamilyConflictBadgePolicyTest {
    @Test
    fun joinedFamilyShowsOneRootCountAndCapsVisualBadgeOnly() {
        val ordinary = familyConflictBadgePresentation(isJoined = true, openRootCount = 5)
        assertThat(ordinary.visible).isTrue()
        assertThat(ordinary.badgeText).isEqualTo("5")
        assertThat(ordinary.contentDescription).isEqualTo("家庭待处理，5项，打开待处理列表")

        val large = familyConflictBadgePresentation(isJoined = true, openRootCount = 120)
        assertThat(large.badgeText).isEqualTo("99+")
        assertThat(large.contentDescription).contains("120项")
    }

    @Test
    fun unjoinedOrEmptyStateHasNoConflictEntry() {
        assertThat(familyConflictBadgePresentation(false, 2).visible).isFalse()
        assertThat(familyConflictBadgePresentation(true, 0).visible).isFalse()
    }
}
