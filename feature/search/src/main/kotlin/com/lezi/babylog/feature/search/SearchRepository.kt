package com.lezi.babylog.feature.search

import com.lezi.babylog.core.model.Record
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.SyncSession
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.CancellationException

/** One search hit with the same creator-or-owner edit capability as the timeline. */
data class SearchResult(
    val record: Record,
    val canEdit: Boolean,
)

/**
 * Identity of the inputs [CareLogSearchRepository.search] just read.
 * [projectionTick] advances on every record/wake invalidation, including
 * repeats of the same baby and source-role set.
 */
data class SearchRevision(
    val babyId: Long?,
    val sourceRoleClientUuids: Set<String>,
    val projectionTick: Long,
    val permissionScope: SearchPermissionScope = SyncSession().toSearchPermissionScope(),
)

data class SearchScope(
    val babyId: Long?,
    val sourceRoleClientUuids: Set<String>,
    val permissions: SearchPermissionScope,
)

internal fun SearchRevision.scope() = SearchScope(babyId, sourceRoleClientUuids.toSet(), permissionScope)

internal sealed class SearchScopeException : Exception()
internal class SearchScopeChangedException : SearchScopeException()
internal class SearchScopeUnavailableException : SearchScopeException()

internal suspend fun <T> withCurrentSearchScope(
    currentScope: suspend () -> SearchScope,
    search: suspend (SearchScope) -> T,
): T {
    val before = checkedSearchScope(currentScope)
    val result = search(before)
    if (before != checkedSearchScope(currentScope)) throw SearchScopeChangedException()
    return result
}

internal suspend fun <T> checkedSearchScope(read: suspend () -> T): T = try {
    read()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Throwable) {
    throw SearchScopeUnavailableException()
}

/** Fixed-size equality key only. Credentials and acknowledgement lists never leave this key. */
class SearchPermissionScope internal constructor(private val digest: List<Byte>) {
    init {
        require(digest.size == 32)
    }

    override fun equals(other: Any?): Boolean = other is SearchPermissionScope && digest == other.digest
    override fun hashCode(): Int = digest.hashCode()
    override fun toString(): String = "SearchPermissionScope(<redacted>)"
}

internal fun SyncSession.toSearchPermissionScope(): SearchPermissionScope {
    val digest = MessageDigest.getInstance("SHA-256")
    fun add(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        // Length-prefix values so adjacent fields cannot alias each other.
        digest.update(byteArrayOf(
            (bytes.size ushr 24).toByte(),
            (bytes.size ushr 16).toByte(),
            (bytes.size ushr 8).toByte(),
            bytes.size.toByte(),
        ))
        digest.update(bytes)
    }
    add(familyId)
    add(membershipId.trim())
    add(deviceId)
    add(role.name)
    add(baseUrl)
    add(isJoined.toString())
    add(reauthRequired.toString())
    // SyncSession has no credential epoch. Rotation conservatively invalidates
    // previous results; do not derive authentication failure from phone time.
    add(accessToken)
    add(refreshToken)
    pendingCreatorAcknowledgements
        .filter { it.entityType == "record" }
        .map { it.clientUuid }
        .sorted()
        .forEach(::add)
    return SearchPermissionScope(digest.digest().toList())
}

fun interface SearchRepository {
    suspend fun search(query: String): List<SearchResult>

    /** Direct local read; live flow delivery may lag the authority actually used by search. */
    suspend fun currentScope(): SearchScope? = null

    /**
     * Hot or cold stream of [SearchRevision]. An empty flow means the result
     * list is not live. The first emission is the snapshot at collect time,
     * which may already differ from the snapshot [search] observed.
     */
    fun revisions(): Flow<SearchRevision> = emptyFlow()
}

@Singleton
class CareLogSearchRepository @Inject constructor(
    private val careLog: CareLog,
    private val syncPort: SyncPort,
) : SearchRepository {
    override suspend fun currentScope(): SearchScope = SearchScope(
        babyId = careLog.getCurrentBaby()?.id,
        sourceRoleClientUuids = careLog.observeSourceRoleClientUuids().first().toSet(),
        permissions = syncPort.session().first().toSearchPermissionScope(),
    )

    override suspend fun search(query: String): List<SearchResult> =
        withCurrentSearchScope(::currentScope) { scope ->
            val babyId = scope.babyId ?: return@withCurrentSearchScope emptyList()
            val session = syncPort.session().first()
            if (session.toSearchPermissionScope() != scope.permissions) throw SearchScopeChangedException()
            val actorMembershipId = session.membershipId
            val actorIsAdmin = session.role == FamilyRole.Owner
            careLog.search(babyId, query).map { record ->
                SearchResult(
                    record = record,
                    canEdit = careLog.canManageRecord(
                        record,
                        actorMembershipId,
                        actorIsAdmin,
                        creatorAcknowledgementPending = session.isCreatorAcknowledgementPending(
                            entityType = "record",
                            clientUuid = record.clientUuid,
                        ),
                    ),
                )
            }
        }

    override fun revisions(): Flow<SearchRevision> = combine(
        careLog.observeCurrentBaby().map { it?.id }.distinctUntilChanged(),
        careLog.observeSourceRoleClientUuids(),
        careLog.observeRecordProjectionInvalidations()
            .scan(0L) { tick, _ -> tick + 1 }
            .drop(1),
        syncPort.session().map { it.toSearchPermissionScope() }.distinctUntilChanged(),
    ) { babyId, sourceRoles, projectionTick, permissionScope ->
        SearchRevision(
            babyId = babyId,
            sourceRoleClientUuids = sourceRoles.toSet(),
            projectionTick = projectionTick,
            permissionScope = permissionScope,
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class SearchRepositoryModule {
    @Binds
    abstract fun bindSearchRepository(
        implementation: CareLogSearchRepository,
    ): SearchRepository
}
