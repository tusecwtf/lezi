package com.lezi.babylog.feature.widget

import com.lezi.babylog.domain.CareLog
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

fun interface WidgetSummarySource {
    suspend fun load(babyId: Long): WidgetSummaryData
}

/** The configured baby no longer belongs to the active local family surface. */
internal class WidgetTargetBabyUnavailableException : IllegalStateException()

@Singleton
class CareLogWidgetSummarySource @Inject constructor(
    private val careLog: CareLog,
) : WidgetSummarySource {
    override suspend fun load(babyId: Long): WidgetSummaryData {
        if (careLog.listBabies().none { it.id == babyId }) {
            throw WidgetTargetBabyUnavailableException()
        }
        val summary = careLog.recentCareSummary(babyId)
        return WidgetSummaryData(
            babyName = summary.babyName,
            feedMl = summary.feedMl,
            sleepMinutes = summary.sleepMin,
            peeCount = summary.pee,
            poopCount = summary.poop,
            lastLabel = summary.lastLabel,
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class WidgetSummaryModule {
    @Binds
    abstract fun bindWidgetSummarySource(
        implementation: CareLogWidgetSummarySource,
    ): WidgetSummarySource
}
