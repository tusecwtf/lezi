package com.lezi.babylog.domain

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.FulfillmentCandidateEntity
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.SleepEndSource
import com.lezi.babylog.core.model.projectSleepInterval
import com.lezi.babylog.domain.carelog.conflictResolverSelectablePaths
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class CareLogWakeObservationTest {
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    @Test
    fun sleepUp_createsWakeObservation_withoutRewritingSleepEnd() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))
        val start = 1_700_000_000_000L
        val openId = care.sleepDown(babyId, start)
        val wakeAt = start + 90 * 60_000L

        val returnedId = care.sleepUp(babyId, wakeAt, nowMillis = wakeAt)

        assertThat(returnedId).isEqualTo(openId)
        val entity = fakes.records.get(openId)!!
        assertThat(entity.endTimestamp).isNull()
        assertThat(entity.effectiveWakeObservationClientUuid).isNull()
        val wakes = care.listWakeObservations(entity.clientUuid)
        assertThat(wakes).hasSize(1)
        assertThat(wakes.single().wakeTimestamp).isEqualTo(wakeAt)
        assertThat(wakes.single().withdrawn).isFalse()
        // Domain read projects provisional end for summary/timeline consumers.
        assertThat(care.getRecord(openId)!!.endTimestamp).isEqualTo(wakeAt)
        assertThat(care.observeOpenSleep(babyId).first()).isNull()
        val day = java.time.Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
        assertThat(care.daySummary(babyId, day, zone, now = wakeAt + 1).sleepMinutes).isEqualTo(90)
    }

    @Test
    fun wakeOnlyCreateEditSelectAndWithdrawReemitSummaryProjection() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))
        val start = 1_700_000_000_000L
        val sleepId = care.sleepDown(babyId, start)
        val sleepUuid = fakes.records.get(sleepId)!!.clientUuid
        val day = java.time.Instant.ofEpochMilli(start).atZone(zone).toLocalDate()

        suspend fun awaitEnd(expected: Long, mutate: suspend () -> Unit) {
            val emission = async(start = CoroutineStart.UNDISPATCHED) {
                care.observeDayRecords(babyId, day, zone)
                    .first { rows -> rows.singleOrNull()?.endTimestamp == expected }
            }
            runCurrent()
            mutate()
            assertThat(emission.await().single().endTimestamp).isEqualTo(expected)
        }

        awaitEnd(start + 60_000L) {
            care.recordWakeObservation(
                babyId = babyId,
                at = start + 60_000L,
                nowMillis = start + 60_000L,
                sleepRecordId = sleepId,
                clientUuid = "wake-primary",
            )
        }
        awaitEnd(start + 90_000L) {
            care.updateWakeObservation(
                clientUuid = "wake-primary",
                wakeTimestamp = start + 90_000L,
                note = "edited",
                nowMillis = start + 90_000L,
            )
        }
        awaitEnd(start + 30_000L) {
            care.recordWakeObservation(
                babyId = babyId,
                at = start + 30_000L,
                nowMillis = start + 90_000L,
                sleepRecordId = sleepId,
                clientUuid = "wake-earlier",
            )
        }
        awaitEnd(start + 90_000L) {
            care.selectEffectiveWakeObservation(sleepUuid, "wake-primary")
        }
        awaitEnd(start + 30_000L) {
            care.withdrawWakeObservation("wake-primary")
        }
    }

    @Test
    fun conflictNotAdoptedOpenSleepIsExcludedFromCanonicalOpenSurface() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))
        fakes.records.upsert(
            RecordEntity(
                clientUuid = "sleep-not-adopted",
                babyId = babyId,
                type = RecordType.SLEEP.key,
                timestamp = 1_000L,
                payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
                updatedAt = 1_000L,
            ),
        )
        fakes.fulfillmentCandidates.upsert(
            FulfillmentCandidateEntity(
                clientUuid = "candidate-not-adopted",
                carePlanClientUuid = "plan-conflict",
                recordClientUuid = "sleep-not-adopted",
                confirmedAt = 1_000L,
                adoptionStatus = "conflict_not_adopted",
                updatedAt = 1_000L,
            ),
        )

        assertThat(care.observeOpenSleep(babyId).first()).isNull()
        val accepted = care.sleepDown(babyId, at = 2_000L)
        assertThat(fakes.records.get(accepted)?.clientUuid).isNotEqualTo("sleep-not-adopted")
    }

    @Test
    fun overlappingOpenSleeps_remain_andLatestIsWakeTarget() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))
        val t0 = 1_700_000_000_000L
        fakes.records.upsert(
            RecordEntity(
                clientUuid = "sleep-stale",
                babyId = babyId,
                type = RecordType.SLEEP.key,
                timestamp = t0,
                endTimestamp = null,
                note = null,
                payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
                updatedAt = t0,
            ),
        )
        fakes.records.upsert(
            RecordEntity(
                clientUuid = "sleep-latest",
                babyId = babyId,
                type = RecordType.SLEEP.key,
                timestamp = t0 + 60 * 60_000L,
                endTimestamp = null,
                note = null,
                payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
                updatedAt = t0 + 60 * 60_000L,
            ),
        )

        val closedId = care.sleepUp(babyId, t0 + 2 * 60 * 60_000L)

        val all = fakes.records.listForBaby(babyId)
        assertThat(all).hasSize(2)
        val stale = all.single { it.clientUuid == "sleep-stale" }
        val latest = all.single { it.clientUuid == "sleep-latest" }
        // Older open is NOT auto-closed.
        assertThat(stale.endTimestamp).isNull()
        assertThat(stale.effectiveWakeObservationClientUuid).isNull()
        assertThat(care.listWakeObservations("sleep-stale")).isEmpty()
        // Latest received the wake observation.
        assertThat(latest.endTimestamp).isNull()
        assertThat(closedId).isEqualTo(latest.id)
        assertThat(care.listWakeObservations("sleep-latest")).hasSize(1)
        val staleProj = care.projectSleepRecord(stale.id)!!
        assertThat(staleProj.interval.isOpen).isTrue()
        // After latest receives a wake, only the older start remains open — it becomes
        // the wake shortcut target (overlap-pending only while multiple opens coexist).
        assertThat(staleProj.interval.isOverlapPending).isFalse()
        assertThat(care.observeOpenSleep(babyId).first()?.clientUuid).isEqualTo("sleep-stale")

        // While both still open, older is overlap-pending.
        val bothOpen = projectSleepInterval(
            sleepClientUuid = "sleep-stale",
            startTimestamp = t0,
            peerOpenSleepStarts = listOf("sleep-latest" to (t0 + 60 * 60_000L)),
        )
        assertThat(bothOpen.isOverlapPending).isTrue()
    }

    @Test
    fun multipleWakes_provisionalEarliest_selectEffective_withdraw() = runTest {
        val momSync = RecordingSyncPort(
            membershipId = "m-mom",
            role = com.lezi.babylog.sync.session.FamilyRole.Owner,
            familyId = "fam-1",
            deviceId = "dev-mom",
        )
        val mom = Fakes(momSync)
        mom.wireTransactionalSnapshots()
        val care = mom.careLog()
        val babyId = mom.seedFamilyAuthorityBaby()
        val start = 1_700_000_000_000L
        val openId = care.sleepDown(babyId, start, nowMillis = start)
        val sleepUuid = mom.records.get(openId)!!.clientUuid

        care.recordWakeObservation(
            babyId = babyId,
            at = start + 60 * 60_000L,
            note = "妈",
            nowMillis = start + 60 * 60_000L,
            sleepRecordId = openId,
            clientUuid = "wake-mom",
        )
        // Second observation via another membership (dad device shares room for unit test).
        mom.wakeObservations.upsert(
            com.lezi.babylog.core.database.causal.WakeObservationEntity(
                clientUuid = "wake-dad",
                sleepRecordClientUuid = sleepUuid,
                wakeTimestamp = start + 90 * 60_000L,
                observerMembershipId = "m-dad",
                note = "爸",
                withdrawn = false,
                updatedAt = start + 90 * 60_000L,
            ),
        )

        val projection = care.projectSleepRecord(openId)!!
        assertThat(projection.interval.endSource).isEqualTo(SleepEndSource.PROVISIONAL)
        assertThat(projection.interval.isProvisional).isTrue()
        assertThat(projection.interval.endTimestamp).isEqualTo(start + 60 * 60_000L)
        assertThat(projection.observations.map { it.clientUuid })
            .containsExactly("wake-mom", "wake-dad")
            .inOrder()

        care.selectEffectiveWakeObservation(sleepUuid, "wake-dad")
        val confirmed = care.projectSleepRecord(openId)!!
        assertThat(confirmed.interval.endSource).isEqualTo(SleepEndSource.EFFECTIVE)
        assertThat(confirmed.interval.endTimestamp).isEqualTo(start + 90 * 60_000L)
        assertThat(confirmed.observations).hasSize(2)
        assertThat(mom.records.get(openId)!!.effectiveWakeObservationClientUuid)
            .isEqualTo("wake-dad")

        // Observer withdraws own observation; effective clears to provisional on remaining.
        care.withdrawWakeObservation("wake-mom")
        assertThat(care.getWakeObservation("wake-mom")!!.withdrawn).isTrue()
        // Clear effective that still points at live dad wake — keep dad as effective.
        assertThat(care.projectSleepRecord(openId)!!.interval.endObservationClientUuid)
            .isEqualTo("wake-dad")

        care.selectEffectiveWakeObservation(sleepUuid, null)
        val afterClear = care.projectSleepRecord(openId)!!
        assertThat(afterClear.interval.endSource).isEqualTo(SleepEndSource.PROVISIONAL)
        assertThat(afterClear.interval.endObservationClientUuid).isEqualTo("wake-dad")
        assertThat(afterClear.observations.filter { !it.withdrawn }).hasSize(1)

        // Re-select dad, then withdraw: effective must clear for explicit unconfirmed state.
        care.selectEffectiveWakeObservation(sleepUuid, "wake-dad")
        assertThat(mom.records.get(openId)!!.effectiveWakeObservationClientUuid)
            .isEqualTo("wake-dad")
        // Owner withdraw path is blocked for non-observer content — use observer membership.
        // Here mom is Owner but not the dad observation observer; use select null instead
        // then re-select mom's wake after un-withdraw is N/A. Withdraw dad as Owner is
        // forbidden by ACL; verify select null already unconfirmed:
        care.selectEffectiveWakeObservation(sleepUuid, null)
        assertThat(mom.records.get(openId)!!.effectiveWakeObservationClientUuid).isNull()
    }

    @Test
    fun withdrawEffectiveObservation_clearsEffectiveOnSleep() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))
        val start = 1_700_000_000_000L
        val openId = care.sleepDown(babyId, start)
        val sleepUuid = fakes.records.get(openId)!!.clientUuid
        care.recordWakeObservation(
            babyId = babyId,
            at = start + 60 * 60_000L,
            nowMillis = start + 60 * 60_000L,
            sleepRecordId = openId,
            clientUuid = "wake-self",
        )
        care.selectEffectiveWakeObservation(sleepUuid, "wake-self")
        assertThat(fakes.records.get(openId)!!.effectiveWakeObservationClientUuid)
            .isEqualTo("wake-self")

        care.withdrawWakeObservation("wake-self")

        assertThat(care.getWakeObservation("wake-self")!!.withdrawn).isTrue()
        assertThat(fakes.records.get(openId)!!.effectiveWakeObservationClientUuid).isNull()
        val projection = care.projectSleepRecord(openId)!!
        assertThat(projection.interval.isOpen || projection.interval.isProvisional).isTrue()
        assertThat(projection.interval.endSource).isNotEqualTo(SleepEndSource.EFFECTIVE)
    }

    @Test
    fun preStartWake_rejected() = runTest {
        val care = Fakes().careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))
        val start = 1_700_000_000_000L
        val openId = care.sleepDown(babyId, start)
        val failure = runCatching {
            care.sleepUp(babyId, start - 1L, nowMillis = start)
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(care.listWakeObservations(care.getRecord(openId)!!.clientUuid)).isEmpty()
        assertThat(care.observeOpenSleep(babyId).first()).isNotNull()
    }

    @Test
    fun nonAuthorCannotSelectEffective_orEditOthersWake() = runTest {
        val momSync = RecordingSyncPort(
            membershipId = "m-mom",
            role = com.lezi.babylog.sync.session.FamilyRole.Member,
            familyId = "fam-1",
            deviceId = "dev-mom",
        )
        val mom = Fakes(momSync)
        mom.wireTransactionalSnapshots()
        val momCare = mom.careLog()
        val babyId = mom.seedFamilyAuthorityBaby()
        val start = 1_700_000_000_000L
        val openId = momCare.sleepDown(babyId, start)
        val sleepUuid = mom.records.get(openId)!!.clientUuid
        momCare.recordWakeObservation(
            babyId = babyId,
            at = start + 30 * 60_000L,
            nowMillis = start + 30 * 60_000L,
            sleepRecordId = openId,
            clientUuid = "wake-mom",
        )

        val dadSync = RecordingSyncPort(
            membershipId = "m-dad",
            role = com.lezi.babylog.sync.session.FamilyRole.Member,
            familyId = "fam-1",
            deviceId = "dev-dad",
        )
        val dad = Fakes(dadSync)
        dad.wireTransactionalSnapshots()
        dad.babies.upsert(mom.babies.get(babyId)!!)
        dad.records.upsert(mom.records.get(openId)!!)
        dad.wakeObservations.upsert(mom.wakeObservations.getByClientUuid("wake-mom")!!)
        val dadCare = dad.careLog()

        assertThat(
            runCatching {
                dadCare.selectEffectiveWakeObservation(sleepUuid, "wake-mom")
            }.exceptionOrNull(),
        ).isInstanceOf(RecordPermissionException::class.java)
        assertThat(
            runCatching {
                dadCare.updateWakeObservation(
                    clientUuid = "wake-mom",
                    wakeTimestamp = start + 40 * 60_000L,
                    note = "hijack",
                    nowMillis = start + 40 * 60_000L,
                )
            }.exceptionOrNull(),
        ).isInstanceOf(RecordPermissionException::class.java)
        // Dad may still add his own observation.
        dadCare.recordWakeObservation(
            babyId = babyId,
            at = start + 45 * 60_000L,
            nowMillis = start + 45 * 60_000L,
            sleepRecordId = openId,
            clientUuid = "wake-dad",
        )
        assertThat(dadCare.getWakeObservation("wake-dad")!!.observerMembershipId)
            .isEqualTo("m-dad")
    }

    @Test
    fun conflictResolver_hidesAutoMergedPaths() {
        assertThat(
            conflictResolverSelectablePaths(
                conflictingPaths = listOf("/note", "/timestamp", "/media/m1"),
                autoMergedPaths = listOf("/timestamp"),
            ),
        ).containsExactly("/media/m1", "/note").inOrder()
    }

    @Test
    fun foreignMemberSleepUp_createsOwnWakeObservation_notB1OnSleep() = runTest {
        val momSync = RecordingSyncPort(
            membershipId = "m-mom",
            role = com.lezi.babylog.sync.session.FamilyRole.Member,
            familyId = "fam-1",
            deviceId = "dev-mom",
        )
        val momFakes = Fakes(momSync)
        momFakes.wireTransactionalSnapshots()
        val momCare = momFakes.careLog()
        val babyId = momFakes.seedFamilyAuthorityBaby()
        val startedAt = System.currentTimeMillis() - 2 * 60 * 60_000L
        val openId = momCare.confirmSleep(
            babyId = babyId,
            expectedOpenSleepId = null,
            timestamp = startedAt,
            endTimestamp = null,
            note = null,
            payloadJson = """{"is_nap":true,"anomaly_flag":false}""",
            nowMillis = startedAt + 1_000L,
            clientUuid = "sleep-open-mom-up",
        )
        val dadSync = RecordingSyncPort(
            membershipId = "m-dad",
            role = com.lezi.babylog.sync.session.FamilyRole.Member,
            familyId = "fam-1",
            deviceId = "dev-dad",
        )
        val dadFakes = Fakes(dadSync)
        dadFakes.wireTransactionalSnapshots()
        dadFakes.records.upsert(momFakes.records.get(openId)!!)
        dadFakes.babies.upsert(momFakes.babies.get(babyId)!!)
        val dadCare = dadFakes.careLog()
        val wakeAt = startedAt + 90 * 60_000L
        val closedId = dadCare.sleepUp(babyId, at = wakeAt, nowMillis = wakeAt)
        assertThat(closedId).isEqualTo(openId)
        val sleep = dadFakes.records.get(openId)!!
        assertThat(sleep.endTimestamp).isNull()
        val wakes = dadCare.listWakeObservations(sleep.clientUuid)
        assertThat(wakes).hasSize(1)
        assertThat(wakes.single().observerMembershipId).isEqualTo("m-dad")
        assertThat(wakes.single().wakeTimestamp).isEqualTo(wakeAt)
        assertThat(dadCare.canManageRecord(dadCare.getRecord(openId)!!)).isFalse()
        assertThat(dadCare.canEditWakeObservation(wakes.single().clientUuid)).isTrue()
    }
}
