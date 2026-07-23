package com.lezi.babylog.domain

/**
 * Schedules the optional next-feed reminder without exposing the settings
 * feature implementation to record and timer features.
 */
interface FeedReminderPort {
    suspend fun scheduleAfterFeed(atMillis: Long? = null)
}
