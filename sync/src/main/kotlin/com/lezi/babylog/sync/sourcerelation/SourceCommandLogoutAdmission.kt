package com.lezi.babylog.sync.sourcerelation

import com.lezi.babylog.core.database.DatabaseTransactionRunner
import com.lezi.babylog.core.database.LocalDataClearScope
import com.lezi.babylog.core.database.causal.CausalTransportJournalEntity
import com.lezi.babylog.core.database.causal.ConflictSnapshotCacheDao
import com.lezi.babylog.core.database.causal.SourceRelationDao
import com.lezi.babylog.core.database.causal.SourceRelationDeclarationEntity
import com.lezi.babylog.sync.SourceCommandLogoutConsent
import com.lezi.babylog.sync.SourceCommandLogoutConsentChangedException
import com.lezi.babylog.sync.SourceCommandLogoutState
import com.lezi.babylog.sync.session.SyncSession
import com.lezi.babylog.sync.session.TrustedEndpointProfile
import java.io.DataOutputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*

/** Generic disclosure only; no original request, record identity, or credentials survive in it. */
data class SourceCommandClearNotice(val id: String, val unknownCount: Int, val confirmedCount: Int)

/** Exact user admission and source-only retirement. Restore snapshot ownership is outside this owner. */
internal class SourceCommandLogoutAdmission(
    private val cache: ConflictSnapshotCacheDao,
    private val relations: SourceRelationDao,
    private val transactions: DatabaseTransactionRunner,
    private val currentSession: suspend () -> SyncSession,
    private val trustedEndpoint: suspend () -> TrustedEndpointProfile?,
    private val hasVerifiedTerminalRemoval: suspend () -> Boolean,
) {
    private val state = MutableStateFlow<SourceCommandClearNotice?>(null)
    val notice: StateFlow<SourceCommandClearNotice?> = state

    suspend fun prepare(): SourceCommandLogoutConsent? {
        val identity = identity(currentSession())
        return transactions.run {
            val snapshot = snapshot(identity)
            if (snapshot.empty) return@run null
            val command = snapshot.commandObject()
            val commandId = command?.get("mutation_id")?.jsonPrimitive?.contentOrNull
            val ids = (listOfNotNull(commandId) + snapshot.declarations.map { it.mutationId }).distinct().sorted()
            val confirmed = command?.get("phase")?.jsonPrimitive?.contentOrNull == "confirmed_refresh_required" &&
                snapshot.declarations.all { it.mutationId == commandId }
            SourceCommandLogoutConsent(ids.ifEmpty { listOf("无法读取的历史操作") },
                command?.get("authority")?.let { runCatching { it.jsonArray[4].jsonPrimitive.content }.getOrNull() },
                if (confirmed) SourceCommandLogoutState.ConfirmedRefreshRequired else SourceCommandLogoutState.Unknown,
                snapshot)
        }
    }

    /** Caller holds the session barrier before dispatching logout. */
    suspend fun admit(consent: SourceCommandLogoutConsent?) {
        val identity = identity(currentSession())
        transactions.run {
            val current = snapshot(identity)
            if (!(current.empty && consent == null) && consent?.exactEvidence != current) throw SourceCommandLogoutConsentChangedException()
            cache.putTransportJournal(APPROVAL_KEY, buildJsonObject {
                put("format", 1); put("kind", "user_logout"); put("phase", "admitted"); put("evidence", current.digest())
            }.toString(), 0)
        }
    }

    suspend fun logoutConfirmed() {
        transactions.run {
            val row = requireNotNull(cache.getTransportJournal(APPROVAL_KEY))
            val value = Json.parseToJsonElement(row.payloadJson).jsonObject
            cache.putTransportJournal(APPROVAL_KEY,
                JsonObject(value + ("phase" to JsonPrimitive("confirmed"))).toString(), row.contentEpoch)
        }
    }

    /** Must be called within the same Room transaction as clearRoom; no network or file work. */
    suspend fun aroundClear(scope: LocalDataClearScope, session: SyncSession, clearRoom: suspend () -> Unit) {
        val before = snapshot(identity(session))
        val approval = cache.getTransportJournal(APPROVAL_KEY)
        val previousNotice = cache.getTransportJournal(NOTICE_KEY)
        val approved = approval?.payloadJson?.let { runCatching {
            val value = Json.parseToJsonElement(it).jsonObject
            value["format"]?.jsonPrimitive?.int == 1 && value["kind"]?.jsonPrimitive?.content == "user_logout" &&
                value["phase"]?.jsonPrimitive?.content == "confirmed" &&
                value["evidence"]?.jsonPrimitive?.content == before.digest()
        }.getOrDefault(false) } == true
        val confirmedLogout = approval?.payloadJson?.let { runCatching {
            Json.parseToJsonElement(it).jsonObject["phase"]?.jsonPrimitive?.content == "confirmed"
        }.getOrDefault(false) } == true
        val terminal = !confirmedLogout && scope == LocalDataClearScope.AllLocalData && hasVerifiedTerminalRemoval() &&
            before.belongsToCurrentAuthority()
        if (scope == LocalDataClearScope.AllLocalData && !before.empty && !approved && !terminal)
            throw SourceCommandLogoutConsentChangedException()
        clearRoom()
        if (scope == LocalDataClearScope.RecordsOnly) {
            // RecordsOnly is not consent to abandon an uncertain server operation.
            before.command?.let { cache.putTransportJournal(it.journalKey, it.payloadJson, it.contentEpoch) }
            before.declarations.forEach { relations.upsertDeclaration(it) }
            approval?.let { cache.putTransportJournal(it.journalKey, it.payloadJson, it.contentEpoch) }
            previousNotice?.let { cache.putTransportJournal(it.journalKey, it.payloadJson, it.contentEpoch) }
            return
        }
        cache.deleteTransportJournal(SOURCE_RELATION_COMMAND_JOURNAL_KEY)
        cache.deleteTransportJournal(APPROVAL_KEY)
        relations.deleteAllDeclarations()
        if (!before.empty) {
            val command = before.commandObject()
            val commandId = command?.get("mutation_id")?.jsonPrimitive?.contentOrNull
            val confirmed = command?.get("phase")?.jsonPrimitive?.contentOrNull == "confirmed_refresh_required"
            val ids = (listOfNotNull(commandId) + before.declarations.map { it.mutationId }).toSet()
            val count = ids.size.coerceAtLeast(1)
            val confirmedCount = if (confirmed) 1 else 0
            val notice = SourceCommandClearNotice(UUID.randomUUID().toString(), count - confirmedCount, confirmedCount)
            cache.putTransportJournal(NOTICE_KEY, encode(notice), 0)
        } else previousNotice?.let { cache.putTransportJournal(it.journalKey, it.payloadJson, it.contentEpoch) }
    }

    suspend fun restoreNotice() { state.value = cache.getTransportJournal(NOTICE_KEY)?.payloadJson?.let(::decode) }
    suspend fun acknowledge(expected: SourceCommandClearNotice) {
        transactions.run {
            val current = cache.getTransportJournal(NOTICE_KEY)?.payloadJson?.let(::decode)
            if (current == expected) cache.deleteTransportJournal(NOTICE_KEY)
        }
        restoreNotice()
    }

    private suspend fun identity(session: SyncSession): List<String> {
        val endpoint = trustedEndpoint()
        return listOf(session.familyId, session.membershipId, session.deviceId, session.pullGeneration,
            endpoint?.origin.orEmpty(), endpoint?.trustMode?.name.orEmpty(), endpoint?.spkiSha256.orEmpty(), session.role.name)
    }

    private suspend fun snapshot(identity: List<String>) = Snapshot(identity,
        cache.getTransportJournal(SOURCE_RELATION_COMMAND_JOURNAL_KEY),
        relations.listUnsettledDeclarations().sortedBy { it.mutationId })

    private data class Snapshot(val authority: List<String>, val command: CausalTransportJournalEntity?,
        val declarations: List<SourceRelationDeclarationEntity>) {
        val empty get() = command == null && declarations.isEmpty()
        fun commandObject(): JsonObject? = command?.payloadJson?.let {
            runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull()
        }
        fun belongsToCurrentAuthority(): Boolean {
            if (authority[0].isBlank() || authority[1].isBlank() || authority[2].isBlank()) return false
            if (declarations.any { it.authorMembershipId != authority[1] }) return false
            if (command == null) return true
            return runCatching { commandObject()?.get("authority")?.jsonArray?.map { it.jsonPrimitive.content } == authority }
                .getOrDefault(false)
        }
        fun digest(): String {
            val digest = MessageDigest.getInstance("SHA-256")
            DataOutputStream(DigestOutputStream(object : OutputStream() { override fun write(value: Int) = Unit }, digest)).use { out ->
                fun text(value: String) { out.writeInt(value.length); value.forEach { out.writeChar(it.code) } }
                text("source-logout-consent-v1")
                authority.forEach(::text)
                out.writeBoolean(command != null)
                command?.let { text(it.journalKey); text(it.payloadJson); out.writeLong(it.contentEpoch) }
                out.writeInt(declarations.size)
                declarations.forEach {
                    text(it.mutationId); text(it.recordClientUuid); text(it.equivalentToClientUuid)
                    text(it.expectedRecordVersion); text(it.expectedOtherVersion); text(it.authorMembershipId)
                    text(it.status); out.writeLong(it.createdAt)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    private fun encode(notice: SourceCommandClearNotice) = buildJsonObject {
        put("format", 1); put("id", notice.id); put("unknown", notice.unknownCount); put("confirmed", notice.confirmedCount)
    }.toString()
    private fun decode(raw: String): SourceCommandClearNotice {
        val value = Json.parseToJsonElement(raw).jsonObject
        require(value["format"]?.jsonPrimitive?.int == 1)
        return SourceCommandClearNotice(value.getValue("id").jsonPrimitive.content,
            value.getValue("unknown").jsonPrimitive.int, value.getValue("confirmed").jsonPrimitive.int)
            .also { require(it.unknownCount >= 0 && it.confirmedCount in 0..1) }
    }
    companion object {
        const val APPROVAL_KEY = "source-command-logout-consent-v1"
        const val NOTICE_KEY = "source-command-terminal-notice-v1"
    }
}
