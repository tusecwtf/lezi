package com.lezi.babylog.domain

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** US079: real member ACL and unmodified Rust wire through public CareLog/RealSyncPort. */
class CareLogRealServerSeamWakeWithdrawalTest {
    @Test(timeout = 360_000)
    fun authorOrOwnerSelectionThenOwnOrForeignSleepWakeWithdrawalWithAndWithoutPhotos() = runBlocking {
        for (photos in listOf(false, true)) {
            for (ownSleep in listOf(false, true)) {
                for (ownerSelects in listOf(false, true)) {
                    println("SEAM[wake-withdrawal] photos=$photos ownSleep=$ownSleep ownerSelects=$ownerSelects")
                    CareLogRealServerSeamFixture.open(mediaEnabled = true).use { fixture ->
                        val author = fixture.member
                        val observer = if (ownSleep) author else fixture.joinExtraMember("wake-observer")
                        val selector = if (ownerSelects) fixture.owner else author
                        val babyId = fixture.owner.careLog.createBaby(CreateBabyInput(
                            nickname = "Wake matrix", birthdayEpochDay = 20_000L,
                        ))
                        fixture.owner.settleLocalWrite()
                        fixture.pullAll()
                        val babyUuid = requireNotNull(fixture.owner.fakes.babies.get(babyId)).clientUuid
                        val authorBaby = requireNotNull(author.babyByClientUuid(babyUuid))
                        val sleepUuid = UUID.randomUUID().toString()
                        val sourcePhoto = if (photos) requireNotNull(author.mediaFiles).syntheticPhoto() else null
                        val start = System.currentTimeMillis() - 3_600_000L
                        author.careLog.confirmSleep(
                            babyId = authorBaby.id,
                            expectedOpenSleepId = null,
                            timestamp = start,
                            endTimestamp = null,
                            note = "original sleep fact",
                            payloadJson = """{"is_nap":true,"anomaly_flag":false}""",
                            photoLocalPaths = listOfNotNull(sourcePhoto),
                            clientUuid = sleepUuid,
                        )
                        author.settleLocalWrite()
                        fixture.pullAll()
                        val observerSleep = requireNotNull(observer.recordByClientUuid(sleepUuid))
                        val wakeUuid = UUID.randomUUID().toString()
                        val wakePhoto = if (photos) requireNotNull(observer.mediaFiles).syntheticPhoto() else null
                        observer.careLog.recordWakeObservation(
                            babyId = observerSleep.babyId,
                            at = start + 1_800_000L,
                            note = "observer correction",
                            photoLocalPaths = listOfNotNull(wakePhoto),
                            sleepRecordId = observerSleep.id,
                            clientUuid = wakeUuid,
                        )
                        observer.settleLocalWrite()
                        fixture.pullAll()
                        selector.careLog.selectEffectiveWakeObservation(sleepUuid, wakeUuid)
                        selector.settleLocalWrite()
                        fixture.pullAll()
                        assertThat(observer.recordByClientUuid(sleepUuid)?.effectiveWakeObservationClientUuid)
                            .isEqualTo(wakeUuid)
                        val before = requireNotNull(observer.recordByClientUuid(sleepUuid))
                        // Freeze the actor so this checks the local public command itself,
                        // before an authorized author's later pointer repair can arrive.
                        observer.foreground.setForeground(false)
                        observer.careLog.withdrawWakeObservation(wakeUuid)
                        observer.careLog.withdrawWakeObservation(wakeUuid)
                        val after = requireNotNull(observer.recordByClientUuid(sleepUuid))
                        assertThat(after).isEqualTo(before)
                        assertThat(observer.fakes.wakeObservations.getByClientUuid(wakeUuid)?.withdrawn).isTrue()
                        observer.foreground.setForeground(true)
                        observer.settleLocalWrite()
                        fixture.pullAll()
                        fixture.allClients.forEach { it.foreground.setForeground(false) }
                        fixture.server.restart()
                        fixture.allClients.forEach { it.foreground.setForeground(true) }
                        fixture.pullAll()
                        // A new consumer reconstructs from the real restarted server,
                        // rather than passing because the original local cache survived.
                        fixture.joinExtraOwner("wake-fresh")
                        for (client in fixture.allClients) {
                            val sleep = requireNotNull(client.recordByClientUuid(sleepUuid))
                            assertThat(sleep.timestamp).isEqualTo(start)
                            assertThat(sleep.note).isEqualTo("original sleep fact")
                            assertThat(sleep.createdByMembershipId).isEqualTo(author.currentSession().membershipId)
                            assertThat(sleep.syncDirty).isFalse()
                            val wake = requireNotNull(client.fakes.wakeObservations.getByClientUuid(wakeUuid))
                            assertThat(wake.withdrawn).isTrue()
                            assertThat(wake.observerMembershipId).isEqualTo(observer.currentSession().membershipId)
                            assertThat(wake.syncDirty).isFalse()
                            val projection = requireNotNull(client.careLog.projectSleepRecord(sleep.id))
                            assertThat(projection.interval.endObservationClientUuid).isNull()
                            assertThat(projection.interval.endTimestamp).isNull()
                            assertThat(projection.interval.visibleObservations).isEmpty()
                            val wakeMedia = client.fakes.media.listActiveForWakeObservation(wake.id)
                            assertThat(wakeMedia).hasSize(if (photos) 1 else 0)
                            if (wakePhoto != null) {
                                // Withdrawal excludes the observation from the sleep interval;
                                // it does not delete the live wake root's historical attachment.
                                assertThat(File(wakeMedia.single().localUri).readBytes())
                                    .isEqualTo(File(wakePhoto).readBytes())
                            }
                            val media = client.fakes.media.listActiveForRecord(sleep.id)
                            assertThat(media).hasSize(if (photos) 1 else 0)
                            if (sourcePhoto != null) {
                                assertThat(File(media.single().localUri).readBytes())
                                    .isEqualTo(File(sourcePhoto).readBytes())
                            }
                        }
                    }
                }
            }
        }
    }
}
