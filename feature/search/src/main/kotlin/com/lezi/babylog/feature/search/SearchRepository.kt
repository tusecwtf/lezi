package com.lezi.babylog.feature.search

import com.lezi.babylog.core.model.Record
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.session.FamilyRole
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan

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
)

fun interface SearchRepository {
    suspend fun search(query: String): List<SearchResult>

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
    override suspend fun search(query: String): List<SearchResult> {
        val baby = careLog.getCurrentBaby() ?: return emptyList()
        val session = syncPort.session().first()
        val actorMembershipId = session.membershipId
        val actorIsAdmin = session.role == FamilyRole.Owner
        return careLog.search(baby.id, query).map { record ->
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
    ) { babyId, sourceRoles, projectionTick ->
        SearchRevision(
            babyId = babyId,
            sourceRoleClientUuids = sourceRoles,
            projectionTick = projectionTick,
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
