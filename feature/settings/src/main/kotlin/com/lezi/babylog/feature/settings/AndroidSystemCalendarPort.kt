package com.lezi.babylog.feature.settings

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.lezi.babylog.domain.SystemCalendarPort
import com.lezi.babylog.domain.SystemCalendarProjectionContract
import com.lezi.babylog.domain.SystemCalendarTarget
import com.lezi.babylog.domain.SystemCalendarUpsert
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CalendarContract projection of CarePlan. Device-local only; never family-synced.
 * Failures return null so CareLog falls back to Lezi reminders.
 */
@Singleton
class AndroidSystemCalendarPort @Inject constructor(
    @ApplicationContext private val context: Context,
) : SystemCalendarPort {
    override fun hasCalendarPermission(): Boolean {
        val write = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_CALENDAR,
        ) == PackageManager.PERMISSION_GRANTED
        val read = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALENDAR,
        ) == PackageManager.PERMISSION_GRANTED
        return write && read
    }

    override suspend fun listWritableCalendars(): List<SystemCalendarTarget> {
        if (!hasCalendarPermission()) return emptyList()
        return runCatching {
            val out = mutableListOf<SystemCalendarTarget>()
            val projection = arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.ACCOUNT_NAME,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            )
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                null,
                null,
                null,
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
                val nameIdx =
                    cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
                val accountIdx =
                    cursor.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_NAME)
                val accessIdx =
                    cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
                while (cursor.moveToNext()) {
                    val access = cursor.getInt(accessIdx)
                    if (!SystemCalendarProjectionContract.isWritableAccessLevel(
                            access,
                            CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR,
                        )
                    ) {
                        continue
                    }
                    out += SystemCalendarTarget(
                        calendarId = cursor.getLong(idIdx).toString(),
                        displayName = cursor.getString(nameIdx).orEmpty().ifBlank { "日历" },
                        accountName = cursor.getString(accountIdx).orEmpty(),
                    )
                }
            }
            out
        }.getOrDefault(emptyList())
    }

    override suspend fun upsertEvent(request: SystemCalendarUpsert): String? {
        if (!hasCalendarPermission()) return null
        return runCatching {
            val calendarId = request.calendarId.toLongOrNull() ?: return null
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, request.title)
                put(CalendarContract.Events.DESCRIPTION, request.description)
                put(CalendarContract.Events.DTSTART, request.beginAtMillis)
                // Point event: non-zero duration so OEMs accept begin-time alerts.
                put(
                    CalendarContract.Events.DTEND,
                    request.beginAtMillis + SystemCalendarProjectionContract.POINT_EVENT_DURATION_MS,
                )
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                put(CalendarContract.Events.HAS_ALARM, 1)
                put(CalendarContract.Events.CUSTOM_APP_PACKAGE, context.packageName)
                // L3 deep link; OEMs may ignore — description text still carries the URI.
                if (!request.customAppUri.isNullOrBlank()) {
                    put(CalendarContract.Events.CUSTOM_APP_URI, request.customAppUri)
                }
                put(
                    CalendarContract.Events.UID_2445,
                    SystemCalendarProjectionContract.eventUid(request.carePlanClientUuid),
                )
            }
            val resolver = context.contentResolver
            val eventId = if (!request.existingEventId.isNullOrBlank()) {
                val id = request.existingEventId!!.toLongOrNull() ?: return null
                val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
                val updated = resolver.update(uri, values, null, null)
                if (updated <= 0) return null
                id
            } else {
                val uri = resolver.insert(CalendarContract.Events.CONTENT_URI, values)
                    ?: return null
                ContentUris.parseId(uri)
            }
            // Begin-time reminder (minutes = 0).
            ensureBeginReminder(eventId)
            eventId.toString()
        }.getOrNull()
    }

    override suspend fun deleteEvent(eventId: String): Boolean {
        if (!hasCalendarPermission()) return false
        val id = eventId.toLongOrNull() ?: return false
        return runCatching {
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
            context.contentResolver.delete(uri, null, null) > 0
        }.getOrDefault(false)
    }

    override suspend fun eventExists(eventId: String): Boolean {
        if (!hasCalendarPermission()) return false
        val id = eventId.toLongOrNull() ?: return false
        return runCatching {
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
            context.contentResolver.query(
                uri,
                arrayOf(CalendarContract.Events._ID),
                null,
                null,
                null,
            )?.use { it.moveToFirst() } == true
        }.getOrDefault(false)
    }

    override suspend fun isWritableCalendar(calendarId: String): Boolean {
        if (!hasCalendarPermission()) return false
        return listWritableCalendars().any { it.calendarId == calendarId }
    }

    private fun ensureBeginReminder(eventId: Long) {
        runCatching {
            val resolver = context.contentResolver
            // Drop prior reminders for this event then insert begin-time alert.
            resolver.delete(
                CalendarContract.Reminders.CONTENT_URI,
                "${CalendarContract.Reminders.EVENT_ID}=?",
                arrayOf(eventId.toString()),
            )
            val values = ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, eventId)
                put(
                    CalendarContract.Reminders.MINUTES,
                    SystemCalendarProjectionContract.BEGIN_REMINDER_MINUTES,
                )
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            }
            resolver.insert(CalendarContract.Reminders.CONTENT_URI, values)
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SystemCalendarModule {
    @Binds
    @Singleton
    abstract fun bindSystemCalendar(adapter: AndroidSystemCalendarPort): SystemCalendarPort
}
