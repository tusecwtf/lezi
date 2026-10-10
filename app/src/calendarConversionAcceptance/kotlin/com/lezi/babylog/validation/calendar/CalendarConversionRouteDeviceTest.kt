package com.lezi.babylog.validation.calendar

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import com.lezi.babylog.MainActivity
import com.lezi.babylog.core.common.DefaultLocalDataGate
import com.lezi.babylog.core.database.*
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.core.model.FulfillmentAdoptionStatus
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.CreateBabyInput
import com.lezi.babylog.sync.SyncPort
import dagger.Lazy
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** US-091: rendered MainActivity -> CalendarRoute and actual retained owner/domain/Room.
 * Uses isolated HiltTestApplication and a narrowly delegated audit-read fault, not LeziApp startup.
 */
@HiltAndroidTest
class CalendarConversionRouteDeviceTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    @Inject lateinit var gate: DefaultLocalDataGate
    @Inject lateinit var care: Lazy<CareLog>
    @Inject lateinit var db: Lazy<LeziDatabase>
    @Inject lateinit var sync: Lazy<SyncPort>
    @Inject lateinit var settings: SettingsStore
    @Inject lateinit var faults: CalendarReadFaults

    @Before fun setUp() { hilt.inject() }

    @Test fun listRefreshFailureStaysCommittedAcrossActivityRecreation() =
        conversionSurvives(CalendarReadFaults.Mode.List)

    @Test fun detailRefreshFailureStaysCommittedAcrossActivityRecreation() =
        conversionSurvives(CalendarReadFaults.Mode.Detail)

    private fun conversionSurvives(mode: CalendarReadFaults.Mode) {
        val fixture = seed()
        faults.candidateUuid = fixture.candidate.clientUuid
        faults.mode = mode
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitText("菜单")
                compose.onNode(hasText("菜单") and hasClickAction()).performClick()
                awaitText("日程")
                compose.onNodeWithText("日程").performScrollTo().performClick()
                awaitTag("calendar_plan_conflict_${fixture.plan.id}", useUnmergedTree = true)
                compose.onNodeWithTag("calendar_plan_${fixture.plan.id}").performScrollTo().performClick()
                awaitTag("conflict_audit_${fixture.candidate.clientUuid}")
                compose.onNodeWithTag("conflict_audit_${fixture.candidate.clientUuid}").performClick()
                awaitTag("conflict_convert_button")
                compose.onNodeWithTag("conflict_convert_button").assertIsDisplayed().performClick()
                awaitTag("conflict_convert_confirm")
                compose.onNodeWithTag("conflict_convert_confirm").assertIsDisplayed().performClick()
                awaitText(WARNING)
                assertCommittedUi()
                val converted = committedRecord(fixture)
                assertEquals(if (mode == CalendarReadFaults.Mode.List) 1 else 0, faults.listFailures.get())
                assertEquals(if (mode == CalendarReadFaults.Mode.Detail) 1 else 0, faults.detailFailures.get())

                scenario.recreate()
                awaitText(WARNING)
                assertCommittedUi()
                assertEquals(converted, committedRecord(fixture))
                val beforeRetryReads = faults.detailReads.get()
                faults.mode = CalendarReadFaults.Mode.None
                compose.onNodeWithText("重试刷新详情").performScrollTo().performClick()
                compose.waitUntil(15_000) { compose.onAllNodesWithText(WARNING).fetchSemanticsNodes().isEmpty() }
                assertCommittedUi()
                // A refresh performs one detail read. Re-entering the converter
                // would first perform extra candidate prechecks/transaction reads.
                assertEquals(beforeRetryReads + 1, faults.detailReads.get())
                assertEquals(converted, committedRecord(fixture))
                compose.onNodeWithText("关闭").assertIsDisplayed().performClick()
                compose.onNodeWithTag("conflict_audit_detail").assertDoesNotExist()
                // Reopen through the actual plan/list entry; its row now carries
                // the committed pointer and the converted detail has no convert CTA.
                compose.onNodeWithTag("calendar_plan_${fixture.plan.id}").performScrollTo().performClick()
                awaitTag("conflict_audit_${fixture.candidate.clientUuid}")
                compose.onNodeWithTag("conflict_audit_${fixture.candidate.clientUuid}")
                    .assertTextContains("已转为独立记录", substring = true).performClick()
                awaitTag("conflict_audit_detail")
                assertCommittedUi()
                assertEquals(converted, committedRecord(fixture))
            }
        } finally {
            faults.mode = CalendarReadFaults.Mode.None
            faults.candidateUuid = ""
        }
    }

    private fun assertCommittedUi() {
        compose.onNode(hasText("已转为独立护理记录") and hasAnyAncestor(hasTestTag("conflict_audit_detail")))
            .assertExists()
        compose.onNodeWithTag("conflict_convert_button").assertDoesNotExist()
        compose.onNodeWithTag("conflict_convert_confirm").assertDoesNotExist()
        compose.onNodeWithText("转换没有完成，这条记录保持原样，可重试").assertDoesNotExist()
    }

    private fun committedRecord(fixture: Fixture): RecordEntity = runBlocking {
        val database = db.get()
        val candidate = requireNotNull(database.fulfillmentCandidateDao().getByClientUuid(fixture.candidate.clientUuid))
        assertEquals(FulfillmentAdoptionStatus.CONFLICT_NOT_ADOPTED, candidate.adoptionStatus)
        assertTrue(candidate.convertedRecordClientUuid.isNotBlank())
        val converted = requireNotNull(database.recordDao().getByClientUuid(candidate.convertedRecordClientUuid))
        assertNotEquals(fixture.source.clientUuid, converted.clientUuid)
        assertEquals(fixture.source.note, converted.note)
        assertEquals(fixture.source.payloadJson, converted.payloadJson)
        assertEquals(fixture.source.timestamp, converted.timestamp)
        assertEquals(fixture.source, database.recordDao().getByClientUuid(fixture.source.clientUuid))
        assertEquals(fixture.plan, database.carePlanDao().get(fixture.plan.id))
        val ids = database.recordDao().listForBaby(fixture.plan.babyId).map { it.id }.toSet()
        assertEquals(fixture.originalRecordIds + converted.id, ids)
        converted
    }

    private fun seed(): Fixture = runBlocking {
        assertTrue(withTimeout(15_000) { gate.ensureReady() })
        // Verify the actual process sync owner is unjoined and endpoint-free.
        // Only the test CareLog's local presentation supplies synthetic admin role.
        val session = sync.get().sessionPresentation().first()
        assertFalse(session.isJoined)
        assertTrue(session.baseUrl.isBlank() && session.serverHost.isBlank())
        val log = care.get()
        assertTrue(log.listBabies().all { it.nickname.startsWith("AppGuard-") })
        val database = db.get()
        val now = System.currentTimeMillis()
        val baby = log.createBaby(CreateBabyInput("AppGuard-${UUID.randomUUID().toString().take(8)}", birthdayEpochDay = LocalDate.now().minusMonths(3).toEpochDay()))
        settings.setCurrentBabyId(baby)
        val planId = log.createCarePlan(baby, RecordType.BATH, now + 3_600_000, nowMillis = now, projectToSystemCalendar = false)
        val winnerId = log.addRecord(baby, RecordType.BATH, timestamp = now, note = "AppGuard-winner", nowMillis = now)
        val sourceId = log.addRecord(baby, RecordType.BATH, timestamp = now + 1, note = "AppGuard-loser", nowMillis = now + 1)
        val winner = requireNotNull(database.recordDao().get(winnerId))
        val source = requireNotNull(database.recordDao().get(sourceId))
        val plan = requireNotNull(database.carePlanDao().get(planId)).copy(
            scheduledAt = now, status = "completed", fulfilledRecordClientUuid = winner.clientUuid, fulfilledAt = now,
        )
        database.carePlanDao().update(plan)
        database.fulfillmentCandidateDao().upsert(FulfillmentCandidateEntity(
            clientUuid = UUID.randomUUID().toString(), carePlanClientUuid = plan.clientUuid,
            recordClientUuid = winner.clientUuid, confirmedAt = now, submitterRole = "owner",
            submitterMembershipId = "AppGuard-calendar-owner", adoptionStatus = FulfillmentAdoptionStatus.ADOPTED,
            updatedAt = now,
        ))
        val candidate = FulfillmentCandidateEntity(
            clientUuid = UUID.randomUUID().toString(), carePlanClientUuid = plan.clientUuid,
            recordClientUuid = source.clientUuid, actualTimestamp = source.timestamp, confirmedAt = now + 1,
            submitterRole = "member", submitterMembershipId = "AppGuard-calendar-member",
            adoptionStatus = FulfillmentAdoptionStatus.CONFLICT_NOT_ADOPTED, updatedAt = now + 1,
        )
        val candidateId = database.fulfillmentCandidateDao().upsert(candidate)
        Fixture(plan, source, candidate.copy(id = candidateId), database.recordDao().listForBaby(baby).map { it.id }.toSet())
    }

    private data class Fixture(
        val plan: CarePlanEntity, val source: RecordEntity,
        val candidate: FulfillmentCandidateEntity, val originalRecordIds: Set<Long>,
    )
    private fun awaitText(text: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
    private fun awaitTag(tag: String, useUnmergedTree: Boolean = false) = compose.waitUntil(15_000) {
        compose.onAllNodesWithTag(tag, useUnmergedTree = useUnmergedTree).fetchSemanticsNodes().size == 1
    }
    private companion object { const val WARNING = "已转为独立护理记录，详情暂时无法刷新，请重试刷新" }
}
