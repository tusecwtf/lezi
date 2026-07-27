package com.lezi.babylog.feature.settings

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.lezi.babylog.domain.SystemCalendarPort
import com.lezi.babylog.domain.SystemCalendarEventState
import com.lezi.babylog.domain.SystemCalendarOwnedEventLookup
import com.lezi.babylog.domain.SystemCalendarProjectionContract
import com.lezi.babylog.domain.SystemCalendarTarget
import com.lezi.babylog.domain.SystemCalendarUpsert
import com.lezi.babylog.domain.SystemCalendarUpsertOutcome
import com.lezi.babylog.domain.SystemCalendarUpsertResult
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
 * Provider identity and reminder readiness are reported separately so a failed
 * reminder repair never loses the event id or creates another calendar copy.
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

    override suspend fun upsertEvent(
        request: SystemCalendarUpsert,
    ): SystemCalendarUpsertResult {
        val knownEventId = request.existingEventId
            ?.toLongOrNull()
            ?.toString()
        if (!hasCalendarPermission()) {
            return unavailableProviderResult(request, knownEventId)
        }

        val existingState = knownEventId?.let {
            eventState(it, request.carePlanClientUuid)
        }
        val ownedLookup = findOwnedEvent(request.carePlanClientUuid)
        val target = selectSystemCalendarUpsertTarget(
            existingEventId = knownEventId,
            existingState = existingState,
            ownedLookup = ownedLookup,
        )
        when (target) {
            is SystemCalendarUpsertTarget.RetryKnown -> {
                return SystemCalendarUpsertResult(
                    eventId = target.eventId,
                    outcome = SystemCalendarUpsertOutcome.ProviderStillOwnsStale,
                )
            }
            SystemCalendarUpsertTarget.Unavailable -> {
                return unavailableProviderResult(request, knownEventId)
            }
            else -> Unit
        }

        val calendarId = request.calendarId.toLongOrNull()
        if (calendarId == null || !isWritableCalendar(request.calendarId)) {
            return releaseProviderRemindersForFallback(request, target, ownedLookup)
        }
        val values = eventValues(request, calendarId)
        val baseResult = when (target) {
            is SystemCalendarUpsertTarget.Update -> updateKnownEvent(
                eventId = target.eventId,
                request = request,
                values = values,
                ownedLookup = ownedLookup,
            )
            SystemCalendarUpsertTarget.Insert -> insertConfirmedAbsentEvent(request, values)
            is SystemCalendarUpsertTarget.RetryKnown,
            SystemCalendarUpsertTarget.Unavailable,
            -> error("handled before provider mutation")
        }
        val resultEventId = baseResult.eventId ?: return baseResult
        val duplicatesConverged = convergeOwnedEvents(
            carePlanClientUuid = request.carePlanClientUuid,
            canonicalEventId = resultEventId,
        )
        return if (duplicatesConverged) {
            baseResult
        } else {
            baseResult.copy(outcome = SystemCalendarUpsertOutcome.ProviderStillOwnsStale)
        }
    }

    private fun unavailableProviderResult(
        request: SystemCalendarUpsert,
        knownEventId: String?,
    ): SystemCalendarUpsertResult = SystemCalendarUpsertResult(
        eventId = knownEventId,
        outcome = if (request.providerHandoffMayExist || knownEventId != null) {
            SystemCalendarUpsertOutcome.ProviderStillOwnsStale
        } else {
            SystemCalendarUpsertOutcome.ReleasedOrAbsent
        },
    )

    override suspend fun findOwnedEvent(
        carePlanClientUuid: String,
    ): SystemCalendarOwnedEventLookup = strictOwnedSystemCalendarEventLookup(
        hasPermission = hasCalendarPermission(),
        carePlanClientUuid = carePlanClientUuid,
        appPackage = context.packageName,
    ) { uid, appPackage ->
        val cursor = context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events._ID),
            "${CalendarContract.Events.UID_2445}=? AND " +
                "${CalendarContract.Events.CUSTOM_APP_PACKAGE}=? AND " +
                "${CalendarContract.Events.DELETED}=0",
            arrayOf(uid, appPackage),
            "${CalendarContract.Events._ID} ASC",
        ) ?: return@strictOwnedSystemCalendarEventLookup null
        cursor.use {
            val idIndex = it.getColumnIndexOrThrow(CalendarContract.Events._ID)
            buildList {
                while (it.moveToNext()) add(it.getLong(idIndex).toString())
            }
        }
    }

    override suspend fun deleteEvent(
        eventId: String,
        carePlanClientUuid: String?,
    ): Boolean {
        if (!hasCalendarPermission()) return false
        val id = eventId.toLongOrNull() ?: return false
        when (eventState(eventId, carePlanClientUuid)) {
            SystemCalendarEventState.PRESENT -> {
                // Remove the notification source before the event row. Some OEM
                // providers do not cascade an Events delete into Reminders.
                runCatching {
                    context.contentResolver.delete(
                        CalendarContract.Reminders.CONTENT_URI,
                        "${CalendarContract.Reminders.EVENT_ID}=?",
                        arrayOf(eventId),
                    )
                }
                if (queryReminderState(id, beginOnly = false) != SystemCalendarEventState.ABSENT) {
                    return false
                }
            }
            // ABSENT may mean the id belongs to another package/UID. Without a
            // live owned row, neither mutation nor a successful delete claim is safe.
            SystemCalendarEventState.ABSENT -> return false
            SystemCalendarEventState.UNAVAILABLE -> return false
        }
        val ownership = ownedEventSelection(
            eventId = id,
            appPackage = context.packageName,
            carePlanClientUuid = carePlanClientUuid,
        )
        runCatching {
            context.contentResolver.delete(
                CalendarContract.Events.CONTENT_URI,
                ownership.selection,
                ownership.selectionArgs,
            )
        }
        return strictOwnedEventDeleteSucceeded(
            eventState = eventState(eventId, carePlanClientUuid),
            reminderState = queryReminderState(id, beginOnly = false),
        )
    }

    override suspend fun eventState(
        eventId: String,
        carePlanClientUuid: String?,
    ): SystemCalendarEventState {
        return strictSystemCalendarEventState(
            hasPermission = hasCalendarPermission(),
            eventId = eventId,
        ) { id ->
            val ownership = ownedEventSelection(
                eventId = id,
                appPackage = context.packageName,
                carePlanClientUuid = carePlanClientUuid,
            )
            val cursor = context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(CalendarContract.Events._ID),
                ownership.selection,
                ownership.selectionArgs,
                null,
            ) ?: return@strictSystemCalendarEventState null
            cursor.use { it.moveToFirst() }
        }
    }

    override suspend fun isWritableCalendar(calendarId: String): Boolean {
        if (!hasCalendarPermission()) return false
        return listWritableCalendars().any { it.calendarId == calendarId }
    }

    private suspend fun releaseProviderRemindersForFallback(
        request: SystemCalendarUpsert,
        target: SystemCalendarUpsertTarget,
        initialLookup: SystemCalendarOwnedEventLookup,
    ): SystemCalendarUpsertResult {
        val lookup = if (initialLookup is SystemCalendarOwnedEventLookup.Unavailable) {
            findOwnedEvent(request.carePlanClientUuid)
        } else {
            initialLookup
        }
        val eventIds = linkedSetOf<String>().apply {
            if (lookup is SystemCalendarOwnedEventLookup.Found) addAll(lookup.eventIds)
            if (target is SystemCalendarUpsertTarget.Update) add(target.eventId)
        }
        if (eventIds.isEmpty()) {
            return SystemCalendarUpsertResult(
                eventId = null,
                outcome = if (
                    lookup is SystemCalendarOwnedEventLookup.Unavailable &&
                    request.providerHandoffMayExist
                ) {
                    SystemCalendarUpsertOutcome.ProviderStillOwnsStale
                } else {
                    SystemCalendarUpsertOutcome.ReleasedOrAbsent
                },
            )
        }

        val reminderStates = eventIds.map { eventId ->
            when (eventState(eventId, request.carePlanClientUuid)) {
                SystemCalendarEventState.PRESENT -> {
                    runCatching {
                        context.contentResolver.delete(
                            CalendarContract.Reminders.CONTENT_URI,
                            "${CalendarContract.Reminders.EVENT_ID}=?",
                            arrayOf(eventId),
                        )
                    }
                    queryReminderState(eventId.toLong(), beginOnly = false)
                }
                // Do not infer reminder absence from event absence. A failed or
                // non-cascading provider delete may leave an orphan reminder row.
                SystemCalendarEventState.ABSENT ->
                    queryReminderState(eventId.toLong(), beginOnly = false)
                SystemCalendarEventState.UNAVAILABLE -> SystemCalendarEventState.UNAVAILABLE
            }
        }
        val releaseOutcome = if (lookup is SystemCalendarOwnedEventLookup.Unavailable) {
            SystemCalendarUpsertOutcome.ProviderStillOwnsStale
        } else {
            resolveReleasedProviderReminders(reminderStates)
        }
        return SystemCalendarUpsertResult(
            eventId = (target as? SystemCalendarUpsertTarget.Update)?.eventId
                ?: (lookup as? SystemCalendarOwnedEventLookup.Found)?.canonicalEventId,
            outcome = releaseOutcome,
        )
    }

    private fun eventValues(request: SystemCalendarUpsert, calendarId: Long): ContentValues =
        ContentValues().apply {
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
            } else {
                putNull(CalendarContract.Events.CUSTOM_APP_URI)
            }
            put(
                CalendarContract.Events.UID_2445,
                SystemCalendarProjectionContract.eventUid(request.carePlanClientUuid),
            )
        }

    private suspend fun updateKnownEvent(
        eventId: String,
        request: SystemCalendarUpsert,
        values: ContentValues,
        ownedLookup: SystemCalendarOwnedEventLookup,
    ): SystemCalendarUpsertResult {
        val id = eventId.toLong()
        val ownership = ownedEventSelection(
            eventId = id,
            appPackage = context.packageName,
            carePlanClientUuid = request.carePlanClientUuid,
        )
        val updated = runCatching {
            context.contentResolver.update(
                CalendarContract.Events.CONTENT_URI,
                values,
                ownership.selection,
                ownership.selectionArgs,
            )
        }.getOrDefault(0)
        if (updated > 0) {
            ensureBeginReminder(id)
            return readRequestedUpsertResult(eventId, request)
        }
        val strictRead = readRequestedUpsertResult(eventId, request)
        if (strictRead.outcome == SystemCalendarUpsertOutcome.CurrentReady) {
            // Some providers report zero for a no-op update. The exact owned
            // generation and begin reminder are nevertheless authoritative.
            return strictRead
        }
        // The old generation must not retain a provider reminder while CareLog
        // falls back to the newly requested Lezi alarm. Release every owned
        // duplicate and require a strict all-reminders-absent readback.
        return releaseProviderRemindersForFallback(
            request = request,
            target = SystemCalendarUpsertTarget.Update(eventId),
            initialLookup = ownedLookup,
        )
    }

    private suspend fun insertConfirmedAbsentEvent(
        request: SystemCalendarUpsert,
        values: ContentValues,
    ): SystemCalendarUpsertResult {
        val uri = runCatching {
            context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
        }.getOrNull()
        val id = uri?.let { runCatching { ContentUris.parseId(it) }.getOrNull() }
            ?: return recoverIndeterminateInsert(request)
        ensureBeginReminder(id)
        return readRequestedUpsertResult(id.toString(), request)
    }

    private suspend fun recoverIndeterminateInsert(
        request: SystemCalendarUpsert,
    ): SystemCalendarUpsertResult = when (val lookup = findOwnedEvent(request.carePlanClientUuid)) {
        is SystemCalendarOwnedEventLookup.Found -> {
            readRequestedUpsertResult(lookup.canonicalEventId, request)
        }
        SystemCalendarOwnedEventLookup.Absent -> SystemCalendarUpsertResult(
            eventId = null,
            outcome = SystemCalendarUpsertOutcome.ReleasedOrAbsent,
        )
        SystemCalendarOwnedEventLookup.Unavailable -> SystemCalendarUpsertResult(
            eventId = null,
            outcome = SystemCalendarUpsertOutcome.ProviderStillOwnsStale,
        )
    }

    private fun ensureBeginReminder(eventId: Long) {
        val resolver = context.contentResolver
        runCatching {
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

    private fun readRequestedUpsertResult(
        eventId: String,
        request: SystemCalendarUpsert,
    ): SystemCalendarUpsertResult {
        val beginReminderState = queryReminderState(eventId.toLong(), beginOnly = true)
        val anyReminderState = if (beginReminderState == SystemCalendarEventState.PRESENT) {
            SystemCalendarEventState.PRESENT
        } else {
            queryReminderState(eventId.toLong(), beginOnly = false)
        }
        return SystemCalendarUpsertResult(
            eventId = eventId,
            outcome = resolveSystemCalendarUpsertOutcome(
                requestedEventState = queryRequestedEventState(eventId, request),
                beginReminderState = beginReminderState,
                anyReminderState = anyReminderState,
            ),
        )
    }

    private fun queryRequestedEventState(
        eventId: String,
        request: SystemCalendarUpsert,
    ): SystemCalendarRequestedEventState {
        val id = eventId.toLongOrNull() ?: return SystemCalendarRequestedEventState.UNAVAILABLE
        val expectedCalendarId = request.calendarId.toLongOrNull()
            ?: return SystemCalendarRequestedEventState.STALE
        val ownership = ownedEventSelection(
            eventId = id,
            appPackage = context.packageName,
            carePlanClientUuid = request.carePlanClientUuid,
        )
        return runCatching {
            val cursor = context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(
                    CalendarContract.Events.CALENDAR_ID,
                    CalendarContract.Events.DTSTART,
                ),
                ownership.selection,
                ownership.selectionArgs,
                null,
            ) ?: return@runCatching SystemCalendarRequestedEventState.UNAVAILABLE
            cursor.use {
                if (!it.moveToFirst()) return@use SystemCalendarRequestedEventState.ABSENT
                val calendarMatches = it.getLong(0) == expectedCalendarId
                val beginMatches = it.getLong(1) == request.beginAtMillis
                if (calendarMatches && beginMatches) {
                    SystemCalendarRequestedEventState.MATCH
                } else {
                    SystemCalendarRequestedEventState.STALE
                }
            }
        }.getOrDefault(SystemCalendarRequestedEventState.UNAVAILABLE)
    }

    private fun queryReminderState(
        eventId: Long,
        beginOnly: Boolean,
    ): SystemCalendarEventState = runCatching {
        val selection = buildString {
            append("${CalendarContract.Reminders.EVENT_ID}=?")
            if (beginOnly) {
                append(" AND ${CalendarContract.Reminders.MINUTES}=?")
                append(" AND ${CalendarContract.Reminders.METHOD}=?")
            }
        }
        val args = if (beginOnly) {
            arrayOf(
                eventId.toString(),
                SystemCalendarProjectionContract.BEGIN_REMINDER_MINUTES.toString(),
                CalendarContract.Reminders.METHOD_ALERT.toString(),
            )
        } else {
            arrayOf(eventId.toString())
        }
        val cursor = context.contentResolver.query(
            CalendarContract.Reminders.CONTENT_URI,
            arrayOf(CalendarContract.Reminders._ID),
            selection,
            args,
            null,
        ) ?: return@runCatching SystemCalendarEventState.UNAVAILABLE
        cursor.use {
            if (it.moveToFirst()) {
                SystemCalendarEventState.PRESENT
            } else {
                SystemCalendarEventState.ABSENT
            }
        }
    }.getOrDefault(SystemCalendarEventState.UNAVAILABLE)

    private suspend fun convergeOwnedEvents(
        carePlanClientUuid: String,
        canonicalEventId: String,
    ): Boolean {
        val before = findOwnedEvent(carePlanClientUuid)
        if (before !is SystemCalendarOwnedEventLookup.Found) {
            return false
        }
        if (canonicalEventId !in before.eventIds) return false
        val duplicateIds = before.eventIds - canonicalEventId
        if (duplicateIds.isEmpty()) return true
        for (duplicateId in duplicateIds) {
            runCatching { deleteEvent(duplicateId, carePlanClientUuid) }
        }
        val after = findOwnedEvent(carePlanClientUuid)
        return strictOwnedDuplicatesConverged(
            ownedLookup = after,
            canonicalEventId = canonicalEventId,
            duplicateReminderStates = duplicateIds.map { duplicateId ->
                queryReminderState(duplicateId.toLong(), beginOnly = false)
            },
        )
    }
}

