package com.lezi.babylog.sync.sourcerelation

import com.lezi.babylog.core.database.DatabaseTransactionRunner
import com.lezi.babylog.core.database.causal.ConflictSnapshotCacheDao
import com.lezi.babylog.core.database.causal.CurrentSourceRelationProjection
import com.lezi.babylog.core.database.causal.SourceRelationDao
import com.lezi.babylog.core.database.causal.SourceRelationDeclarationEntity
import com.lezi.babylog.core.database.causal.SourceRelationDeclarationStatus
import com.lezi.babylog.core.database.causal.SourceRelationEntity
import com.lezi.babylog.core.database.causal.SourceRelationMemberEntity
import com.lezi.babylog.core.database.causal.SourceRelationReason
import com.lezi.babylog.core.database.causal.SourceRelationRole
import com.lezi.babylog.sync.backend.CurrentSourceRelationsRequest
import com.lezi.babylog.sync.backend.CurrentSourceRelationsSnapshot
import com.lezi.babylog.sync.backend.MAX_CURRENT_SOURCE_RELATION_RECORDS
import com.lezi.babylog.sync.backend.SourceRelationDeclareRequest
import com.lezi.babylog.sync.backend.SyncHttpException
import com.lezi.babylog.sync.backend.SourceRelationResolveGroupRequest
import com.lezi.babylog.sync.backend.SourceRelationResult
import com.lezi.babylog.sync.backend.SyncBackend
import com.lezi.babylog.sync.session.SyncSession
import com.lezi.babylog.sync.session.TrustedEndpointProfile
import com.lezi.babylog.sync.session.matchesOrigin
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

const val SOURCE_RELATION_COMMAND_JOURNAL_KEY = "source-relation-command-v1"

/**
 * Owns dispatch evidence and canonical settlement under the caller's replica sync mutex.
 * The journal is one bounded, nonsecret command, never an automatically replayed outbox.
 * Repeating the same choice and CAS versions reuses its original operation ID; every other
 * command, identity or trust change leaves the unknown outcome fenced for the original server.
 */
