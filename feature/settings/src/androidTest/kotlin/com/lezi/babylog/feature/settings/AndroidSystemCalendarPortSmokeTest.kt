package com.lezi.babylog.feature.settings

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.provider.CalendarContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.domain.SystemCalendarDisclosureLevel
import com.lezi.babylog.domain.SystemCalendarDisclosurePolicy
import com.lezi.babylog.domain.SystemCalendarUpsert
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator / device smoke for [AndroidSystemCalendarPort] against real
 * CalendarContract (ticket 28 residual). Covers grant path, writable target,
 * L1–L3 disclosure content, create/edit/delete, deep link field, and the
 * no-photo-bytes/URI invariant. Interactive OEM calendar UI is not driven.
 */
@RunWith(AndroidJUnit4::class)
class AndroidSystemCalendarPortSmokeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val port = AndroidSystemCalendarPort(context)
    private val createdCalendarIds = mutableListOf<Long>()
    private val createdEventIds = mutableListOf<Long>()

    @Before
    fun grantCalendarPermissions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        // Library instrumented APK package (targetContext) needs the grants.
        val packages = linkedSetOf(
            context.packageName,
            instrumentation.context.packageName,
        )
        for (pkg in packages) {
            runCatching {
                automation.grantRuntimePermission(pkg, Manifest.permission.READ_CALENDAR)
            }
            runCatching {
                automation.grantRuntimePermission(pkg, Manifest.permission.WRITE_CALENDAR)
            }
            runCatching {
                instrumentation.uiAutomation.executeShellCommand(
                    "pm grant $pkg ${Manifest.permission.READ_CALENDAR}",
                ).close()
            }
            runCatching {
                instrumentation.uiAutomation.executeShellCommand(
                    "pm grant $pkg ${Manifest.permission.WRITE_CALENDAR}",
                ).close()
            }
        }
    }

    @After
    fun cleanup() {
        val resolver = context.contentResolver
        createdEventIds.forEach { id ->
            runCatching {
                resolver.delete(
                    ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id),
                    null,
                    null,
                )
            }
        }
        createdCalendarIds.forEach { id ->
            runCatching {
                val uri = ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, id)
                    .buildUpon()
                    .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                    .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
                    .appendQueryParameter(
                        CalendarContract.Calendars.ACCOUNT_TYPE,
                        CalendarContract.ACCOUNT_TYPE_LOCAL,
                    )
                    .build()
                resolver.delete(uri, null, null)
            }
        }
    }

    @Test
    fun writableTargetL1L2L3CrudDeepLinkAndNoPhotoLeak() = runBlocking<Unit> {
        assumeTrue("Calendar permission not granted", port.hasCalendarPermission())

        val calendarId = ensureWritableLocalCalendar()
        assumeTrue("No writable calendar available", calendarId != null)
        val calId = calendarId!!

        assertThat(port.isWritableCalendar(calId)).isTrue()
        assertThat(port.listWritableCalendars().map { it.calendarId }).contains(calId)

        val begin = System.currentTimeMillis() + 3_600_000L
        val planUuid = "smoke-plan-${System.nanoTime()}"

        // L1
        val l1 = SystemCalendarDisclosurePolicy.build(
            level = SystemCalendarDisclosureLevel.EVENT_ONLY,
            babyNickname = "乐乐",
            recordTypeLabel = "配方奶",
            note = "不应出现在 L1",
            photoCount = 3,
            carePlanClientUuid = planUuid,
        )
        val eventId = port.upsertEvent(
            SystemCalendarUpsert(
                calendarId = calId,
                carePlanClientUuid = planUuid,
                beginAtMillis = begin,
                title = l1.title,
                description = l1.description,
                customAppUri = l1.deepLinkUri,
            ),
        )
        assertThat(eventId).isNotNull()
        createdEventIds += eventId!!.toLong()
        val l1Row = readEvent(eventId.toLong())
        assertThat(l1Row.title).isEqualTo(SystemCalendarDisclosurePolicy.L1_TITLE)
        assertThat(l1Row.description.orEmpty()).doesNotContain("不应出现")
        assertThat(l1Row.description.orEmpty()).doesNotContain("照片")
        assertThat(l1Row.description.orEmpty()).doesNotContain("content://")
        assertThat(l1Row.description.orEmpty()).doesNotContain("file://")
        assertThat(l1Row.customAppUri).isNull()

        // L2 edit in place
        val l2 = SystemCalendarDisclosurePolicy.build(
            level = SystemCalendarDisclosureLevel.BABY_AND_TYPE,
            babyNickname = "乐乐",
            recordTypeLabel = "配方奶",
            note = null,
            photoCount = 0,
            carePlanClientUuid = planUuid,
        )
        val sameId = port.upsertEvent(
            SystemCalendarUpsert(
                calendarId = calId,
                carePlanClientUuid = planUuid,
                beginAtMillis = begin + 60_000L,
                title = l2.title,
                description = l2.description,
                existingEventId = eventId,
                customAppUri = l2.deepLinkUri,
            ),
        )
        assertThat(sameId).isEqualTo(eventId)
        val l2Row = readEvent(eventId.toLong())
        assertThat(l2Row.title).isEqualTo("乐乐 · 配方奶")

        // L3 with note + photo count line + deep link; still no photo bytes/URIs.
        val l3 = SystemCalendarDisclosurePolicy.build(
            level = SystemCalendarDisclosureLevel.DETAILS,
            babyNickname = "乐乐",
            recordTypeLabel = "配方奶",
            note = "备注含本地路径 photos/secret.jpg 也不应被拆成 URI 字段",
            photoCount = 2,
            carePlanClientUuid = planUuid,
        )
        assertThat(l3.deepLinkUri).isEqualTo("lezi://care-plan/$planUuid")
        assertThat(l3.description).contains("照片 2 张，打开乐记查看")
        assertThat(l3.description).doesNotContain("content://")
        assertThat(l3.description).doesNotContain("file://")
        val l3Id = port.upsertEvent(
            SystemCalendarUpsert(
                calendarId = calId,
                carePlanClientUuid = planUuid,
                beginAtMillis = begin + 120_000L,
                title = l3.title,
                description = l3.description,
                existingEventId = eventId,
                customAppUri = l3.deepLinkUri,
            ),
        )
        assertThat(l3Id).isEqualTo(eventId)
        val l3Row = readEvent(eventId.toLong())
        assertThat(l3Row.title).isEqualTo("乐乐 · 配方奶")
        assertThat(l3Row.description).contains("照片 2 张，打开乐记查看")
        assertThat(l3Row.description).contains("lezi://care-plan/$planUuid")
        // Note text may be present; provider must not gain attachment/location photo paths.
        assertThat(l3Row.eventLocation).isNull()
        assertThat(l3Row.customAppUri).isEqualTo("lezi://care-plan/$planUuid")
        assertThat(l3Row.description).doesNotContain("content://")
        assertThat(l3Row.description).doesNotContain("file://")

        assertThat(port.eventExists(eventId)).isTrue()
        assertThat(port.deleteEvent(eventId)).isTrue()
        assertThat(port.eventExists(eventId)).isFalse()
        createdEventIds.remove(eventId.toLong())
    }

    @Test
    fun providerFailureDoesNotThrowFromPort() = runBlocking<Unit> {
        assumeTrue("Calendar permission not granted", port.hasCalendarPermission())
        // Non-numeric calendar id → null, not exception (CareLog must keep plan save).
        // Numeric-but-missing ids are OEM-dependent (some AOSP builds still insert).
        val result = port.upsertEvent(
            SystemCalendarUpsert(
                calendarId = "not-a-calendar-id",
                carePlanClientUuid = "fail-plan",
                beginAtMillis = System.currentTimeMillis() + 60_000L,
                title = "乐记 · 护理计划",
                description = null,
            ),
        )
        assertThat(result).isNull()
        assertThat(port.deleteEvent("not-a-number")).isFalse()
        assertThat(port.eventExists("not-a-number")).isFalse()
        // Existing-id update on garbage id also fails closed.
        val updateMissing = port.upsertEvent(
            SystemCalendarUpsert(
                calendarId = "1",
                carePlanClientUuid = "fail-plan-2",
                beginAtMillis = System.currentTimeMillis() + 60_000L,
                title = "乐记 · 护理计划",
                description = null,
                existingEventId = "999999999999",
            ),
        )
        assertThat(updateMissing).isNull()
    }

    private fun ensureWritableLocalCalendar(): String? {
        val existing = runBlocking { port.listWritableCalendars() }.firstOrNull()
        if (existing != null) return existing.calendarId

        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, "lezi-smoke")
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "乐记测试日历")
            put(
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                CalendarContract.Calendars.CAL_ACCESS_OWNER,
            )
            put(CalendarContract.Calendars.OWNER_ACCOUNT, ACCOUNT_NAME)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
        }
        val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
            .appendQueryParameter(
                CalendarContract.Calendars.ACCOUNT_TYPE,
                CalendarContract.ACCOUNT_TYPE_LOCAL,
            )
            .build()
        val inserted = context.contentResolver.insert(uri, values) ?: return null
        val id = ContentUris.parseId(inserted)
        createdCalendarIds += id
        return id.toString()
    }

    private data class EventRow(
        val title: String?,
        val description: String?,
        val eventLocation: String?,
        val customAppUri: String?,
    )

    private fun readEvent(eventId: Long): EventRow {
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        context.contentResolver.query(
            uri,
            arrayOf(
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DESCRIPTION,
                CalendarContract.Events.EVENT_LOCATION,
                CalendarContract.Events.CUSTOM_APP_URI,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            check(cursor.moveToFirst()) { "event $eventId missing" }
            return EventRow(
                title = cursor.getString(0),
                description = cursor.getString(1),
                eventLocation = cursor.getString(2),
                customAppUri = cursor.getString(3),
            )
        } ?: error("query failed for event $eventId")
    }

    companion object {
        private const val ACCOUNT_NAME = "lezi-smoke@local"
    }
}