internal data class SystemCalendarOwnedEventSelection(
    val selection: String,
    val selectionArgs: Array<String>,
)

/** One ownership predicate shared by state, update, and delete mutations. */
internal fun ownedEventSelection(
    eventId: Long,
    appPackage: String,
    carePlanClientUuid: String?,
): SystemCalendarOwnedEventSelection {
    val clauses = mutableListOf(
        "${CalendarContract.Events._ID}=?",
        "${CalendarContract.Events.CUSTOM_APP_PACKAGE}=?",
        "${CalendarContract.Events.DELETED}=0",
    )
    val args = mutableListOf(eventId.toString(), appPackage)
    if (carePlanClientUuid != null) {
        clauses += "${CalendarContract.Events.UID_2445}=?"
        args += SystemCalendarProjectionContract.eventUid(carePlanClientUuid)
    }
    return SystemCalendarOwnedEventSelection(
        selection = clauses.joinToString(" AND "),
        selectionArgs = args.toTypedArray(),
    )
}

/** Keeps ContentProvider null/error distinct from a confirmed empty query. */
internal fun strictSystemCalendarEventState(
    hasPermission: Boolean,
    eventId: String,
    queryPresent: (Long) -> Boolean?,
): SystemCalendarEventState {
    if (!hasPermission) return SystemCalendarEventState.UNAVAILABLE
    val id = eventId.toLongOrNull() ?: return SystemCalendarEventState.UNAVAILABLE
    val present = try {
        queryPresent(id)
    } catch (_: Exception) {
        null
    }
    return when (present) {
        true -> SystemCalendarEventState.PRESENT
        false -> SystemCalendarEventState.ABSENT
        null -> SystemCalendarEventState.UNAVAILABLE
    }
}

