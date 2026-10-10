package com.lezi.babylog.validation

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
import com.lezi.babylog.MainActivity
import com.lezi.babylog.RootViewModel
import com.lezi.babylog.UNTRUSTED_NAVIGATION_CONFIRM_TAG
import com.lezi.babylog.core.model.DeviceLayoutSnapshot
import com.lezi.babylog.core.model.CarePlanStatus
import com.lezi.babylog.core.model.RecordMediaFiles
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.deviceLayoutSnapshot
import com.lezi.babylog.feature.log.composer.RecordComposerRequest
import com.lezi.babylog.feature.widget.WidgetComposerContract
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matchers.anyOf
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** US-088 full production host slice: an already-open draft owns its note/photos.
 * Does not claim the separate delayed UUID-resolution race or process-death coverage.
 * Only the external photo picker result is synthetic; import/ownership/cleanup are real.
 */
class ProductionExternalComposerOwnershipDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun repeatedExternalConfirmationsAndActivityRecreationKeepExistingDraftAndOwnedPhoto() {
        val fixture = ProductionAppFixture()
        fixture.seedBaby()
        val validPlan = runBlocking {
            val now = System.currentTimeMillis()
            val id = fixture.careLog.createCarePlan(
                babyId = fixture.babyId, type = RecordType.BATH,
                scheduledAt = now + 3_600_000, note = "AppGuard-external-plan",
                nowMillis = now, projectToSystemCalendar = false,
            )
            val plan = requireNotNull(fixture.careLog.getCarePlan(id))
            assertEquals(CarePlanStatus.PENDING, plan.status)
            assertEquals(id, fixture.careLog.getCarePlanByClientUuid(plan.clientUuid)?.id)
            plan
        }
        val settings = EntryPointAccessors.fromApplication(
            fixture.app, ProductionAcceptanceEntryPoint::class.java,
        ).settingsStore()
        val originalLayout = runBlocking { settings.settings.first().deviceLayoutSnapshot() }
        val input = File(fixture.context.cacheDir, "camera/AppGuard-${UUID.randomUUID()}.png")
        input.parentFile!!.mkdirs()
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.MAGENTA)
            input.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        val uri = FileProvider.getUriForFile(fixture.context, fixture.context.packageName + ".fileprovider", input)
        val mediaRoot = RecordMediaFiles.allowedRoot(fixture.context.filesDir)
        val beforeMedia = mediaRoot.listFiles().orEmpty().map { it.canonicalPath }.toSet()
        val beforeRecords = fixture.records()
        Intents.init()
        try {
            // API26 document picker and API35 photo picker both return the same
            // single synthetic input. Never operate a gallery or external app.
            Intents.intending(anyOf(
                hasAction(Intent.ACTION_OPEN_DOCUMENT),
                hasAction("android.provider.action.PICK_IMAGES"),
                hasAction("androidx.activity.result.contract.action.PICK_IMAGES"),
                hasAction("com.google.android.gms.provider.action.PICK_IMAGES"),
            )).respondWith(ActivityResult(Activity.RESULT_OK, Intent().setData(uri)))
            runBlocking {
                settings.setCurrentBabyId(fixture.babyId)
                settings.setDeviceLayoutSnapshot(DeviceLayoutSnapshot(quickRecordSlots = listOf("pee", "sleep", "nursing", "formula")))
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitTag("one_hand_action_pee")
                compose.onNodeWithTag("one_hand_action_pee").performClick()
                awaitTag("record_composer_note_field")
                val note = "新的草稿保留备注和照片"
                compose.onNodeWithTag("record_composer_note_field").performTextReplacement(note)
                compose.onNodeWithText("相册").performScrollTo().performClick()
                compose.waitUntil(15_000) {
                    compose.onAllNodesWithContentDescription("记录图片，点击预览").fetchSemanticsNodes().size == 1
                }
                val owned = mediaRoot.listFiles().orEmpty().single { it.canonicalPath !in beforeMedia }
                val ownedDigest = digest(owned)
                assertNotEquals(input.canonicalPath, owned.canonicalPath)
                var request: RecordComposerRequest? = null
                scenario.onActivity { request = ViewModelProvider(it)[RootViewModel::class.java].ui.value?.composerRequest }
                assertTrue(request is RecordComposerRequest.New)
                assertEquals(RecordType.PEE, (request as RecordComposerRequest.New).type)

                val external = listOf(
                    Intent(Intent.ACTION_VIEW, Uri.parse("lezi://care-plan/${validPlan.clientUuid}"))
                        .setClass(fixture.context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    WidgetComposerContract.createIntent(fixture.context, fixture.babyId, RecordType.FORMULA),
                )
                external.forEach { intent ->
                    repeat(2) {
                        scenario.onActivity { it.startActivity(intent) }
                        awaitTag(UNTRUSTED_NAVIGATION_CONFIRM_TAG)
                        compose.onNodeWithTag(UNTRUSTED_NAVIGATION_CONFIRM_TAG).performClick()
                        assertDraft(scenario, request, note, owned, ownedDigest)
                    }
                }
                scenario.recreate()
                // MainActivity reparses its retained external Intent on recreation.
                // The real repeated confirmation still cannot replace the open draft.
                awaitTag(UNTRUSTED_NAVIGATION_CONFIRM_TAG)
                compose.onNodeWithTag(UNTRUSTED_NAVIGATION_CONFIRM_TAG).performClick()
                assertDraft(scenario, request, note, owned, ownedDigest)
                assertEquals(beforeRecords, fixture.records())

                // Positive ownership oracle: explicit discard reclaims the imported
                // draft copy, while preserving the input and pre-existing media.
                compose.onNodeWithText("取消").assertIsDisplayed().performClick()
                awaitText("放弃未保存的更改？")
                compose.onNodeWithText("放弃").performClick()
                compose.waitUntil(10_000) { !owned.exists() }
                assertTrue(input.isFile)
                assertTrue(beforeMedia.all { File(it).isFile })
                assertEquals(beforeRecords, fixture.records())
                compose.onNodeWithTag("quick_record_confirm_sheet").assertDoesNotExist()
            }
        } finally {
            Intents.release()
            input.delete()
            runBlocking { settings.setDeviceLayoutSnapshot(originalLayout) }
        }
    }

    private fun assertDraft(
        scenario: ActivityScenario<MainActivity>, request: RecordComposerRequest?,
        note: String, photo: File, expectedDigest: String,
    ) {
        awaitTag("record_composer_note_field")
        compose.onNodeWithTag("record_composer_note_field").assertTextContains(note)
        compose.onAllNodesWithContentDescription("记录图片，点击预览").assertCountEquals(1)
        scenario.onActivity { assertEquals(request, ViewModelProvider(it)[RootViewModel::class.java].ui.value?.composerRequest) }
        assertTrue(photo.isFile)
        assertEquals(expectedDigest, digest(photo))
    }

    private fun digest(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun awaitTag(tag: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1
    }
    private fun awaitText(text: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
}
