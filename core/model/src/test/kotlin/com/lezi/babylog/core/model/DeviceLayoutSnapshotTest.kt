package com.lezi.babylog.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class DeviceLayoutSnapshotTest {
    @Test
    fun normalizeKeepsExactlyFourUniqueSlotsWithoutAutoFill() {
        val normalized = normalizeDeviceLayoutSnapshot(
            DeviceLayoutSnapshot(
                quickRecordSlots = listOf(" pee ", "sleep", "pee", "", "formula"),
            ),
        )

        assertThat(normalized.quickRecordSlots).containsExactly("pee", "sleep", "", "").inOrder()
    }

    @Test
    fun normalizePreservesAnyNumberOfIntentionalEmptySlots() {
        val normalized = normalizeDeviceLayoutSnapshot(
            DeviceLayoutSnapshot(quickRecordSlots = emptyList()),
        )

        assertThat(normalized.quickRecordSlots).containsExactly("", "", "", "").inOrder()
    }

    @Test
    fun normalizeCleansHiddenKeysWithoutChangingOrderingPayloads() {
        val normalized = normalizeDeviceLayoutSnapshot(
            DeviceLayoutSnapshot(
                hiddenItems = linkedSetOf(" sleep ", "", "sleep", "pee"),
                itemOrderJson = "[\"sleep\",\"pee\"]",
                categoryOrderJson = "[\"routine\",\"excretion\"]",
            ),
        )

        assertThat(normalized.hiddenItems).containsExactly("sleep", "pee")
        assertThat(normalized.itemOrderJson).isEqualTo("[\"sleep\",\"pee\"]")
        assertThat(normalized.categoryOrderJson).isEqualTo("[\"routine\",\"excretion\"]")
    }

    @Test
    fun futureVersionCannotBeWrittenByCurrentApp() {
        val future = DeviceLayoutSnapshot(version = DEVICE_LAYOUT_SNAPSHOT_VERSION + 1)

        assertThrows(IllegalArgumentException::class.java) {
            requireCurrentDeviceLayoutVersion(future)
        }
    }
}
