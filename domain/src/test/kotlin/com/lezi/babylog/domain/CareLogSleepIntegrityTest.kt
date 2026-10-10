package com.lezi.babylog.domain

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CareLogSleepIntegrityTest {
    private val payload = """{"is_nap":false,"anomaly_flag":false}"""
    private val hour = 3_600_000L
    private val start = 1_700_000_000_000L

    @get:org.junit.Rule
    val photoFiles = org.junit.rules.TemporaryFolder()

    @Test
    fun movingAcrossMidnightAndOriginalWakePreservesOneObservationInBothProjectionBranches() = runTest {
        val midnight = java.time.LocalDate.of(2024, 6, 2)
            .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        for (effective in listOf(false, true)) {
            val care = Fakes().careLog()
            val baby = care.createBaby(CreateBabyInput(nickname = "测试宝宝", birthdayEpochDay = 1))
            val id = care.confirmSleep(baby, null, midnight - 2 * hour, midnight - hour,
                null, payload, nowMillis = midnight + 6 * hour)
            val original = care.projectSleepRecord(id)!!.observations.single().clientUuid
            if (effective) care.selectEffectiveWakeObservation(care.getRecord(id)!!.clientUuid, original)
            for ((from, to) in listOf(
                midnight + hour to midnight + 2 * hour,
                midnight - hour to midnight + hour,
                midnight - 3 * hour to midnight - 2 * hour,
            )) {
                care.updateRecord(id, from, to, "移动区间", payload, nowMillis = midnight + 6 * hour)
                val projection = care.projectSleepRecord(id)!!
                assertThat(projection.interval.startTimestamp).isEqualTo(from)
                assertThat(projection.interval.endTimestamp).isEqualTo(to)
                assertThat(projection.observations.map { it.clientUuid }).containsExactly(original)
                assertThat(projection.observations.single().wakeTimestamp).isEqualTo(to)
            }
        }
    }

    @Test
    fun rootNoteAndPhotoEditsPreserveWholeWakeRevisionAndMediaAcrossOwnershipBranches() = runTest {
        for (foreign in listOf(false, true)) for (effective in listOf(false, true)) {
            val fakes = Fakes()
            fakes.wireTransactionalSnapshots()
            val care = fakes.careLog()
            val baby = care.createBaby(CreateBabyInput(nickname = "测试宝宝", birthdayEpochDay = 1))
            val id = care.sleepDown(baby, start, nowMillis = start)
            val wakePhoto = photoFiles.syntheticPhoto("wake-$foreign-$effective.png")
            val rootPhoto = photoFiles.syntheticPhoto("root-$foreign-$effective.png")
            care.recordWakeObservation(babyId = baby, at = start + hour, note = "观察备注",
                photoLocalPaths = listOf(wakePhoto), sleepRecordId = id,
                clientUuid = "wake", nowMillis = start + hour)
            if (effective) care.selectEffectiveWakeObservation(care.getRecord(id)!!.clientUuid, "wake")
            if (foreign) fakes.wakeObservations.update(
                fakes.wakeObservations.getByClientUuid("wake")!!.copy(
                    observerMembershipId = "other-observer", syncDirty = false,
                ),
            )
            val wakeBefore = fakes.wakeObservations.itemsSnapshot()
            val mediaBefore = fakes.media.listAllIncludingDeleted().filter { it.wakeObservationId != null }
            for (photos in listOf(null, listOf(rootPhoto))) {
                care.updateRecord(id, start, start + hour, "根备注", payload,
                    photoLocalPaths = photos, nowMillis = start + 2 * hour)
                assertThat(care.getRecord(id)!!.note).isEqualTo("根备注")
                assertThat(care.listRecordPhotoPaths(id)).containsExactlyElementsIn(photos.orEmpty())
                assertThat(fakes.wakeObservations.itemsSnapshot()).containsExactlyElementsIn(wakeBefore)
                assertThat(fakes.media.listAllIncludingDeleted().filter { it.wakeObservationId != null })
                    .containsExactlyElementsIn(mediaBefore)
            }
            assertThat(care.canEditWakeObservation("wake")).isEqualTo(!foreign)
            if (foreign) {
                assertThat(runCatching {
                    care.updateWakeObservation("wake", start + 2 * hour, "不允许", nowMillis = start + 2 * hour)
                }.exceptionOrNull()).isInstanceOf(RecordPermissionException::class.java)
                assertThat(fakes.wakeObservations.itemsSnapshot()).containsExactlyElementsIn(wakeBefore)
                assertThat(fakes.media.listAllIncludingDeleted().filter { it.wakeObservationId != null })
                    .containsExactlyElementsIn(mediaBefore)
            }
        }
    }

    @Test
    fun searchTracksOpenWithdrawnAndSelectedWakeAndHidesSourceRoots() = runTest {
        for (source in listOf(false, true)) {
            val fakes = Fakes()
            val care = fakes.careLog()
            val baby = care.createBaby(CreateBabyInput(nickname = "测试宝宝", birthdayEpochDay = 1))
            val id = care.sleepDown(baby, start, nowMillis = start)
            val uuid = care.getRecord(id)!!.clientUuid
            if (source) fakes.sourceRelations.applyPullSummary(
                relationId = "source-relation", recordClientUuid = uuid, role = "source",
                peerIds = listOf("display-root"), observedAt = start,
            )
            suspend fun assertSearch(ongoing: Boolean, duration: String?) {
                assertThat(care.search(baby, "进行中").map { it.id })
                    .containsExactlyElementsIn(if (ongoing && !source) listOf(id) else emptyList<Long>())
                for (term in listOf("1h", "2h")) {
                    assertThat(care.search(baby, term).map { it.id })
                        .containsExactlyElementsIn(if (duration == term && !source) listOf(id) else emptyList<Long>())
                }
            }
            assertSearch(true, null)
            care.recordWakeObservation(babyId = baby, at = start + hour,
                sleepRecordId = id, clientUuid = "first-wake", nowMillis = start + 2 * hour)
            assertSearch(false, "1h")
            care.withdrawWakeObservation("first-wake")
            assertSearch(true, null)
            care.recordWakeObservation(babyId = baby, at = start + hour,
                sleepRecordId = id, clientUuid = "one-hour", nowMillis = start + 2 * hour)
            care.recordWakeObservation(babyId = baby, at = start + 2 * hour,
                sleepRecordId = id, clientUuid = "two-hours", nowMillis = start + 2 * hour)
            care.selectEffectiveWakeObservation(uuid, "two-hours")
            assertSearch(false, "2h")
            care.selectEffectiveWakeObservation(uuid, "one-hour")
            assertSearch(false, "1h")
        }
    }

    @Test
    fun movingClosedSleepLaterThenEarlierKeepsRequestedIntervalAndObservationIdentity() = runTest {
        val care = Fakes().careLog()
        val baby = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))
        val id = care.confirmSleep(
            babyId = baby, expectedOpenSleepId = null, timestamp = start,
            endTimestamp = start + hour, note = "睡下", payloadJson = payload,
            nowMillis = start + 10 * hour,
        )
        val original = care.projectSleepRecord(id)!!.observations.single()
        care.updateRecord(id, start + 2 * hour, start + 3 * hour, "睡下", payload,
            nowMillis = start + 10 * hour)
        assertThat(care.projectSleepRecord(id)!!.interval.endTimestamp).isEqualTo(start + 3 * hour)
        assertThat(care.projectSleepRecord(id)!!.observations.single().clientUuid)
            .isEqualTo(original.clientUuid)
        care.updateRecord(id, start - 2 * hour, start - hour, "睡下", payload,
            nowMillis = start + 10 * hour)
        assertThat(care.projectSleepRecord(id)!!.interval.endTimestamp).isEqualTo(start - hour)
        assertThat(care.projectSleepRecord(id)!!.observations.single().clientUuid)
            .isEqualTo(original.clientUuid)
    }

    @Test
    fun editingSleepNotePreservesIndependentEffectiveWakeContent() = runTest {
        val care = Fakes().careLog()
        val baby = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))
        val id = care.sleepDown(baby, start, nowMillis = start)
        care.recordWakeObservation(
            babyId = baby, at = start + hour, note = "醒来时很开心",
            sleepRecordId = id, clientUuid = "independent-wake", nowMillis = start + hour,
        )
        val sleep = care.getRecord(id)!!
        care.selectEffectiveWakeObservation(sleep.clientUuid, "independent-wake")
        val before = care.getWakeObservation("independent-wake")
        care.updateRecord(id, start, start + hour, "只改睡下备注", payload,
            nowMillis = start + hour)
        assertThat(care.getWakeObservation("independent-wake")).isEqualTo(before)
        assertThat(care.getRecord(id)!!.note).isEqualTo("只改睡下备注")
    }

    @Test
    fun failedBackfillWakeLeavesNoHalfSleepAndRetryClosesOneFact() = runTest {
        val fakes = Fakes()
        fakes.wireTransactionalSnapshots()
        val care = fakes.careLog()
        val baby = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))
        val uuid = "c03a84ef-2c72-4dc0-8e63-df013fc67210"
        fakes.wakeObservations.upsertFailure = IllegalStateException("disk write failed")
        val failure = runCatching {
            care.confirmSleep(baby, null, start, start + hour, "补记", payload,
                nowMillis = start + hour, clientUuid = uuid)
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(care.getRecordByClientUuid(uuid)).isNull()
        fakes.wakeObservations.upsertFailure = null
        val id = care.confirmSleep(baby, null, start, start + hour, "补记", payload,
            nowMillis = start + hour, clientUuid = uuid)
        assertThat(care.projectSleepRecord(id)!!.interval.endTimestamp).isEqualTo(start + hour)
        assertThat(care.projectSleepRecord(id)!!.observations).hasSize(1)
    }

    @Test
    fun failedWakeCorrectionPreservesOriginalSleepStart() = runTest {
        val fakes = Fakes()
        fakes.wireTransactionalSnapshots()
        val care = fakes.careLog()
        val baby = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))
        val id = care.sleepDown(baby, start, nowMillis = start)
        fakes.wakeObservations.upsertFailure = IllegalStateException("disk write failed")
        assertThat(runCatching {
            care.confirmSleep(baby, id, start - hour, start + hour, "醒来", payload,
                nowMillis = start + hour)
        }.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(care.getRecord(id)!!.timestamp).isEqualTo(start)
        assertThat(care.projectSleepRecord(id)!!.observations).isEmpty()
    }

    @Test
    fun failedAnomalyWakeDoesNotLeaveAnOpenSleep() = runTest {
        val fakes = Fakes()
        fakes.wireTransactionalSnapshots()
        val care = fakes.careLog()
        val baby = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))
        fakes.wakeObservations.upsertFailure = IllegalStateException("disk write failed")
        assertThat(runCatching {
            care.sleepUp(baby, start + hour, nowMillis = start + hour)
        }.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(care.observeOpenSleep(baby).first()).isNull()
    }

    @Test
    fun withdrawingOwnWakeDoesNotReviseAnotherAuthorsSleep() = runTest {
        val fakes = Fakes(RecordingSyncPort(
            membershipId = "observer", familyId = "family", deviceId = "device",
            role = com.lezi.babylog.sync.session.FamilyRole.Member,
        ))
        val care = fakes.careLog()
        val baby = fakes.seedFamilyAuthorityBaby()
        val id = care.sleepDown(baby, start, nowMillis = start)
        care.recordWakeObservation(babyId = baby, at = start + hour,
            sleepRecordId = id, clientUuid = "own-wake", nowMillis = start + hour)
        care.selectEffectiveWakeObservation(care.getRecord(id)!!.clientUuid, "own-wake")
        // Boundary fixture represents the foreign author's accepted root arriving by pull.
        fakes.records.update(fakes.records.get(id)!!.copy(
            createdByMembershipId = "sleep-author", syncDirty = false,
        ))
        val before = care.getRecord(id)!!
        care.withdrawWakeObservation("own-wake")
        val after = care.getRecord(id)!!
        assertThat(after.updatedAt).isEqualTo(before.updatedAt)
        assertThat(after.syncDirty).isFalse()
        assertThat(after.effectiveWakeObservationClientUuid)
            .isEqualTo(before.effectiveWakeObservationClientUuid)
        assertThat(care.getWakeObservation("own-wake")!!.withdrawn).isTrue()
        assertThat(care.projectSleepRecord(id)!!.interval.isOpen).isTrue()
    }

    @Test
    fun convertingClosedSleepLoserPreservesProjectedEndAndWakeEvidenceAlongsideAnotherOpenSleep() = runTest {
        for (effective in listOf(false, true)) {
            val fakes = Fakes(RecordingSyncPort(
                membershipId = "owner", familyId = "family", deviceId = "device",
                role = com.lezi.babylog.sync.session.FamilyRole.Owner,
            ))
            fakes.wireTransactionalSnapshots()
            val care = fakes.careLog()
            val baby = fakes.seedFamilyAuthorityBaby()
            val id = care.sleepDown(baby, start, nowMillis = start)
            care.recordWakeObservation(babyId = baby, at = start + hour,
                note = "原醒来备注", photoLocalPaths = listOf("wake.jpg"),
                sleepRecordId = id, clientUuid = "source-wake", nowMillis = start + hour)
            val sourceUuid = care.getRecord(id)!!.clientUuid
            if (effective) care.selectEffectiveWakeObservation(sourceUuid, "source-wake")
            val sourceProjection = care.projectSleepRecord(id)
            fakes.fulfillmentCandidates.upsert(
                com.lezi.babylog.core.database.FulfillmentCandidateEntity(
                    clientUuid = "loser", carePlanClientUuid = "plan", recordClientUuid = sourceUuid,
                    confirmedAt = start, updatedAt = start,
                    adoptionStatus = com.lezi.babylog.core.model.FulfillmentAdoptionStatus.CONFLICT_NOT_ADOPTED,
                ),
            )
            val open = care.sleepDown(baby, start + 2 * hour, nowMillis = start + 2 * hour)
            val copied = care.convertConflictNotAdoptedToIndependentRecord("loser", start + 3 * hour)
            val copy = care.projectSleepRecord(copied)!!
            assertThat(copy.interval.startTimestamp).isEqualTo(start)
            assertThat(copy.interval.endTimestamp).isEqualTo(start + hour)
            assertThat(copy.interval.endSource).isEqualTo(sourceProjection!!.interval.endSource)
            assertThat(copy.observations.single().note).isEqualTo("原醒来备注")
            assertThat(copy.observations.single().photoLocalPaths).containsExactly("wake.jpg")
            assertThat(copy.observations.single().clientUuid).isNotEqualTo("source-wake")
            assertThat(care.getWakeObservation("source-wake"))
                .isEqualTo(sourceProjection!!.observations.single())
            assertThat(care.projectSleepRecord(open)!!.interval.isOpen).isTrue()
            assertThat(care.convertConflictNotAdoptedToIndependentRecord("loser", start + 4 * hour))
                .isEqualTo(copied)
        }
    }

    @Test
    fun searchMatchesProjectedSleepDurationAndExcludesClosedSleepFromOngoing() = runTest {
        val care = Fakes().careLog()
        val baby = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))
        val id = care.confirmSleep(baby, null, start, start + hour, null, payload,
            nowMillis = start + hour)
        assertThat(care.search(baby, "进行中")).isEmpty()
        assertThat(care.search(baby, "1h").map { it.id }).containsExactly(id)
    }

    @Test
    fun rootOnlyEditPreservesForeignEffectiveWakeWithoutDemandingObserverPermission() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val baby = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))
        val id = care.sleepDown(baby, start, nowMillis = start)
        care.recordWakeObservation(babyId = baby, at = start + hour, note = "独立观察",
            sleepRecordId = id, clientUuid = "foreign-wake", nowMillis = start + hour)
        care.selectEffectiveWakeObservation(care.getRecord(id)!!.clientUuid, "foreign-wake")
        fakes.wakeObservations.update(fakes.wakeObservations.getByClientUuid("foreign-wake")!!
            .copy(observerMembershipId = "other", syncDirty = false))
        val before = care.getWakeObservation("foreign-wake")
        care.updateRecord(id, start, start + hour, "新的睡下备注", payload,
            nowMillis = start + hour)
        assertThat(care.getWakeObservation("foreign-wake")).isEqualTo(before)
        assertThat(care.getRecord(id)!!.note).isEqualTo("新的睡下备注")
    }
}
