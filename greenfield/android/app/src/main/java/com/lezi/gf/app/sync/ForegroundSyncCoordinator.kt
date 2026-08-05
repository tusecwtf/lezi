package com.lezi.gf.app.sync

import com.lezi.gf.app.media.PhotoWireCodec
import com.lezi.gf.care.CarePlan
import com.lezi.gf.care.CareRecord
import com.lezi.gf.care.CareService
import com.lezi.gf.care.CustomItemDef
import com.lezi.gf.care.EntityMerge
import com.lezi.gf.care.PhotoRef
import com.lezi.gf.care.PlanStatus
import com.lezi.gf.family.Baby
import com.lezi.gf.family.BabySex
import com.lezi.gf.family.FamilyService
import com.lezi.gf.kernel.GfResult
import com.lezi.gf.syncsession.SyncSessionService
import com.lezi.gf.syncsession.WireBaby
import com.lezi.gf.syncsession.WireCustomDef
import com.lezi.gf.syncsession.WirePhoto
import com.lezi.gf.syncsession.WirePlanBundle
import com.lezi.gf.syncsession.WireRecordBundle
import com.lezi.gf.syncsession.WireReconcileRequest
import com.lezi.gf.syncsession.WireReconcileResponse

/**
 * Composition-root foreground sync: push local atomic packages, then **apply**
 * remote packages into CareStore/Family so UI sees cross-device outcomes.
 *
 * Merge rule: last-writer-wins by [CareRecord.updatedAtMs] / plan/def equivalent
 * ([EntityMerge]). Incomplete atomic media packages are dropped (record+photos together).
 * Custom **defs** sync (ticket 25); **layout / dock / theme** stay local.
 */
class ForegroundSyncCoordinator(
    private val care: CareService,
    private val family: FamilyService,
    private val sync: SyncSessionService,
) {
    fun run(): GfResult<WireReconcileResponse> {
        if (family.account().joinState != com.lezi.gf.family.JoinState.JOINED) {
            return GfResult.Err(com.lezi.gf.kernel.GfError.Forbidden("未加入家庭"))
        }
        // Capture layout so apply cannot accidentally clobber device-local dock
        val layoutBefore = care.store().layout
        val request = WireReconcileRequest(
            push_records = care.store().allRecords().map { it.toWire() },
            push_plans = care.store().allPlans().map { it.toWire() },
            push_babies = if (family.isOwner()) {
                family.authorityBabies().map { it.toWire() }
            } else {
                emptyList()
            },
            push_custom_defs = care.store().allCustoms().map { it.toWire() },
        )
        return when (val res = sync.reconcile(request)) {
            is GfResult.Ok -> {
                applyRemote(res.value)
                // Layout is never on the wire — restore explicit device snapshot
                care.store().layout = layoutBefore
                GfResult.Ok(res.value)
            }
            is GfResult.Err -> res
        }
    }

    /** Apply server packages into local stores — only complete atomic media. */
    fun applyRemote(response: WireReconcileResponse) {
        family.setMembershipNames(response.membership_names)

        val records = response.records.mapNotNull { wr ->
            if (!isAtomicComplete(wr.photos)) return@mapNotNull null
            wr.toDomain()
        }
        val plans = response.plans.mapNotNull { wp ->
            if (!isAtomicComplete(wp.photos)) return@mapNotNull null
            wp.toDomain()
        }
        // LWW by updated_at_ms — not blind remote overwrite
        val mergedRecords = EntityMerge.mergeRecords(care.store().allRecords(), records)
        val mergedPlans = EntityMerge.mergePlans(care.store().allPlans(), plans)
        val remoteCustoms = response.custom_defs.map { it.toDomain() }
        val mergedCustoms = EntityMerge.mergeCustoms(care.store().allCustoms(), remoteCustoms)

        care.store().replaceAll(
            recordsIn = mergedRecords,
            plansIn = mergedPlans,
            customsIn = mergedCustoms,
        )

        val remoteBabies = response.babies.map { it.toDomain() }
        if (remoteBabies.isNotEmpty()) {
            family.applyAuthorityBabies(remoteBabies)
        }
    }

    companion object {
        fun isAtomicComplete(photos: List<WirePhoto>): Boolean {
            // Empty photos OK. Any photo with declared size must carry content or sha.
            return photos.none { p ->
                p.byte_size > 0 &&
                    (p.content_base64.isNullOrBlank()) &&
                    p.sha256.isBlank()
            }
        }
    }
}

