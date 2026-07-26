package com.lezi.babylog.sync

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Invoked after a remote care_plan package (plan + all photos) is fully applied
 * in one Room transaction. Domain uses this to schedule local reminders / system
 * calendar projection without injecting CareLog into [RealSyncPort].
 *
 * Local reminder prefs and calendar settings never enter the family package;
 * receivers project using their own device configuration only.
 */
fun interface CarePlanFamilyAppliedListener {
    /**
     * @param planClientUuids portable care plan ids that just became fully visible
     *   (including tombstones / terminal statuses that should cancel projections).
     */
    suspend fun onFamilyCarePlansApplied(planClientUuids: List<String>)
}

@Singleton
class NoOpCarePlanFamilyAppliedListener @Inject constructor() : CarePlanFamilyAppliedListener {
    override suspend fun onFamilyCarePlansApplied(planClientUuids: List<String>) = Unit
}
