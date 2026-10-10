package com.lezi.babylog.validation

import com.lezi.babylog.sync.session.SyncPreferences
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Read the real owner for isolated synthetic fixture setup; no replacement bindings. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ProductionAcceptanceEntryPoint {
    fun localDataUpgradeEnvironment(): com.lezi.babylog.core.common.LocalDataUpgradeEnvironment
    fun widgetStateStore(): com.lezi.babylog.feature.widget.WidgetStateStore
    fun acceptanceWidgetController(): com.lezi.babylog.feature.widget.CareWidgetRefreshController
    fun localDataMutationEpoch(): com.lezi.babylog.domain.localdata.LocalDataMutationEpoch
    fun database(): com.lezi.babylog.core.database.LeziDatabase
    fun settingsStore(): com.lezi.babylog.core.datastore.SettingsStore
    fun syncPreferences(): SyncPreferences
}