/** Keeps a null/failed UID query distinct from a confirmed empty provider result. */
internal fun strictOwnedSystemCalendarEventLookup(
    hasPermission: Boolean,
    carePlanClientUuid: String,
    appPackage: String,
    queryEventIds: (uid: String, appPackage: String) -> List<String>?,
): SystemCalendarOwnedEventLookup {
    if (!hasPermission || carePlanClientUuid.isBlank() || appPackage.isBlank()) {
        return SystemCalendarOwnedEventLookup.Unavailable
    }
    val eventIds = try {
        queryEventIds(
            SystemCalendarProjectionContract.eventUid(carePlanClientUuid),
            appPackage,
        )
    } catch (_: Exception) {
        null
    } ?: return SystemCalendarOwnedEventLookup.Unavailable
    val validIds = eventIds.filterTo(linkedSetOf()) { it.toLongOrNull() != null }
    return if (validIds.isEmpty()) {
        SystemCalendarOwnedEventLookup.Absent
    } else {
        SystemCalendarOwnedEventLookup.Found(validIds)
    }
}

internal sealed interface SystemCalendarUpsertTarget {
    data class Update(val eventId: String) : SystemCalendarUpsertTarget
    data class RetryKnown(val eventId: String) : SystemCalendarUpsertTarget
    data object Insert : SystemCalendarUpsertTarget
    data object Unavailable : SystemCalendarUpsertTarget
}

