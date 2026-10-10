package com.lezi.babylog.core.model

/** Content belongs to the observer; an Owner can select, not rewrite foreign observations. */
fun canEditWakeContent(
    observerMembershipId: String,
    actorMembershipId: String,
    actorIsOwner: Boolean,
    syncDirty: Boolean,
): Boolean {
    val actor = actorMembershipId.trim()
    if (observerMembershipId.isNotBlank()) return observerMembershipId == actor
    return actorIsOwner || (actor.isEmpty() && syncDirty)
}
