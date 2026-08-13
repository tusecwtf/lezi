package com.lezi.babylog.domain.carelog

import com.lezi.babylog.core.model.SleepEndSource
import com.lezi.babylog.core.model.SleepIntervalProjection

/**
 * Pure timeline/composer labels for sleep projection and open conflicts.
 * Feature Compose must not invent a second projection rule.
 */
object SleepPresentation {
    const val PROVISIONAL_BADGE = "暂定"
    const val OVERLAP_PENDING_BADGE = "重叠待确认"
    const val CONFLICT_BADGE = "有冲突"
    const val PENDING_SYNC_LABEL = "待同步"
    const val SYNCED_LABEL = "已同步"
    const val CONFLICT_PENDING_LABEL = "冲突待解决"

    fun endBadge(interval: SleepIntervalProjection): String? = when {
        interval.isProvisional -> PROVISIONAL_BADGE
        interval.isOverlapPending -> OVERLAP_PENDING_BADGE
        else -> null
    }

    fun conflictCardSummary(
        hasOpenConflict: Boolean,
        conflictingPathCount: Int = 0,
    ): String? {
        if (!hasOpenConflict) return null
        return if (conflictingPathCount > 0) {
            "$CONFLICT_BADGE · ${conflictingPathCount} 项差异"
        } else {
            CONFLICT_BADGE
        }
    }

    fun observationListLabel(
        observation: WakeObservation,
        interval: SleepIntervalProjection,
    ): String {
        val base = buildString {
            append("观察")
            if (observation.observerMembershipId.isNotBlank()) {
                append(" · ")
                append(observation.observerMembershipId)
            }
            if (observation.withdrawn) {
                append(" · 已撤回")
            }
        }
        val isEnd = interval.endObservationClientUuid == observation.clientUuid
        return when {
            observation.withdrawn -> base
            isEnd && interval.endSource == SleepEndSource.EFFECTIVE -> "$base · 有效"
            isEnd && interval.isProvisional -> "$base · $PROVISIONAL_BADGE"
            else -> base
        }
    }
}