private fun CareRecord.toWire(): WireRecordBundle = WireRecordBundle(
    client_uuid = clientUuid,
    baby_client_uuid = babyClientUuid,
    type_key = typeKey,
    timestamp_ms = timestampMs,
    note = note,
    payload_json = payloadJson,
    photos = photos.map { PhotoWireCodec.toWire(it) },
    created_by_membership_id = createdByMembershipId,
    deleted_at_ms = deletedAtMs,
    updated_at_ms = updatedAtMs,
    linked_plan_uuid = linkedPlanUuid,
    is_non_adopted_fulfill = isNonAdoptedFulfill,
)

private fun CarePlan.toWire(): WirePlanBundle = WirePlanBundle(
    client_uuid = clientUuid,
    baby_client_uuid = babyClientUuid,
    type_key = typeKey,
    scheduled_at_ms = scheduledAtMs,
    note = note,
    payload_json = payloadJson,
    photos = photos.map { PhotoWireCodec.toWire(it) },
    status = status.name,
    linked_record_uuid = linkedRecordUuid,
    confirmed_at_ms = confirmedAtMs,
    is_next_feed_marker = isNextFeedMarker,
    created_by_membership_id = createdByMembershipId,
    deleted_at_ms = deletedAtMs,
    updated_at_ms = updatedAtMs,
)

private fun WireRecordBundle.toDomain(): CareRecord = CareRecord(
    clientUuid = client_uuid,
    babyClientUuid = baby_client_uuid,
    typeKey = type_key,
    timestampMs = timestamp_ms,
    note = note,
    payloadJson = payload_json,
    photos = photos.map { p ->
        PhotoRef(
            mediaUuid = p.media_uuid,
            localPath = p.content_base64?.let { "b64:$it" } ?: "remote/${p.media_uuid}",
            byteSize = p.byte_size,
            isDraftOwned = false,
        )
    },
    createdByMembershipId = created_by_membership_id,
    deletedAtMs = deleted_at_ms,
    updatedAtMs = updated_at_ms,
    linkedPlanUuid = linked_plan_uuid,
    isNonAdoptedFulfill = is_non_adopted_fulfill,
)

private fun WirePlanBundle.toDomain(): CarePlan = CarePlan(
    clientUuid = client_uuid,
    babyClientUuid = baby_client_uuid,
    typeKey = type_key,
    scheduledAtMs = scheduled_at_ms,
    note = note,
    payloadJson = payload_json,
    photos = photos.map { p ->
        PhotoRef(
            mediaUuid = p.media_uuid,
            localPath = p.content_base64?.let { "b64:$it" } ?: "remote/${p.media_uuid}",
            byteSize = p.byte_size,
            isDraftOwned = false,
        )
    },
    status = runCatching { PlanStatus.valueOf(status) }.getOrDefault(PlanStatus.PENDING),
    linkedRecordUuid = linked_record_uuid,
    confirmedAtMs = confirmed_at_ms,
    isNextFeedMarker = is_next_feed_marker,
    createdByMembershipId = created_by_membership_id,
    deletedAtMs = deleted_at_ms,
    updatedAtMs = updated_at_ms,
)

private fun Baby.toWire(): WireBaby = WireBaby(
    client_uuid = clientUuid,
    nickname = nickname,
    sex = sex.name,
    birthday_epoch_day = birthdayEpochDay,
    deleted_at_ms = deletedAtMs,
    updated_at_ms = updatedAtMs,
)

private fun WireBaby.toDomain(): Baby = Baby(
    clientUuid = client_uuid,
    nickname = nickname,
    sex = runCatching { BabySex.valueOf(sex) }.getOrDefault(BabySex.UNKNOWN),
    birthdayEpochDay = birthday_epoch_day,
    familyAuthority = true,
    deletedAtMs = deleted_at_ms,
    updatedAtMs = updated_at_ms,
)

private fun CustomItemDef.toWire(): WireCustomDef = WireCustomDef(
    client_uuid = clientUuid,
    title = title,
    icon_key = iconKey,
    deleted_at_ms = deletedAtMs,
    updated_at_ms = updatedAtMs,
)

private fun WireCustomDef.toDomain(): CustomItemDef = CustomItemDef(
    clientUuid = client_uuid,
    title = title,
    iconKey = icon_key,
    deletedAtMs = deleted_at_ms,
    updatedAtMs = updated_at_ms,
)