/** Resolves one mutation target without converting an unavailable lookup into insert. */
internal fun selectSystemCalendarUpsertTarget(
    existingEventId: String?,
    existingState: SystemCalendarEventState?,
    ownedLookup: SystemCalendarOwnedEventLookup,
): SystemCalendarUpsertTarget {
    if (ownedLookup is SystemCalendarOwnedEventLookup.Found) {
        return SystemCalendarUpsertTarget.Update(ownedLookup.canonicalEventId)
    }
    return when (ownedLookup) {
        is SystemCalendarOwnedEventLookup.Found -> error("handled above")
        SystemCalendarOwnedEventLookup.Absent -> when (existingState) {
            SystemCalendarEventState.PRESENT -> {
                SystemCalendarUpsertTarget.Update(checkNotNull(existingEventId))
            }
            SystemCalendarEventState.ABSENT,
            null,
            -> SystemCalendarUpsertTarget.Insert
            SystemCalendarEventState.UNAVAILABLE -> {
                existingEventId?.let(SystemCalendarUpsertTarget::RetryKnown)
                    ?: SystemCalendarUpsertTarget.Unavailable
            }
        }
        SystemCalendarOwnedEventLookup.Unavailable -> {
            when (existingState) {
                SystemCalendarEventState.PRESENT -> {
                    SystemCalendarUpsertTarget.Update(checkNotNull(existingEventId))
                }
                SystemCalendarEventState.ABSENT,
                SystemCalendarEventState.UNAVAILABLE,
                null,
                -> existingEventId?.let(SystemCalendarUpsertTarget::RetryKnown)
                    ?: SystemCalendarUpsertTarget.Unavailable
            }
        }
    }
}

