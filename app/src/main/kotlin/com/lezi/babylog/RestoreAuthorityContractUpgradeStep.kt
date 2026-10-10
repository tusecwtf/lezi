package com.lezi.babylog

import com.lezi.babylog.core.common.LocalDataDomain
import com.lezi.babylog.core.common.LocalDataUpgradeStep
import javax.inject.Inject

/** Contract 6→7: semantic journal/MIME revision; Room29 tables and every fact remain unchanged. */
internal class RestoreAuthorityContractUpgradeStep @Inject constructor() : LocalDataUpgradeStep {
    override val fromContractVersion = 6
    override val toContractVersion = 7
    override val affectedDomains = setOf(LocalDataDomain.Room, LocalDataDomain.Settings, LocalDataDomain.Media)
    override suspend fun migrate() = Unit
    override suspend fun verify() = Unit
}
