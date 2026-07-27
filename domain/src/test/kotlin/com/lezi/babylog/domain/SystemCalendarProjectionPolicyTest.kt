package com.lezi.babylog.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SystemCalendarProjectionPolicyTest {
    @Test
    fun unsyncedOnlyWhenUserEnabledAndConfigured() {
        assertThat(
            evaluateCarePlanSystemCalendarUnsynced(
                systemCalendarEnabled = false,
                systemCalendarId = "cal-1",
                hasPermission = false,
                targetWritable = false,
                mappedEventId = null,
                eventExists = false,
            ),
        ).isFalse()
        assertThat(
            evaluateCarePlanSystemCalendarUnsynced(
                systemCalendarEnabled = true,
                systemCalendarId = null,
                hasPermission = true,
                targetWritable = true,
                mappedEventId = null,
                eventExists = false,
            ),
        ).isFalse()
    }

    @Test
    fun permissionRevokeOrVanishedTargetOrMissingEventIsUnsynced() {
        assertThat(
            evaluateCarePlanSystemCalendarUnsynced(
                systemCalendarEnabled = true,
                systemCalendarId = "cal-1",
                hasPermission = false,
                targetWritable = false,
                mappedEventId = "evt-1",
                eventExists = true,
            ),
        ).isTrue()
        assertThat(
            evaluateCarePlanSystemCalendarUnsynced(
                systemCalendarEnabled = true,
                systemCalendarId = "cal-1",
                hasPermission = true,
                targetWritable = false,
                mappedEventId = "evt-1",
                eventExists = true,
            ),
        ).isTrue()
        assertThat(
            evaluateCarePlanSystemCalendarUnsynced(
                systemCalendarEnabled = true,
                systemCalendarId = "cal-1",
                hasPermission = true,
                targetWritable = true,
                mappedEventId = null,
                eventExists = false,
            ),
        ).isTrue()
        assertThat(
            evaluateCarePlanSystemCalendarUnsynced(
                systemCalendarEnabled = true,
                systemCalendarId = "cal-1",
                hasPermission = true,
                targetWritable = true,
                mappedEventId = "evt-1",
                eventExists = false,
            ),
        ).isTrue()
    }

    @Test
    fun healthyProjectionIsNotUnsynced() {
        assertThat(
            evaluateCarePlanSystemCalendarUnsynced(
                systemCalendarEnabled = true,
                systemCalendarId = "cal-1",
                hasPermission = true,
                targetWritable = true,
                mappedEventId = "evt-1",
                eventExists = true,
            ),
        ).isFalse()
    }

    @Test
    fun presentEventIsStillUnsyncedUntilItsReminderGenerationIsReady() {
        assertThat(
            evaluateCarePlanSystemCalendarUnsynced(
                systemCalendarEnabled = true,
                systemCalendarId = "cal-1",
                hasPermission = true,
                targetWritable = true,
                mappedEventId = "evt-1",
                eventExists = true,
                reminderReady = false,
                projectionPending = false,
            ),
        ).isTrue()
        assertThat(
            evaluateCarePlanSystemCalendarUnsynced(
                systemCalendarEnabled = true,
                systemCalendarId = "cal-1",
                hasPermission = true,
                targetWritable = true,
                mappedEventId = "evt-1",
                eventExists = true,
                reminderReady = true,
                projectionPending = true,
            ),
        ).isTrue()
    }

    @Test
    fun disclosureLevelFromStoredCoercesIntoValidGrades() {
        assertThat(SystemCalendarDisclosureLevel.fromStored(1))
            .isEqualTo(SystemCalendarDisclosureLevel.EVENT_ONLY)
        assertThat(SystemCalendarDisclosureLevel.fromStored(2))
            .isEqualTo(SystemCalendarDisclosureLevel.BABY_AND_TYPE)
        assertThat(SystemCalendarDisclosureLevel.fromStored(3))
            .isEqualTo(SystemCalendarDisclosureLevel.DETAILS)
        // coerceIn(1, 3): below → L1, above → L3; default unset prefs use 2.
        assertThat(SystemCalendarDisclosureLevel.fromStored(0))
            .isEqualTo(SystemCalendarDisclosureLevel.EVENT_ONLY)
        assertThat(SystemCalendarDisclosureLevel.fromStored(99))
            .isEqualTo(SystemCalendarDisclosureLevel.DETAILS)
    }

    @Test
    fun l1TitleIsLeziEventOnlyWithoutDescriptionOrDeepLink() {
        val content = SystemCalendarDisclosurePolicy.build(
            level = SystemCalendarDisclosureLevel.EVENT_ONLY,
            babyNickname = "年年",
            recordTypeLabel = "配方奶",
            note = "含敏感备注",
            photoCount = 3,
            carePlanClientUuid = "plan-1",
        )
        assertThat(content.title).isEqualTo("乐记 · 护理计划")
        assertThat(content.description).isNull()
        assertThat(content.deepLinkUri).isNull()
        assertThat(SystemCalendarDisclosurePolicy.containsForbiddenPhotoLeak(content.title))
            .isFalse()
    }

    @Test
    fun l2TitleIsBabyNicknameAndTypeWithoutDescription() {
        val content = SystemCalendarDisclosurePolicy.build(
            level = SystemCalendarDisclosureLevel.BABY_AND_TYPE,
            babyNickname = "年年",
            recordTypeLabel = "配方奶",
            note = "备注",
            photoCount = 2,
            carePlanClientUuid = "plan-2",
        )
        assertThat(content.title).isEqualTo("年年 · 配方奶")
        assertThat(content.description).isNull()
        assertThat(content.deepLinkUri).isNull()
    }

    @Test
    fun l3IncludesNotePhotoCountTextAndStableDeepLinkNeverPhotoBytes() {
        val content = SystemCalendarDisclosurePolicy.build(
            level = SystemCalendarDisclosureLevel.DETAILS,
            babyNickname = "年年",
            recordTypeLabel = "配方奶",
            note = "补充维D",
            photoCount = 2,
            carePlanClientUuid = "plan-uuid-xyz",
        )
        assertThat(content.title).isEqualTo("年年 · 配方奶")
        assertThat(content.description).isNotNull()
        assertThat(content.description).contains("补充维D")
        assertThat(content.description).contains("照片 2 张，打开乐记查看")
        assertThat(content.description).contains("lezi://care-plan/plan-uuid-xyz")
        assertThat(content.deepLinkUri).isEqualTo("lezi://care-plan/plan-uuid-xyz")
        // Privacy: no photo bytes / local URIs / content paths in projected text.
        assertThat(SystemCalendarDisclosurePolicy.containsForbiddenPhotoLeak(content.title))
            .isFalse()
        assertThat(SystemCalendarDisclosurePolicy.containsForbiddenPhotoLeak(content.description))
            .isFalse()
        assertThat(content.description).doesNotContain("content://")
        assertThat(content.description).doesNotContain("file://")
        assertThat(content.description).doesNotContain("/storage/")
    }

    @Test
    fun l3WithoutPhotosOmitsPhotoLineButKeepsDeepLink() {
        val content = SystemCalendarDisclosurePolicy.build(
            level = SystemCalendarDisclosureLevel.DETAILS,
            babyNickname = "宝宝",
            recordTypeLabel = "尿尿",
            note = null,
            photoCount = 0,
            carePlanClientUuid = "p-empty",
        )
        assertThat(content.description).isEqualTo("lezi://care-plan/p-empty")
        assertThat(content.description).doesNotContain("照片")
    }
}