internal enum class SystemCalendarRequestedEventState {
    MATCH,
    STALE,
    ABSENT,
    UNAVAILABLE,
}

/** Resolves ownership from the exact requested generation, never from a reminder row alone. */
internal fun resolveSystemCalendarUpsertOutcome(
    requestedEventState: SystemCalendarRequestedEventState,
    beginReminderState: SystemCalendarEventState,
    anyReminderState: SystemCalendarEventState,
): SystemCalendarUpsertOutcome = when {
    requestedEventState == SystemCalendarRequestedEventState.MATCH &&
        beginReminderState == SystemCalendarEventState.PRESENT ->
        SystemCalendarUpsertOutcome.CurrentReady
    anyReminderState == SystemCalendarEventState.ABSENT ->
        SystemCalendarUpsertOutcome.ReleasedOrAbsent
    else -> SystemCalendarUpsertOutcome.ProviderStillOwnsStale
}

/** Lezi fallback is safe only after every known provider reminder is confirmed absent. */
internal fun resolveReleasedProviderReminders(
    reminderStates: List<SystemCalendarEventState>,
): SystemCalendarUpsertOutcome = if (
    reminderStates.all { it == SystemCalendarEventState.ABSENT }
) {
    SystemCalendarUpsertOutcome.ReleasedOrAbsent
} else {
    SystemCalendarUpsertOutcome.ProviderStillOwnsStale
}

/** Deletion is complete only when neither the event nor any reminder can still fire. */
internal fun strictOwnedEventDeleteSucceeded(
    eventState: SystemCalendarEventState,
    reminderState: SystemCalendarEventState,
): Boolean = eventState == SystemCalendarEventState.ABSENT &&
    reminderState == SystemCalendarEventState.ABSENT

/** Duplicate convergence includes orphan-reminder verification, not only the UID event set. */
internal fun strictOwnedDuplicatesConverged(
    ownedLookup: SystemCalendarOwnedEventLookup,
    canonicalEventId: String,
    duplicateReminderStates: List<SystemCalendarEventState>,
): Boolean = ownedLookup is SystemCalendarOwnedEventLookup.Found &&
    ownedLookup.eventIds == setOf(canonicalEventId) &&
    duplicateReminderStates.all { it == SystemCalendarEventState.ABSENT }

@Module
@InstallIn(SingletonComponent::class)
abstract class SystemCalendarModule {
    @Binds
    @Singleton
    abstract fun bindSystemCalendar(adapter: AndroidSystemCalendarPort): SystemCalendarPort
}
