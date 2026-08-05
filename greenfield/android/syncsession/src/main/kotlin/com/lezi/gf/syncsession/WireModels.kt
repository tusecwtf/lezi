package com.lezi.gf.syncsession

import com.lezi.gf.kernel.ProductVersion
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire DTOs — separate from domain models. */

@Serializable
data class WireHealth(
    val status: String,
    val version: String,
    val wire_current: String = ProductVersion.WIRE_CURRENT,
)

@Serializable
data class WireReady(
    val ready: Boolean,
    val version: String,
)

@Serializable
data class WireCapability(
    val wire_current: String,
    val capabilities: List<String>,
)

@Serializable
data class WireSetupStatus(
    val state: String, // empty | configured
    val family_id: String? = null,
    val family_name: String? = null,
)

@Serializable
data class WireCreateFamilyRequest(
    val bootstrap_secret: String,
    val family_name: String,
    val display_name: String,
    val device_name: String,
)

@Serializable
data class WireSessionResponse(
    val family_id: String,
    val family_name: String,
    val membership_id: String,
    val role: String,
    val display_name: String,
    val device_id: String,
    val access_token: String,
    val refresh_token: String,
)

@Serializable
data class WireJoinRequest(
    val display_name: String,
    val device_name: String,
)

@Serializable
data class WireJoinPending(
    val request_id: String,
    val status: String = "pending",
)

@Serializable
data class WireApproveRequest(
    val request_id: String,
    val approve: Boolean = true,
)

@Serializable
data class WireRecordBundle(
    val client_uuid: String,
    val baby_client_uuid: String,
    val type_key: String,
    val timestamp_ms: Long,
    val note: String = "",
    val payload_json: String = "{}",
    val photos: List<WirePhoto> = emptyList(),
    val created_by_membership_id: String? = null,
    val deleted_at_ms: Long? = null,
    val updated_at_ms: Long = 0,
    val linked_plan_uuid: String? = null,
    val is_non_adopted_fulfill: Boolean = false,
)

@Serializable
data class WirePhoto(
    val media_uuid: String,
    val sha256: String = "",
    val byte_size: Long = 0,
    val content_base64: String? = null,
)

@Serializable
data class WirePlanBundle(
    val client_uuid: String,
    val baby_client_uuid: String,
    val type_key: String,
    val scheduled_at_ms: Long,
    val note: String = "",
    val payload_json: String = "{}",
    val photos: List<WirePhoto> = emptyList(),
    val status: String = "PENDING",
    val linked_record_uuid: String? = null,
    val confirmed_at_ms: Long? = null,
    val is_next_feed_marker: Boolean = false,
    val created_by_membership_id: String? = null,
    val deleted_at_ms: Long? = null,
    val updated_at_ms: Long = 0,
)

@Serializable
data class WireBaby(
    val client_uuid: String,
    val nickname: String,
    val sex: String = "UNKNOWN",
    val birthday_epoch_day: Long = 0,
    val deleted_at_ms: Long? = null,
    val updated_at_ms: Long = 0,
)

@Serializable
data class WireCustomDef(
    val client_uuid: String,
    val title: String,
    val icon_key: String = "custom",
    val deleted_at_ms: Long? = null,
    val updated_at_ms: Long = 0,
)

@Serializable
data class WireReconcileRequest(
    val since_revision: Long = 0,
    val push_records: List<WireRecordBundle> = emptyList(),
    val push_plans: List<WirePlanBundle> = emptyList(),
    val push_babies: List<WireBaby> = emptyList(),
    val push_custom_defs: List<WireCustomDef> = emptyList(),
)

@Serializable
data class WireReconcileResponse(
    val revision: Long,
    val records: List<WireRecordBundle> = emptyList(),
    val plans: List<WirePlanBundle> = emptyList(),
    val babies: List<WireBaby> = emptyList(),
    val custom_defs: List<WireCustomDef> = emptyList(),
    val membership_names: Map<String, String> = emptyMap(),
)

@Serializable
data class WireAppUpdate(
    val version_code: Int,
    val version_name: String,
    val release_notes: String = "",
    val min_supported_version_code: Int = 1,
    val force: Boolean = false,
    val apk_sha256: String = "",
)

@Serializable
data class WireError(
    val code: String,
    val message: String,
)

/** Client capabilities required for this generation. */
object WireCaps {
    const val CURRENT: String = ProductVersion.WIRE_CURRENT
    val REQUIRED: Set<String> = setOf(
        "family.identity.v1",
        "sync.reconcile.v1",
        "sync.atomic_media.v1",
        "app.update.v1",
    )
}

enum class SyncShallowStatus {
    SYNCED,
    PENDING,
    TEMPORARILY_UNAVAILABLE,
    NOT_JOINED,
}

fun SyncShallowStatus.toChinese(pendingCount: Int = 0): String = when (this) {
    SyncShallowStatus.SYNCED -> "已同步"
    SyncShallowStatus.PENDING -> "待同步 $pendingCount 项"
    SyncShallowStatus.TEMPORARILY_UNAVAILABLE -> "暂时无法同步"
    SyncShallowStatus.NOT_JOINED -> "未连接家庭"
}
