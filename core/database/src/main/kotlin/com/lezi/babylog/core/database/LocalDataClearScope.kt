package com.lezi.babylog.core.database

/**
 * Business scope shared by the domain clear, sync barrier, and both durable cleanup stores.
 *
 * Reminder and replica cleanup remain separate hand-offs with different legacy storage keys;
 * keeping those keys here makes adding or renaming a scope a single adaptation point.
 */
enum class LocalDataClearScope(
    internal val reminderOperationKey: String,
    internal val replicaScopeKey: String,
) {
    RecordsOnly(
        reminderOperationKey = "records_clear",
        replicaScopeKey = "records_only",
    ),
    AllLocalData(
        reminderOperationKey = "all_local_data_clear",
        replicaScopeKey = "all_local",
    ),
    ;

    companion object {
        internal fun fromReminderOperationKey(key: String): LocalDataClearScope? =
            entries.firstOrNull { it.reminderOperationKey == key }

        internal fun fromReplicaScopeKey(key: String): LocalDataClearScope? =
            entries.firstOrNull { it.replicaScopeKey == key }

        /** The widest completed scope, or null when neither hand-off recovered work. */
        fun widest(vararg scopes: LocalDataClearScope?): LocalDataClearScope? = when {
            scopes.any { it == AllLocalData } -> AllLocalData
            scopes.any { it == RecordsOnly } -> RecordsOnly
            else -> null
        }
    }
}
