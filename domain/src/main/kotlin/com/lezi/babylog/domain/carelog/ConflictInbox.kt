package com.lezi.babylog.domain.carelog

import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.recordTypeLabel
import com.lezi.babylog.sync.conflict.ConflictRoot
import com.lezi.babylog.sync.conflict.ConflictRootType

data class ConflictInbox(
    val items: List<ConflictInboxItem> = emptyList(),
) {
    val count: Int get() = items.size
}

data class ConflictInboxItem(
    val conflictId: String,
    val rootType: ConflictRootType,
    val clientUuid: String,
    val rootLabel: String,
    val title: String,
    val babyLabel: String?,
    val actor: ConflictInboxActor,
    /** The current local stable root state; null when only the summary is available. */
    val stableTombstone: Boolean?,
    /** Branch deletion state is exact only after a complete detail snapshot is cached. */
    val branchTombstone: ConflictInboxBranchTombstone,
    /** Media total is exact only after a complete detail snapshot is cached. */
    val media: ConflictInboxMedia,
    /** Server receipt/update time used by the global inbox order. */
    val updatedAt: Long,
)

sealed interface ConflictInboxActor {
    data class Known(val membershipId: String, val label: String) : ConflictInboxActor
    data object RequiresDetail : ConflictInboxActor
}

sealed interface ConflictInboxBranchTombstone {
    data class Known(val hasCandidate: Boolean) : ConflictInboxBranchTombstone
    data object RequiresDetail : ConflictInboxBranchTombstone
}

sealed interface ConflictInboxMedia {
    data class Known(val totalCount: Int) : ConflictInboxMedia
    data class RequiresDetail(val knownLocalCount: Int) : ConflictInboxMedia
}

internal fun ConflictRoot.presentationTitle(clientUuid: String): String = when (this) {
    is ConflictRoot.Baby -> nickname.ifBlank { clientUuid }
    is ConflictRoot.Record -> rootTypeLabel(type)
    is ConflictRoot.CarePlan -> "${rootTypeLabel(type)}计划"
    is ConflictRoot.CustomItem -> name.ifBlank { clientUuid }
    is ConflictRoot.WakeObservation -> "醒来观察"
}

internal fun ConflictRoot.actorId(): String = when (this) {
    is ConflictRoot.Baby -> createdByMembershipId
    is ConflictRoot.Record -> createdByMembershipId
    is ConflictRoot.CarePlan -> createdByMembershipId
    is ConflictRoot.CustomItem -> createdByMembershipId
    is ConflictRoot.WakeObservation -> observerMembershipId
}

internal fun ConflictRootType.presentationLabel(): String = when (this) {
    ConflictRootType.Baby -> "宝宝资料"
    ConflictRootType.Record -> "护理记录"
    ConflictRootType.CarePlan -> "护理计划"
    ConflictRootType.CustomItem -> "自定义项目"
    ConflictRootType.WakeObservation -> "醒来观察"
}

private fun rootTypeLabel(type: String): String =
    RecordType.fromKey(type)?.let { recordTypeLabel(it.key) } ?: "护理事实"

internal fun localConflictTitle(
    rootType: ConflictRootType,
    rawTitle: String?,
    clientUuid: String,
): String = when (rootType) {
    ConflictRootType.Baby,
    ConflictRootType.CustomItem,
    -> rawTitle?.ifBlank { clientUuid } ?: clientUuid
    ConflictRootType.Record -> rawTitle?.let(::rootTypeLabel) ?: clientUuid
    ConflictRootType.CarePlan -> rawTitle?.let { "${rootTypeLabel(it)}计划" } ?: clientUuid
    ConflictRootType.WakeObservation -> "醒来观察"
}
