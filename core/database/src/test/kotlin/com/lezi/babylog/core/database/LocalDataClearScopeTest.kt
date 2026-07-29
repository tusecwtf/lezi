package com.lezi.babylog.core.database

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LocalDataClearScopeTest {
    @Test
    fun storeKeysRoundTripThroughOneBusinessScope() {
        LocalDataClearScope.entries.forEach { scope ->
            assertThat(LocalDataClearScope.fromReminderOperationKey(scope.reminderOperationKey))
                .isEqualTo(scope)
            assertThat(LocalDataClearScope.fromReplicaScopeKey(scope.replicaScopeKey))
                .isEqualTo(scope)
        }
    }

    @Test
    fun widestPromotesAllLocalDataAcrossRecoveryStores() {
        assertThat(
            LocalDataClearScope.widest(
                LocalDataClearScope.RecordsOnly,
                LocalDataClearScope.AllLocalData,
            ),
        ).isEqualTo(LocalDataClearScope.AllLocalData)
        assertThat(LocalDataClearScope.widest(null, LocalDataClearScope.RecordsOnly))
            .isEqualTo(LocalDataClearScope.RecordsOnly)
        assertThat(LocalDataClearScope.widest(null, null)).isNull()
    }
}
