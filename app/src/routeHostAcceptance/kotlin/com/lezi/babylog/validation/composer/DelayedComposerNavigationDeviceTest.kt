package com.lezi.babylog.validation.composer

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.platform.app.InstrumentationRegistry
import com.lezi.babylog.MainActivity
import com.lezi.babylog.RootViewModel
import com.lezi.babylog.UNTRUSTED_NAVIGATION_CONFIRM_TAG
import com.lezi.babylog.core.common.DefaultLocalDataGate
import com.lezi.babylog.core.database.LeziDatabase
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.core.model.CarePlan
import com.lezi.babylog.core.model.CarePlanStatus
import com.lezi.babylog.core.model.DeviceLayoutSnapshot
import com.lezi.babylog.core.model.RecordMediaFiles
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.CreateBabyInput
import com.lezi.babylog.feature.log.composer.RecordComposerRequest
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.validation.host.HeldRouteRead
import dagger.Lazy
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.hamcrest.Matchers.anyOf
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Real MainActivity/Root/Composer and owned media; only selected plan reads are held. */
@HiltAndroidTest
class DelayedComposerNavigationDeviceTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    @Inject lateinit var gate: DefaultLocalDataGate
    @Inject lateinit var care: Lazy<CareLog>
    @Inject lateinit var database: Lazy<LeziDatabase>
    @Inject lateinit var sync: Lazy<SyncPort>
    @Inject lateinit var settings: SettingsStore
    @Inject lateinit var lookups: PlanLookupGate

    @Before fun setUp() { hilt.inject() }

    @Test fun latePlanLookupCannotReplaceOwnedDraftAfterActivityRecreation() {
        val fixture = seed()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(context.cacheDir, "camera/AppGuard-${UUID.randomUUID()}.png")
        check(input.parentFile!!.mkdirs() || input.parentFile!!.isDirectory)
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.MAGENTA)
            input.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", input)
        val mediaRoot = RecordMediaFiles.allowedRoot(context.filesDir)
        val beforeMedia = mediaRoot.listFiles().orEmpty().map { it.canonicalPath }.toSet()
        Intents.init()
        try {
            Intents.intending(anyOf(
                hasAction(Intent.ACTION_OPEN_DOCUMENT),
                hasAction("android.provider.action.PICK_IMAGES"),
                hasAction("androidx.activity.result.contract.action.PICK_IMAGES"),
                hasAction("com.google.android.gms.provider.action.PICK_IMAGES"),
            )).respondWith(ActivityResult(Activity.RESULT_OK, Intent().setData(uri)))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val pending = lookups.hold(fixture.older.clientUuid)
                confirmExternal(scenario, fixture.older)
                awaitEntered(pending)
                openNewDraft()
                val note = "迟到计划不能覆盖这份备注和照片"
                compose.onNodeWithTag("record_composer_note_field").performTextReplacement(note)
                compose.onNodeWithText("相册").performScrollTo().performClick()
                compose.waitUntil(15_000) {
                    compose.onAllNodesWithContentDescription("记录图片，点击预览").fetchSemanticsNodes().size == 1
                }
                val owned = mediaRoot.listFiles().orEmpty().single { it.canonicalPath !in beforeMedia }
                val ownedDigest = digest(owned)
                assertNotEquals(input.canonicalPath, owned.canonicalPath)
                val request = rootRequest(scenario)
                assertTrue(request is RecordComposerRequest.New)
                assertEquals(RecordType.PEE, (request as RecordComposerRequest.New).type)
                var retainedRoot: RootViewModel? = null
                scenario.onActivity { retainedRoot = ViewModelProvider(it)[RootViewModel::class.java] }

                scenario.recreate()
                // Recreated MainActivity reparses the retained external Intent.
                // Its confirmation is real, but the already-open draft owns the host.
                awaitTag(UNTRUSTED_NAVIGATION_CONFIRM_TAG)
                compose.onNodeWithTag(UNTRUSTED_NAVIGATION_CONFIRM_TAG).performClick()
                scenario.onActivity { assertSame(retainedRoot, ViewModelProvider(it)[RootViewModel::class.java]) }
                releaseAndConsume(pending)
                assertEquals(request, rootRequest(scenario))
                compose.onNodeWithTag("record_composer_note_field").assertTextContains(note)
                compose.onAllNodesWithContentDescription("记录图片，点击预览").assertCountEquals(1)
                assertTrue(owned.isFile)
                assertEquals(ownedDigest, digest(owned))
                assertNoRecords(fixture.baby)

                compose.onNodeWithText("取消").assertIsDisplayed().performClick()
                awaitText("放弃未保存的更改？")
                compose.onNodeWithText("放弃").performClick()
                compose.waitUntil(15_000) { !owned.exists() }
                assertNoComposer(scenario)
                assertTrue(input.isFile)
                assertTrue(beforeMedia.all { File(it).isFile })
                assertNoRecords(fixture.baby)
            }
        } finally {
            lookups.releaseAll()
            Intents.release()
            input.delete()
        }
    }

    @Test fun closedAndNewerComposerRequestsRejectLatePlanLookups() {
        val fixture = seed()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                // An actual new draft, then its visible cancel action, invalidates
                // a lookup that started before either user action.
                val closed = lookups.hold(fixture.older.clientUuid)
                confirmExternal(scenario, fixture.older)
                awaitEntered(closed)
                openNewDraft()
                compose.onNodeWithText("取消").assertIsDisplayed().performClick()
                assertNoComposer(scenario)
                releaseAndConsume(closed)
                assertNoComposer(scenario)

                // The newer confirmed plan owns delivery even if the older lookup
                // returns first, before any composer is visible.
                val older = lookups.hold(fixture.older.clientUuid)
                val newer = lookups.hold(fixture.newer.clientUuid)
                confirmExternal(scenario, fixture.older)
                awaitEntered(older)
                confirmExternal(scenario, fixture.newer)
                awaitEntered(newer)
                releaseAndConsume(older)
                assertNoComposer(scenario)
                releaseAndConsume(newer)
                awaitTag("record_composer_note_field")
                assertEquals(RecordComposerRequest.Fulfill(fixture.newer.id), rootRequest(scenario))
                compose.onNodeWithTag("record_composer_note_field").assertTextContains(requireNotNull(fixture.newer.note))
                compose.onNodeWithText("取消").assertIsDisplayed().performClick()
                assertNoComposer(scenario)
                assertNoRecords(fixture.baby)
                runBlocking {
                    assertEquals(fixture.older, care.get().getCarePlan(fixture.older.id))
                    assertEquals(fixture.newer, care.get().getCarePlan(fixture.newer.id))
                }
            }
        } finally {
            lookups.releaseAll()
        }
    }

    private fun seed(): Fixture = runBlocking {
        assertTrue(withTimeout(15_000) { gate.ensureReady() })
        val session = sync.get().sessionPresentation().first()
        assertFalse(session.isJoined)
        assertTrue(session.baseUrl.isBlank() && session.serverHost.isBlank())
        val log = care.get()
        assertTrue(log.listBabies().isEmpty())
        val baby = log.createBaby(CreateBabyInput(
            "AppGuard-delayed-composer", birthdayEpochDay = LocalDate.now().minusMonths(3).toEpochDay(),
        ))
        settings.setCurrentBabyId(baby)
        settings.setDeviceLayoutSnapshot(DeviceLayoutSnapshot(quickRecordSlots = listOf("pee", "sleep", "nursing", "formula")))
        suspend fun plan(note: String): CarePlan {
            val now = System.currentTimeMillis()
            val id = log.createCarePlan(babyId = baby, type = RecordType.BATH, scheduledAt = now + 3_600_000,
                note = note, nowMillis = now, projectToSystemCalendar = false)
            val result = requireNotNull(log.getCarePlan(id))
            assertEquals(CarePlanStatus.PENDING, result.status)
            assertEquals(id, log.getCarePlanByClientUuid(result.clientUuid)?.id)
            return result
        }
        Fixture(baby, plan("AppGuard-older-plan"), plan("AppGuard-newer-plan"))
    }

    private fun confirmExternal(scenario: ActivityScenario<MainActivity>, plan: CarePlan) {
        awaitTag("one_hand_action_pee")
        scenario.onActivity { activity ->
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("lezi://care-plan/${plan.clientUuid}"))
                .setClass(activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        awaitTag(UNTRUSTED_NAVIGATION_CONFIRM_TAG)
        compose.onNodeWithTag(UNTRUSTED_NAVIGATION_CONFIRM_TAG).performClick()
    }

    private fun openNewDraft() {
        compose.onNodeWithTag("one_hand_action_pee").performClick()
        awaitTag("record_composer_note_field")
    }

    private fun rootRequest(scenario: ActivityScenario<MainActivity>): RecordComposerRequest? {
        var request: RecordComposerRequest? = null
        scenario.onActivity { request = ViewModelProvider(it)[RootViewModel::class.java].ui.value?.composerRequest }
        return request
    }

    private fun assertNoComposer(scenario: ActivityScenario<MainActivity>) {
        compose.waitUntil(15_000) { rootRequest(scenario) == null }
        compose.onNodeWithTag("quick_record_confirm_sheet").assertDoesNotExist()
        compose.onNodeWithTag("record_composer_note_field").assertDoesNotExist()
    }

    private fun awaitEntered(read: HeldRouteRead) = compose.waitUntil(15_000) { read.entered.isCompleted }
    private fun releaseAndConsume(read: HeldRouteRead) {
        read.release()
        compose.waitUntil(15_000) { read.completed.isCompleted }
        assertNull(runBlocking { read.completed.await() })
        compose.waitForIdle()
    }
    private fun assertNoRecords(baby: Long) = runBlocking { assertTrue(database.get().recordDao().listForBaby(baby).isEmpty()) }
    private fun digest(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun awaitTag(tag: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1
    }
    private fun awaitText(text: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
    private data class Fixture(val baby: Long, val older: CarePlan, val newer: CarePlan)
}
