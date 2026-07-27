package com.lezi.babylog.feature.family

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class FamilyTouchTargetPolicyTest {
    @Test
    fun memberRosterEntranceUsesTheRegularFortyEightDpTouchTarget() {
        assertEquals(48.dp, familyMemberRosterMinimumTouchHeight())
    }
}
