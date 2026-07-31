package com.lezi.babylog.feature.family

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FamilyMemberDecisionPolicyTest {
    @Test
    fun displayNameComparisonMatchesServerNfkcWhitespaceAndCaseNormalization() {
        assertThat(normalizedFamilyDisplayNameKey("  Ｍｏｍ\u3000  Alice  "))
            .isEqualTo(normalizedFamilyDisplayNameKey("mom Alice"))
    }

    @Test
    fun familyDeletionRequiresTrimmedExactNameAndANonBlankRootPassword() {
        assertThat(canConfirmFamilyDeletion("乐乐一家", "  乐乐一家  ", "root-secret")).isTrue()
        assertThat(canConfirmFamilyDeletion("Lezi Home", "lezi home", "root-secret")).isFalse()
        assertThat(canConfirmFamilyDeletion("乐乐一家", "乐乐一家", "   ")).isFalse()
        assertThat(canConfirmFamilyDeletion("", "", "root-secret")).isFalse()
    }
}
