package com.lezi.babylog.feature.search

import com.lezi.babylog.core.model.Record
import com.lezi.babylog.domain.CareLog
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

fun interface SearchRepository {
    suspend fun search(query: String): List<Record>
}

@Singleton
class CareLogSearchRepository @Inject constructor(
    private val careLog: CareLog,
) : SearchRepository {
    override suspend fun search(query: String): List<Record> {
        val baby = careLog.getCurrentBaby() ?: return emptyList()
        return careLog.search(baby.id, query)
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
