package com.lezi.babylog.domain.family

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Bumped when the local membership display name is written.
 * Session token, cursor, and last-success do not reload that row.
 */
object LocalFamilyIdentityInvalidations {
    val epoch = MutableStateFlow(0)

    fun bump() {
        epoch.value = epoch.value + 1
    }
}
