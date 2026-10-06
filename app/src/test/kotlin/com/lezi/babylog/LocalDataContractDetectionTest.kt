package com.lezi.babylog

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.common.LocalDataUpgradeBlockReason
import com.lezi.babylog.core.common.LocalDataUpgradeFailure
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalDataContractDetectionTest {
    @Test
    fun declaredContractWithUnexpectedRoomSchemaIsInconsistent() {
        val failure = assertThrows(LocalDataUpgradeFailure::class.java) {
            detectLocalDataInspection(
                markerVersion = 1,
                roomSchema = 17,
                currentContractVersion = 2,
                roomSchemasByContract = mapOf(1 to 24, 2 to 25),
            )
        }

        assertThat(failure.reason).isEqualTo(LocalDataUpgradeBlockReason.InconsistentData)
    }

    @Test
    fun markerlessPermanentBaselineIsInferredForADirectFutureUpgrade() {
        val inspection = detectLocalDataInspection(
            markerVersion = null,
            roomSchema = 24,
            currentContractVersion = 3,
            roomSchemasByContract = mapOf(1 to 24, 2 to 25, 3 to 26),
        )

        assertThat(inspection.contractVersion).isEqualTo(1)
        assertThat(inspection.baselineMarkerRequired).isFalse()
    }

    @Test
    fun verifiedPendingTransitionResumesAfterProcessDeathDuringMutation() {
        val inspection = detectLocalDataInspection(
            markerVersion = 1,
            roomSchema = 25,
            currentContractVersion = 3,
            roomSchemasByContract = mapOf(1 to 24, 2 to 25, 3 to 26),
            pendingTransition = PendingLocalDataTransition(1, 2),
        )

        assertThat(inspection.contractVersion).isEqualTo(1)
    }

    @Test
    fun undeclaredPendingTransitionFailsClosed() {
        val failure = assertThrows(LocalDataUpgradeFailure::class.java) {
            detectLocalDataInspection(
                markerVersion = 1,
                roomSchema = 25,
                currentContractVersion = 3,
                roomSchemasByContract = mapOf(1 to 24, 3 to 26),
                pendingTransition = PendingLocalDataTransition(1, 2),
            )
        }

        assertThat(failure.reason).isEqualTo(LocalDataUpgradeBlockReason.InconsistentData)
    }
}
