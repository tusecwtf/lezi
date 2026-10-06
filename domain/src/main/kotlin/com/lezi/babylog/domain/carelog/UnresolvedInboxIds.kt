package com.lezi.babylog.domain.carelog

import com.lezi.babylog.sync.isDismissibleUnresolvedEntity
import com.lezi.babylog.sync.session.formatEntityTypeChinese
import com.lezi.babylog.sync.session.formatShortIdentity
import com.lezi.babylog.sync.conflict.ConflictRootType

const val LOCAL_DISMISS_CONSEQUENCE =
    "只从这台手机去掉，不通知家里，其它手机不受影响。若家人之后修改了这条，它也不会再回到这台手机。"

data class UnresolvedInboxRef(
    val kind: ConflictInboxKind,
    val entityType: String,
    val clientUuid: String,
    val inboxId: String,
    val dismissible: Boolean,
)

object UnresolvedInboxIds {
    fun encode(kind: ConflictInboxKind, entityType: String, clientUuid: String): String {
        val token = when (kind) {
            ConflictInboxKind.LocalExtra -> "extra"
            ConflictInboxKind.Rejected -> "rejected"
            ConflictInboxKind.PullHole -> "hole"
            ConflictInboxKind.Branched -> error("branched conflicts use the server conflict id")
        }
        return "local:$token:$entityType:$clientUuid"
    }

    fun parse(inboxId: String): UnresolvedInboxRef? {
        val parts = inboxId.split(":", limit = 4)
        if (parts.size != 4 || parts[0] != "local") return null
        val kind = when (parts[1]) {
            "extra" -> ConflictInboxKind.LocalExtra
            "rejected" -> ConflictInboxKind.Rejected
            "hole" -> ConflictInboxKind.PullHole
            else -> return null
        }
        val entityType = parts[2]
        val clientUuid = parts[3]
        if (entityType.isBlank() || clientUuid.isBlank()) return null
        return UnresolvedInboxRef(
            kind = kind,
            entityType = entityType,
            clientUuid = clientUuid,
            inboxId = inboxId,
            dismissible = isDismissibleUnresolvedEntity(entityType),
        )
    }

    fun isLocal(inboxId: String): Boolean = inboxId.startsWith("local:")
}

internal fun unresolvedRootType(entityType: String): ConflictRootType? =
    runCatching { ConflictRootType.fromWire(entityType) }.getOrNull()

internal fun unresolvedTitle(entityType: String, clientUuid: String): String =
    "${formatEntityTypeChinese(entityType)} ${formatShortIdentity(clientUuid)}"
