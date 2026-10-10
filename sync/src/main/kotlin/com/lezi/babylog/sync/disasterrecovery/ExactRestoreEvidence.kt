package com.lezi.babylog.sync.disasterrecovery

import com.lezi.babylog.core.database.BabyEntity
import com.lezi.babylog.core.database.CarePlanEntity
import com.lezi.babylog.core.database.CustomItemEntity
import com.lezi.babylog.core.database.FulfillmentCandidateEntity
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.database.causal.WakeObservationEntity
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * V2 encodes the complete local row, including transport state and device-only fields. Each field
 * has a name and type; nullable values carry presence, and strings carry UTF-16 code-unit lengths.
 * UTF-16 preserves every Kotlin String, including unpaired surrogates that UTF-8 would replace.
 * This is local equality evidence only, never a server content digest or wire projection.
 */
internal fun exactRestoreEvidence(type: String, row: Any, attachments: List<MediaAssetEntity>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val sink = object : OutputStream() {
        override fun write(value: Int) = digest.update(value.toByte())
        override fun write(bytes: ByteArray, offset: Int, length: Int) = digest.update(bytes, offset, length)
    }
    DataOutputStream(BufferedOutputStream(sink, 1024)).use { output ->
        output.writeExactText("lezi.restore.exact-local-evidence.v2")
        val fields = BinaryRestoreEvidenceFields(output)
        fields.row(type, row)
        output.writeInt(attachments.size)
        // Captured owner buckets already have stable UUID order, including duplicate tombstones.
        attachments.forEach { fields.row("media", it) }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** Typed sink also lets the schema-completeness test detect an omitted or mistyped local field. */
internal interface ExactRestoreEvidenceFields {
    fun text(name: String, value: String?)
    fun int(name: String, value: Int?)
    fun long(name: String, value: Long?)
    fun boolean(name: String, value: Boolean)
}

/** Deliberately explicit: changing a local field requires an evidence-schema review. */
internal fun writeExactRestoreEvidenceFields(row: Any, fields: ExactRestoreEvidenceFields) {
    with(fields) {
        when (row) {
            is BabyEntity -> {
                long("id", row.id)
                long("familyId", row.familyId)
                text("nickname", row.nickname)
                text("sex", row.sex)
                long("birthdayEpochDay", row.birthdayEpochDay)
                int("birthWeightGrams", row.birthWeightGrams)
                int("themeColorArgb", row.themeColorArgb)
                int("sortOrder", row.sortOrder)
                text("clientUuid", row.clientUuid)
                long("updatedAt", row.updatedAt)
                long("deletedAt", row.deletedAt)
                boolean("syncDirty", row.syncDirty)
                text("avatarMediaUuid", row.avatarMediaUuid)
                text("avatarPath", row.avatarPath)
                boolean("familyAuthority", row.familyAuthority)
                text("baseVersion", row.baseVersion)
                text("mutationId", row.mutationId)
                text("openConflictId", row.openConflictId)
                text("localBranchVersionId", row.localBranchVersionId)
            }
            is RecordEntity -> {
                long("id", row.id)
                text("clientUuid", row.clientUuid)
                long("babyId", row.babyId)
                text("type", row.type)
                long("timestamp", row.timestamp)
                long("endTimestamp", row.endTimestamp)
                text("note", row.note)
                text("payloadJson", row.payloadJson)
                int("schemaVersion", row.schemaVersion)
                long("updatedAt", row.updatedAt)
                long("deletedAt", row.deletedAt)
                boolean("syncDirty", row.syncDirty)
                text("createdByMembershipId", row.createdByMembershipId)
                long("familyPublishedUpdatedAt", row.familyPublishedUpdatedAt)
                text("baseVersion", row.baseVersion)
                text("mutationId", row.mutationId)
                text("openConflictId", row.openConflictId)
                text("localBranchVersionId", row.localBranchVersionId)
                text("effectiveWakeObservationClientUuid", row.effectiveWakeObservationClientUuid)
            }
            is CarePlanEntity -> {
                long("id", row.id)
                text("clientUuid", row.clientUuid)
                long("babyId", row.babyId)
                text("type", row.type)
                long("customItemId", row.customItemId)
                long("scheduledAt", row.scheduledAt)
                text("scheduledZoneId", row.scheduledZoneId)
                text("note", row.note)
                text("payloadJson", row.payloadJson)
                int("schemaVersion", row.schemaVersion)
                text("status", row.status)
                text("createdByMembershipId", row.createdByMembershipId)
                text("fulfilledRecordClientUuid", row.fulfilledRecordClientUuid)
                long("fulfilledAt", row.fulfilledAt)
                text("sourceRecordClientUuid", row.sourceRecordClientUuid)
                long("updatedAt", row.updatedAt)
                long("deletedAt", row.deletedAt)
                boolean("syncDirty", row.syncDirty)
                boolean("systemCalendarProjectionEnabled", row.systemCalendarProjectionEnabled)
                text("systemCalendarEventId", row.systemCalendarEventId)
                boolean("systemCalendarReminderReady", row.systemCalendarReminderReady)
                boolean("systemCalendarProjectionPending", row.systemCalendarProjectionPending)
                long("familyPublishedUpdatedAt", row.familyPublishedUpdatedAt)
                text("baseVersion", row.baseVersion)
                text("mutationId", row.mutationId)
                text("openConflictId", row.openConflictId)
                text("localBranchVersionId", row.localBranchVersionId)
            }
            is CustomItemEntity -> {
                long("id", row.id)
                text("clientUuid", row.clientUuid)
                long("familyId", row.familyId)
                text("name", row.name)
                int("iconSlot", row.iconSlot)
                int("sortOrder", row.sortOrder)
                long("updatedAt", row.updatedAt)
                long("deletedAt", row.deletedAt)
                text("createdByMembershipId", row.createdByMembershipId)
                boolean("syncDirty", row.syncDirty)
                text("baseVersion", row.baseVersion)
                text("mutationId", row.mutationId)
                text("openConflictId", row.openConflictId)
                text("localBranchVersionId", row.localBranchVersionId)
            }
            is FulfillmentCandidateEntity -> {
                long("id", row.id)
                text("clientUuid", row.clientUuid)
                text("carePlanClientUuid", row.carePlanClientUuid)
                text("recordClientUuid", row.recordClientUuid)
                long("actualTimestamp", row.actualTimestamp)
                long("confirmedAt", row.confirmedAt)
                text("submitterMembershipId", row.submitterMembershipId)
                text("submitterRole", row.submitterRole)
                text("adoptionStatus", row.adoptionStatus)
                text("convertedRecordClientUuid", row.convertedRecordClientUuid)
                long("updatedAt", row.updatedAt)
                long("deletedAt", row.deletedAt)
                boolean("syncDirty", row.syncDirty)
            }
            is WakeObservationEntity -> {
                long("id", row.id)
                text("clientUuid", row.clientUuid)
                text("sleepRecordClientUuid", row.sleepRecordClientUuid)
                long("wakeTimestamp", row.wakeTimestamp)
                text("observerMembershipId", row.observerMembershipId)
                text("note", row.note)
                boolean("withdrawn", row.withdrawn)
                long("updatedAt", row.updatedAt)
                long("deletedAt", row.deletedAt)
                boolean("syncDirty", row.syncDirty)
                long("familyPublishedUpdatedAt", row.familyPublishedUpdatedAt)
                text("baseVersion", row.baseVersion)
                text("mutationId", row.mutationId)
                text("openConflictId", row.openConflictId)
                text("localBranchVersionId", row.localBranchVersionId)
            }
            is MediaAssetEntity -> {
                long("id", row.id)
                long("recordId", row.recordId)
                long("carePlanId", row.carePlanId)
                long("wakeObservationId", row.wakeObservationId)
                text("clientUuid", row.clientUuid)
                text("kind", row.kind)
                long("babyId", row.babyId)
                text("localUri", row.localUri)
                text("remoteUri", row.remoteUri)
                text("mime", row.mime)
                int("width", row.width)
                int("height", row.height)
                long("byteSize", row.byteSize)
                long("createdAt", row.createdAt)
                long("updatedAt", row.updatedAt)
                long("deletedAt", row.deletedAt)
                boolean("syncDirty", row.syncDirty)
                text("baseVersion", row.baseVersion)
                text("mutationId", row.mutationId)
                text("openConflictId", row.openConflictId)
                text("localBranchVersionId", row.localBranchVersionId)
                text("sha256", row.sha256)
            }
            else -> error("unsupported restore evidence row")
        }
    }
}

private class BinaryRestoreEvidenceFields(private val output: DataOutputStream) : ExactRestoreEvidenceFields {
    fun row(type: String, row: Any) {
        output.writeExactText(type)
        writeExactRestoreEvidenceFields(row, this)
        output.writeInt(-1) // End of named fields; a field name always has a non-negative length.
    }

    override fun text(name: String, value: String?) {
        field(name, 's')
        output.writeExactText(value)
    }

    override fun int(name: String, value: Int?) {
        field(name, 'i')
        output.writeBoolean(value != null)
        if (value != null) output.writeInt(value)
    }

    override fun long(name: String, value: Long?) {
        field(name, 'l')
        output.writeBoolean(value != null)
        if (value != null) output.writeLong(value)
    }

    override fun boolean(name: String, value: Boolean) {
        field(name, 'b')
        output.writeBoolean(value)
    }

    private fun field(name: String, type: Char) {
        output.writeExactText(name)
        output.writeByte(type.code)
    }
}

private fun DataOutputStream.writeExactText(value: String?) {
    if (value == null) {
        writeInt(-1)
    } else {
        writeInt(value.length)
        value.forEach { writeChar(it.code) }
    }
}