class SourceRelationCommandOwner(
    private val sourceRelationDao: SourceRelationDao,
    private val journalDao: ConflictSnapshotCacheDao,
    private val transactionRunner: DatabaseTransactionRunner,
    private val backend: SyncBackend,
    private val currentSession: suspend () -> SyncSession,
    private val currentEndpoint: suspend () -> TrustedEndpointProfile?,
    private val nowMillis: () -> Long,
) {
    suspend fun declare(request: SourceRelationDeclareRequest): SourceRelationResult =
        execute(Command.declare(request))

    suspend fun resolveGroup(request: SourceRelationResolveGroupRequest): SourceRelationResult =
        execute(Command.resolve(request))

    private suspend fun execute(requested: Command): SourceRelationResult {
        requested.validate()
        val session = currentSession()
        val authority = authority(session)
        var captured = transactionRunner.run {
            val existing = journalDao.getTransportJournal(SOURCE_RELATION_COMMAND_JOURNAL_KEY)
            if (existing != null) {
                val journal = decode(existing.payloadJson)
                checkPending(journal.encode() == existing.payloadJson)
                checkPending(journal.authority == authority && journal.command.sameChoice(requested))
                journal.acceptedReceipt?.let { validateAccepted(journal.command, it) }
                journal
            } else {
                checkPending(sourceRelationDao.listUnsettledDeclarations().isEmpty())
                val journal = Journal(authority, requested, session.pullCursor,
                    canonicalEvidence(requested.members), nowMillis())
                journal.declaration(SourceRelationDeclarationStatus.PENDING)?.let {
                    sourceRelationDao.upsertDeclaration(it)
                }
                persist(journal)
                journal
            }
        }
        var remoteConfirmed = captured.acceptedReceipt != null
        try {
            currentCoroutineContext().ensureActive()
            checkPending(authority(currentSession()) == captured.authority)
            if (!remoteConfirmed) {
                // No network request, including the read-only refresh, runs in a Room transaction.
                val result = when (captured.command.kind) {
                    SourceRelationReason.AUTHOR_DECLARE -> backend.declareSourceRelation(session, captured.command.asDeclare())
                    else -> backend.resolveSourceRelationGroup(session, captured.command.asResolve())
                }
                currentCoroutineContext().ensureActive()
                checkPending(authority(currentSession()) == captured.authority)
                if (result.status != "accepted") {
                    transactionRunner.run {
                        requireCurrentJournal(captured)
                        val status = when (result.status) {
                            "cas_mismatch" -> SourceRelationDeclarationStatus.SUPERSEDED
                            "rejected" -> {
                                checkPending(!result.code.isNullOrBlank())
                                SourceRelationDeclarationStatus.REJECTED
                            }
                            else -> throw SourceRelationCommandUnsettledException()
                        }
                        captured.declaration(status)?.let { sourceRelationDao.upsertDeclaration(it) }
                        journalDao.deleteTransportJournal(SOURCE_RELATION_COMMAND_JOURNAL_KEY)
                    }
                    return result
                }
                validateAccepted(captured.command, result)
                remoteConfirmed = true
                val confirmed = captured.copy(acceptedReceipt = result)
                transactionRunner.run {
                    requireCurrentJournal(captured)
                    persist(confirmed)
                }
                captured = confirmed
            }
            return withTimeout(MAX_REFRESH_MILLIS) { refreshCurrentProjection(captured) }
        } catch (timeout: TimeoutCancellationException) {
            if (remoteConfirmed) throw SourceRelationCommandRefreshRequiredException(timeout)
            throw timeout
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (remoteConfirmed) {
                if (error is SourceRelationCommandRefreshRequiredException) throw error
                throw SourceRelationCommandRefreshRequiredException(error)
            }
            if (error is SourceRelationCommandUnsettledException) throw error
            throw SourceRelationCommandUnsettledException(error)
        }
    }

    private suspend fun refreshCurrentProjection(journal: Journal): SourceRelationResult {
        val receipt = requireNotNull(journal.acceptedReceipt)
        var queryIds = localClosure(journal.command.members).sorted()
        requireRefresh(queryIds.size <= MAX_MEMBERS, "本机旧来源组超出单次完整读取范围，请先同步原服务器并检查来源关系")
        var knownScope = queryIds.toSet()
        repeat(MAX_REFRESH_READS) {
            val baselineScope = localClosure(knownScope.toList())
            val addedLocalPeers = baselineScope - knownScope
            if (addedLocalPeers.isNotEmpty()) {
                queryIds = (queryIds + addedLocalPeers).distinct().sorted()
                requireRefresh(queryIds.size <= MAX_MEMBERS, "本机旧来源组超出单次完整读取范围，请先同步原服务器并检查来源关系")
            }
            val baselineEvidence = canonicalEvidence(baselineScope.sorted())
            val session = currentSession()
            checkPending(authority(session) == journal.authority)
            val request = CurrentSourceRelationsRequest(session.familyId, session.pullGeneration, queryIds)
            val snapshot = backend.readCurrentSourceRelations(session, request)
            snapshot.validateFor(request, session.pullCursor)
            currentCoroutineContext().ensureActive()
            checkPending(authority(currentSession()) == journal.authority)
            val covered = snapshot.records.mapTo(linkedSetOf()) { it.recordClientUuid }
            val needed = localClosure((baselineScope + covered).toList())
            val missing = needed - covered
            if (missing.isNotEmpty()) {
                queryIds = (queryIds + missing).distinct().sorted()
                requireRefresh(queryIds.size <= MAX_MEMBERS, "当前来源组无法在一次完整读取中覆盖本机旧成员，请先同步原服务器并检查来源关系")
                knownScope = needed
                return@repeat
            }
            if (!baselineScope.containsAll(covered)) {
                // The next read starts with local evidence for every newly discovered peer.
                knownScope = needed
                return@repeat
            }
            val settlementSession = currentSession()
            checkPending(authority(settlementSession) == journal.authority)
            snapshot.validateFor(request, settlementSession.pullCursor)
            transactionRunner.run {
                requireCurrentJournal(journal)
                requireRefresh(canonicalEvidence(baselineScope.sorted()) == baselineEvidence,
                    "读取期间本机来源关系发生变化，请重试刷新")
                sourceRelationDao.replaceCurrentProjection(
                    coveredRecordClientUuids = covered.sorted(),
                    groups = snapshot.sourceRelations.map { group ->
                        CurrentSourceRelationProjection(
                            relationId = group.relationId,
                            displayClientUuid = group.displayClientUuid,
                            sourceClientUuids = group.sourceClientUuids,
                            autoAligned = group.autoAligned,
                            acceptedProvenance = if (group.relationId == receipt.relationId &&
                                group.displayClientUuid == receipt.displayClientUuid &&
                                group.sourceClientUuids.toSet() == receipt.sourceClientUuids.toSet()) {
                                SourceRelationEntity(group.relationId, group.displayClientUuid, true,
                                    journal.command.kind, journal.command.mutationId,
                                    journal.authority.membershipId, journal.createdAt)
                            } else null,
                        )
                    },
                    observedAt = nowMillis(),
                )
                journal.declaration(SourceRelationDeclarationStatus.CONSUMED)?.let {
                    sourceRelationDao.upsertDeclaration(it)
                }
                journalDao.deleteTransportJournal(SOURCE_RELATION_COMMAND_JOURNAL_KEY)
            }
            return receipt.copy(currentProjection = snapshot)
        }
        throw SourceRelationCommandRefreshRequiredException(
            IllegalStateException("来源组范围持续变化，请待原服务器同步稳定后重试"),
        )
    }

    private suspend fun persist(journal: Journal) = journalDao.putTransportJournal(
        SOURCE_RELATION_COMMAND_JOURNAL_KEY, journal.encode(), journal.createdAt,
    )

    private suspend fun requireCurrentJournal(journal: Journal) = checkPending(
        journalDao.getTransportJournal(SOURCE_RELATION_COMMAND_JOURNAL_KEY)?.payloadJson == journal.encode(),
    )

    /** Follow complete old local groups, with explicit corruption and resource bounds. */
    private suspend fun localClosure(seeds: List<String>): Set<String> {
        val covered = seeds.toMutableSet()
        requireRefresh(covered.size <= MAX_CURRENT_SOURCE_RELATION_RECORDS, "本机来源关系范围过大，请检查来源关系")
        val visited = mutableSetOf<String>()
        val relations = mutableSetOf<String>()
        while (visited.size < covered.size) {
            val next = (covered - visited).take(MAX_MEMBERS)
            val memberships = sourceRelationDao.listMembersForRecords(next)
            requireRefresh(memberships.groupBy { it.recordClientUuid }.values.all { it.size == 1 },
                "本机来源关系成员归属不完整，请先同步原服务器")
            visited += next
            for (relationId in memberships.map { it.relationId }.distinct()) {
                if (!relations.add(relationId)) continue
                val members = sourceRelationDao.listMembers(relationId)
                requireRefresh(members.size <= MAX_MEMBERS, "本机来源组超过 64 条记录，请检查来源关系")
                covered += members.map { it.recordClientUuid }
                requireRefresh(covered.size <= MAX_CURRENT_SOURCE_RELATION_RECORDS, "本机来源关系范围过大，请检查来源关系")
            }
        }
        return covered
    }

    private suspend fun authority(session: SyncSession): Authority {
        val endpoint = currentEndpoint()
        checkPending(session.isJoined && session.pullGeneration.isNotBlank() &&
            session.familyId.isNotBlank() && session.membershipId.isNotBlank() &&
            session.deviceId.isNotBlank() && endpoint != null && endpoint.matchesOrigin(session.baseUrl))
        return Authority(
            session.familyId, session.membershipId, session.deviceId, session.pullGeneration,
            requireNotNull(endpoint).origin, endpoint.trustMode.name, endpoint.spkiSha256.orEmpty(),
            session.role.name,
        )
    }

    /** Hash only the bounded affected component, preserving complete headers and member roles. */
    private suspend fun canonicalEvidence(requestMembers: List<String>): String {
        val relationIds = linkedSetOf<String>()
        for (chunk in requestMembers.chunked(MAX_MEMBERS)) {
            val memberships = sourceRelationDao.listMembersForRecords(chunk)
            checkPending(memberships.groupBy { it.recordClientUuid }.values.all { it.size == 1 })
            relationIds += memberships.map { it.relationId }
        }
        val touched = requestMembers.toMutableSet()
        val evidence = buildList {
            for (relationId in relationIds.sorted()) {
                val row = sourceRelationDao.get(relationId)
                val members = sourceRelationDao.listMembers(relationId)
                    .sortedBy { it.recordClientUuid }
                touched += members.map { it.recordClientUuid }
                checkPending(touched.size <= MAX_CURRENT_SOURCE_RELATION_RECORDS && members.size <= MAX_MEMBERS)
                add(buildJsonObject {
                    put("relation", row?.let { header ->
                        buildJsonObject {
                            put("relation_id", header.relationId)
                            put("display_client_uuid", header.displayClientUuid)
                            put("media_retained", header.mediaRetained)
                            put("reason", header.reason)
                            put("mutation_id", header.mutationId)
                            put("created_by_membership_id", header.createdByMembershipId)
                            put("created_at", header.createdAt)
                        }
                    } ?: JsonNull)
                    put("members", JsonArray(members.map { member ->
                        buildJsonObject {
                            put("relation_id", member.relationId)
                            put("record_client_uuid", member.recordClientUuid)
                            put("role", member.role)
                        }
                    }))
                })
            }
        }
        val autoDisplays = sourceRelationDao.listAutoAlignedDisplayClientUuids()
            .filter { it in touched }.distinct().sorted()
        val bytes = buildJsonObject {
            put("relations", JsonArray(evidence))
            put("auto_displays", JsonArray(autoDisplays.map(::JsonPrimitive)))
        }.toString().toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

class SourceRelationCommandRefreshRequiredException(cause: Throwable? = null) : IllegalStateException(
    when {
        cause is UnsupportedOperationException || (cause is SyncHttpException &&
            (cause.statusCode in setOf(404, 501) || (cause.statusCode == 422 &&
                com.lezi.babylog.sync.backend.syncHttpCodeOrNull(cause.responseBody) == "source_relation_protocol_unsupported"))) ->
            "选择已在原家庭服务器确认，但服务器版本不支持读取当前来源关系；请更新原服务器后重试，刷新完成前恢复仍暂停。"
        cause is IllegalStateException && cause !is SourceRelationCommandUnsettledException ->
            "选择已在原家庭服务器确认；${cause.message ?: "当前来源关系尚待刷新"}。刷新完成前恢复仍暂停。"
        else -> "选择已在原家庭服务器确认，当前来源关系尚待刷新；请连接原服务器重试。刷新完成前恢复仍暂停。"
    },
    cause,
)

private fun requireRefresh(condition: Boolean, message: String) {
    if (!condition) throw SourceRelationCommandRefreshRequiredException(IllegalStateException(message))
}

private fun validateAccepted(command: Command, result: SourceRelationResult) {
    checkPending(result.status == "accepted" && !result.relationId.isNullOrBlank() &&
        result.relationId.length <= 128 && result.displayClientUuid == command.display &&
        result.mediaRetained == true && result.code == null && result.latestVersions.isEmpty())
    checkPending(result.sourceClientUuids.size == command.members.size - 1 &&
        result.sourceClientUuids.toSet() == command.members.toSet() - command.display)
}

class SourceRelationCommandUnsettledException(cause: Throwable? = null) : IllegalStateException(
    "来源关系选择结果尚未确认，恢复已暂停；请连接原家庭服务器并重试同一选择，确认当前来源关系后再恢复。原服务器不可用时不能安全跳过此选择。",
    cause,
)

private fun checkPending(condition: Boolean) {
    if (!condition) throw SourceRelationCommandUnsettledException()
}

private const val MAX_MEMBERS = 64
private const val MAX_JOURNAL_BYTES = 64 * 1024
private const val MAX_REFRESH_READS = 4
private const val MAX_REFRESH_MILLIS = 12_000L

private data class Authority(
    val familyId: String,
    val membershipId: String,
    val deviceId: String,
    val generation: String,
    val origin: String,
    val trustMode: String,
    val spki: String,
    val role: String,
) {
    fun encode() = JsonArray(listOf(familyId, membershipId, deviceId, generation, origin, trustMode, spki, role)
        .map(::JsonPrimitive))
}

private data class Command(
    val kind: String,
    val mutationId: String,
    val members: List<String>,
    val display: String,
    val versions: Map<String, String>,
) {
    fun validate() {
        require(kind in setOf(SourceRelationReason.AUTHOR_DECLARE, SourceRelationReason.OWNER_GROUP_RESOLVE))
        require(mutationId.isNotBlank() && mutationId.length <= 128)
        require(members.size in 2..MAX_MEMBERS && members.toSet().size == members.size)
        require(display in members && versions.keys == members.toSet())
        require((members + versions.values).all { it.isNotBlank() && it.length <= 512 })
        require(kind != SourceRelationReason.AUTHOR_DECLARE || (members.size == 2 && display == members[1]))
    }

    fun sameChoice(other: Command): Boolean = kind == other.kind && display == other.display &&
        members.toSet() == other.members.toSet() && versions == other.versions

    fun asDeclare() = SourceRelationDeclareRequest(
        mutationId, members[0], display, versions.getValue(members[0]), versions.getValue(display),
    )

    fun asResolve() = SourceRelationResolveGroupRequest(mutationId, members, display, versions)

    companion object {
        fun declare(request: SourceRelationDeclareRequest) = Command(
            SourceRelationReason.AUTHOR_DECLARE,
            request.mutationId,
            listOf(request.recordClientUuid, request.equivalentToClientUuid),
            request.equivalentToClientUuid,
            mapOf(request.recordClientUuid to request.expectedRecordVersion,
                request.equivalentToClientUuid to request.expectedOtherVersion),
        )
        fun resolve(request: SourceRelationResolveGroupRequest) = Command(
            SourceRelationReason.OWNER_GROUP_RESOLVE, request.mutationId,
            request.memberClientUuids.toList(), request.displayClientUuid, request.expectedVersions.toMap(),
        )
    }
}

private data class Journal(
    val authority: Authority,
    val command: Command,
    val capturedCursor: Long,
    val canonicalEvidence: String,
    val createdAt: Long,
    val acceptedReceipt: SourceRelationResult? = null,
) {
    fun declaration(status: String): SourceRelationDeclarationEntity? =
        if (command.kind != SourceRelationReason.AUTHOR_DECLARE) null else SourceRelationDeclarationEntity(
            command.mutationId, command.members[0], command.display,
            command.versions.getValue(command.members[0]), command.versions.getValue(command.display),
            authority.membershipId, status, createdAt,
        )

    fun encode(): String = buildJsonObject {
        put("authority", authority.encode())
        put("kind", command.kind)
        put("mutation_id", command.mutationId)
        put("members", JsonArray(command.members.map(::JsonPrimitive)))
        put("display", command.display)
        put("versions", JsonObject(command.versions.mapValues { JsonPrimitive(it.value) }))
        put("cursor", capturedCursor)
        put("canonical_evidence", canonicalEvidence)
        put("created_at", createdAt)
        acceptedReceipt?.let { receipt ->
            put("phase", "confirmed_refresh_required")
            put("accepted_receipt", buildJsonObject {
                put("relation_id", receipt.relationId)
                put("display_client_uuid", receipt.displayClientUuid)
                put("source_client_uuids", JsonArray(receipt.sourceClientUuids.map(::JsonPrimitive)))
                put("media_retained", true)
            })
        }
    }.toString().also { checkPending(it.toByteArray(Charsets.UTF_8).size <= MAX_JOURNAL_BYTES) }
}

private fun decode(payload: String): Journal = try {
    checkPending(payload.toByteArray(Charsets.UTF_8).size <= MAX_JOURNAL_BYTES)
    val json = Json.parseToJsonElement(payload).jsonObject
    val baseKeys = setOf("authority", "kind", "mutation_id", "members", "display", "versions",
        "cursor", "canonical_evidence", "created_at")
    checkPending(json.keys == baseKeys || json.keys == baseKeys + setOf("phase", "accepted_receipt"))
    val receipt = if ("phase" in json) {
        checkPending(json.getValue("phase") == JsonPrimitive("confirmed_refresh_required"))
        val value = json.getValue("accepted_receipt").jsonObject
        checkPending(value.keys == setOf("relation_id", "display_client_uuid", "source_client_uuids", "media_retained"))
        checkPending(value.getValue("media_retained") == JsonPrimitive(true))
        SourceRelationResult("accepted", value.getValue("relation_id").jsonPrimitive.content,
            value.getValue("display_client_uuid").jsonPrimitive.content,
            value.getValue("source_client_uuids").jsonArray.map { it.jsonPrimitive.content }, true)
    } else null
    val authority = json.getValue("authority").jsonArray.map { it.jsonPrimitive.content }
    checkPending(authority.size == 8)
    val command = Command(
        json.getValue("kind").jsonPrimitive.content,
        json.getValue("mutation_id").jsonPrimitive.content,
        json.getValue("members").jsonArray.map { it.jsonPrimitive.content },
        json.getValue("display").jsonPrimitive.content,
        json.getValue("versions").jsonObject.mapValues { it.value.jsonPrimitive.content },
    )
    command.validate()
    Journal(
        Authority(authority[0], authority[1], authority[2], authority[3], authority[4], authority[5],
            authority[6], authority[7]),
        command,
        json.getValue("cursor").jsonPrimitive.long,
        json.getValue("canonical_evidence").jsonPrimitive.content,
        json.getValue("created_at").jsonPrimitive.long,
        receipt,
    )
} catch (error: SourceRelationCommandUnsettledException) {
    throw error
} catch (error: Exception) {
    throw SourceRelationCommandUnsettledException(error)
}
