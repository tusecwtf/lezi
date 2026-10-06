package com.lezi.babylog.core.model

/** Current on-device schema for the complete record-layout preference snapshot. */
const val DEVICE_LAYOUT_SNAPSHOT_VERSION: Int = 1

/**
 * One complete, device-local record-layout epoch.
 *
 * This value is never family-synced. Empty quick slots are intentional and remain empty.
 */
data class DeviceLayoutSnapshot(
    val version: Int = DEVICE_LAYOUT_SNAPSHOT_VERSION,
    val quickRecordSlots: List<String> = DEFAULT_QUICK_RECORD_SLOTS,
    val hiddenItems: Set<String> = emptySet(),
    val itemOrderJson: String = "[]",
    val categoryOrderJson: String = "[]",
) {
    val isCurrentVersion: Boolean
        get() = version == DEVICE_LAYOUT_SNAPSHOT_VERSION
}

/**
 * Enforce the invariants shared by every current-version writer.
 *
 * Duplicate non-empty slot keys are cleared in place. They are deliberately not replaced by
 * another catalog item, because user-owned empty slots must survive normalization.
 */
fun normalizeDeviceLayoutSnapshot(snapshot: DeviceLayoutSnapshot): DeviceLayoutSnapshot {
    val seen = mutableSetOf<String>()
    val normalizedSlots = normalizeQuickRecordSlots(snapshot.quickRecordSlots).map { key ->
        when {
            key.isEmpty() -> ""
            seen.add(key) -> key
            else -> ""
        }
    }
    return snapshot.copy(
        quickRecordSlots = normalizedSlots,
        hiddenItems = snapshot.hiddenItems.map(String::trim).filter(String::isNotEmpty).toSet(),
    )
}

/** Refuse to overwrite a layout written by a newer app schema. */
fun requireCurrentDeviceLayoutVersion(snapshot: DeviceLayoutSnapshot) {
    require(snapshot.isCurrentVersion) {
        "Unsupported device layout snapshot version ${snapshot.version}; " +
            "current version is $DEVICE_LAYOUT_SNAPSHOT_VERSION"
    }
}

/** Complete snapshot represented by this settings emission. */
fun SettingsLocal.deviceLayoutSnapshot(): DeviceLayoutSnapshot =
    DeviceLayoutSnapshot(
        version = deviceLayoutSnapshotVersion,
        quickRecordSlots = quickRecordSlots,
        hiddenItems = hiddenItems,
        itemOrderJson = itemOrderJson,
        categoryOrderJson = categoryOrderJson,
    )
