package com.lezi.babylog.feature.search

import com.lezi.babylog.core.model.Record
import com.lezi.babylog.domain.CareLog
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/** One search hit with the same creator-or-owner edit capability as the timeline. */
data class SearchResult(
    val record: Record,
    val canEdit: Boolean,
)

fun interface SearchRepository {
    suspend fun search(query: String): List<SearchResult>
}

@Singleton
class CareLogSearchRepository @Inject constructor(
    private val careLog: CareLog,
) : SearchRepository {
    override suspend fun search(query: String): List<SearchResult> {
        val baby = careLog.getCurrentBaby() ?: return emptyList()
        return careLog.search(baby.id, query).map { record ->
            SearchResult(
                record = record,
                canEdit = careLog.canManageRecord(record),
            )
        }
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
