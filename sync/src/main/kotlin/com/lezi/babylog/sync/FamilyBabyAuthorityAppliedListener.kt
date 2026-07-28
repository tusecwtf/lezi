package com.lezi.babylog.sync

import javax.inject.Inject
import javax.inject.Singleton

/** Runs only after a complete authoritative Baby snapshot/page sequence is durable. */
fun interface FamilyBabyAuthorityAppliedListener {
    suspend fun onFamilyBabyAuthorityApplied()
}

@Singleton
class NoOpFamilyBabyAuthorityAppliedListener @Inject constructor() :
    FamilyBabyAuthorityAppliedListener {
    override suspend fun onFamilyBabyAuthorityApplied() = Unit
}
