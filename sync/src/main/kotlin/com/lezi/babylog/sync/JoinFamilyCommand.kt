package com.lezi.babylog.sync

/** Complete, caller-owned input for one join attempt. */
data class JoinFamilyCommand(
    val invitation: String,
    val homeLanConfig: HomeLanServerConfig,
    val displayName: String? = null,
)
