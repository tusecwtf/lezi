package com.lezi.babylog.validation.export

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.platform.app.InstrumentationRegistry
import com.lezi.babylog.MainActivity
import com.lezi.babylog.core.common.DefaultLocalDataGate
import com.lezi.babylog.core.database.LeziDatabase
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.core.model.ProductDateTime
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.CreateBabyInput
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.validation.host.HeldRouteRead
import dagger.Lazy
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** US-090 prepared source: real MainActivity/ExportRoute, domain/Room and normal file generator.
 * HiltTestApplication is not LeziApp startup proof. Only the chooser is stubbed; the return
 * slice explicitly simulates host foregrounding, not an actual third-party share round trip.
 * No renderer/process observer, replacement ViewModel, fake export document or renderer binding.
 */
@HiltAndroidTest
class ExportRouteFilesDeviceTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    @Inject lateinit var gate: DefaultLocalDataGate
    @Inject lateinit var care: Lazy<CareLog>
    @Inject lateinit var db: Lazy<LeziDatabase>
    @Inject lateinit var sync: Lazy<SyncPort>
    @Inject lateinit var settings: SettingsStore
    @Inject lateinit var reads: ExportReadControl

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val exportDir get() = File(context.cacheDir, "export")
    private val sourcePhotos = mutableListOf<File>()

    @Before fun setUp() {
        hilt.inject()
        Intents.init()
        intending(hasAction(Intent.ACTION_CHOOSER)).respondWith(ActivityResult(Activity.RESULT_CANCELED, null))
    }

    @After fun tearDown() {
        reads.reset()
        Intents.release()
        sourcePhotos.forEach { it.delete() }
    }

    @Test fun historicalPdfRequestsFreezeOptionsAndKeepDistinctFiles() {
        val fixture = seed()
        ActivityScenario.launch(MainActivity::class.java).use {
            openExport()
            selectRange(FROM, TO)
            compose.onNode(isToggleable()).assertIsOn().performScrollTo().performClick().assertIsOff()
            val firstRequest = fixture.request(FROM, TO)
            val firstHeld = reads.holdNext(firstRequest)
            try {
                clickExport(PDF)
                awaitHeld(firstHeld)
                assertFrozenDraft(FROM, TO, photos = false)
                assertEquals(1, reads.count(firstRequest))
                assertTrue(chooserIntents().isEmpty())
                firstHeld.release()
                val first = awaitShare(1, "application/pdf", "分享 PDF")
                assertPreview(fixture, FROM, TO, includeStart = true)
                assertPdf(first.uri, pageCount = 1, hasPhoto = false)
                assertIdleDraft(FROM, TO, photos = false)
                val originalDigest = digest(first.file)

                // A later, different draft must not mutate the already shared file or URI.
                selectRange(TO, TO)
                compose.onNode(isToggleable()).assertIsOff().performScrollTo().performClick().assertIsOn()
                val secondRequest = fixture.request(TO, TO)
                val secondHeld = reads.holdNext(secondRequest)
                try {
                    clickExport(PDF)
                    awaitHeld(secondHeld)
                    assertFrozenDraft(TO, TO, photos = true)
                    assertEquals(1, reads.count(secondRequest))
                    secondHeld.release()
                    val second = awaitShare(2, "application/pdf", "分享 PDF")
                    assertPreview(fixture, TO, TO, includeStart = false)
                    assertPdf(second.uri, pageCount = 2, hasPhoto = true)
                    assertNotEquals(first.uri, second.uri)
                    assertNotEquals(first.file.name, second.file.name)
                    assertEquals(originalDigest, digest(first.file))
                    assertPdf(first.uri, pageCount = 1, hasPhoto = false)
                    assertIdleDraft(TO, TO, photos = true)
                    assertEquals(1, reads.count(firstRequest))
                    assertEquals(1, reads.count(secondRequest))
                    assertInventory(fixture, first, second)
                    assertSourceUnchanged(fixture)
                } finally {
                    releaseAndAwait(secondHeld)
                }
            } finally {
                releaseAndAwait(firstHeld)
            }
        }
    }

    @Test fun readFailurePreservesDraftAndExplicitRetryGeneratesFile() {
        val fixture = seed()
        ActivityScenario.launch(MainActivity::class.java).use {
            openExport()
            selectRange(FROM, TO)
            compose.onNode(isToggleable()).performScrollTo().performClick().assertIsOff()
            val request = fixture.request(FROM, TO)
            val held = reads.holdNext(request, fail = true)
            try {
                clickExport(PDF)
                awaitHeld(held)
                assertFrozenDraft(FROM, TO, photos = false)
                held.release()
                awaitText("本机保存没有成功", substring = true)
                assertTrue(chooserIntents().isEmpty())
                assertEquals(fixture.beforeFiles, exportNames())
                assertEquals(1, reads.count(request))
                assertSourceUnchanged(fixture)

                // The shared dialog's Retry action dismisses it. The actual export CTA
                // starts the next attempt; do not misreport the dismissal as an IO retry.
                compose.onNodeWithText("再试一次").performClick()
                assertIdleDraft(FROM, TO, photos = false)
                assertEquals(1, reads.count(request))
                clickExport(PDF)
                val share = awaitShare(1, "application/pdf", "分享 PDF")
                assertEquals(2, reads.count(request))
                assertPdf(share.uri, pageCount = 1, hasPhoto = false)
                assertPreview(fixture, FROM, TO, includeStart = true)
                assertIdleDraft(FROM, TO, photos = false)
                compose.onNodeWithText("本机保存没有成功", substring = true).assertDoesNotExist()
                assertInventory(fixture, share)
                assertSourceUnchanged(fixture)
            } finally {
                releaseAndAwait(held)
            }
        }
    }

    @Test fun stubbedChooserReturnKeepsRouteUsableAndTxtReadable() {
        val fixture = seed()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openExport()
            selectRange(FROM, TO)
            clickExport(TXT)
            val first = awaitShare(1, "text/plain", "分享 TXT")
            val text = readUri(first.uri).toString(Charsets.UTF_8)
            assertDocumentText(text, fixture, FROM, TO, includeStart = true)
            assertPreview(fixture, FROM, TO, includeStart = true)
            assertArrayEquals(first.file.readBytes(), readUri(first.uri))

            // Espresso's canceled chooser stub does not itself pause the Activity.
            // Drive a real host lifecycle transition to exercise ExportRoute's return UI.
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            awaitText("文件已生成，可在本页再次分享")
            assertIdleDraft(FROM, TO, photos = true)
            compose.onNodeWithTag("export_empty_range").assertDoesNotExist()
            compose.onNodeWithText("预览").assertExists()
            assertEquals(text, readUri(first.uri).toString(Charsets.UTF_8))
            assertEquals(1, chooserIntents().size)

            clickExport(TXT)
            val second = awaitShare(2, "text/plain", "分享 TXT")
            assertNotEquals(first.uri, second.uri)
            assertEquals(text, readUri(second.uri).toString(Charsets.UTF_8))
            assertEquals(text, readUri(first.uri).toString(Charsets.UTF_8))
            assertIdleDraft(FROM, TO, photos = true)
            assertInventory(fixture, first, second)
            assertSourceUnchanged(fixture)
        }
    }

    private fun seed(): Fixture = runBlocking {
        assertEquals("com.lezi.babylog.debug", context.packageName)
        assertTrue(withTimeout(15_000) { gate.ensureReady() })
        val session = sync.get().sessionPresentation().first()
        assertFalse(session.isJoined)
        assertTrue(session.baseUrl.isBlank() && session.serverHost.isBlank())
        val log = care.get()
        assertTrue(log.listBabies().all { it.nickname.startsWith("AppGuard-") })
        val token = UUID.randomUUID().toString().take(8)
        val name = "AppGuard-export-$token"
        val babyId = log.createBaby(CreateBabyInput(name, birthdayEpochDay = FROM.minusMonths(3).toEpochDay()))
        settings.setCurrentBabyId(babyId)
        // Distinct photos make the narrowed PDF's actual page count a range oracle:
        // exporting the old wider range with photos on would produce three pages.
        val firstPhoto = syntheticPhoto("$token-first", Color.BLUE)
        val lastPhoto = syntheticPhoto("$token-last", Color.RED)
        val start = dayStart(FROM)
        val end = dayStart(TO.plusDays(1))
        val startNote = "AppGuard-first-$token"
        val endNote = "AppGuard-last-$token"
        val excludedBefore = "AppGuard-before-$token"
        val excludedAfter = "AppGuard-after-$token"
        log.addRecord(babyId, RecordType.BATH, timestamp = start - 1, note = excludedBefore)
        log.addRecord(babyId, RecordType.BATH, timestamp = start, note = startNote, photoLocalPaths = listOf(firstPhoto.path))
        log.addRecord(babyId, RecordType.BATH, timestamp = end - 1, note = endNote, photoLocalPaths = listOf(lastPhoto.path))
        log.addRecord(babyId, RecordType.BATH, timestamp = end, note = excludedAfter)
        val records = db.get().recordDao().listForBaby(babyId)
        val media = db.get().mediaAssetDao().listForRecords(records.map { it.id })
        assertEquals(4, records.size)
        assertEquals(setOf(firstPhoto.path, lastPhoto.path), media.map { it.localUri }.toSet())
        assertEquals(2, media.size)
        Fixture(babyId, name, startNote, endNote, excludedBefore, excludedAfter, records, media, exportNames())
    }

    private fun syntheticPhoto(token: String, color: Int): File {
        val photo = File(context.filesDir, "record-media/AppGuard-export-$token.png")
        sourcePhotos.add(photo)
        check(photo.parentFile!!.isDirectory || photo.parentFile!!.mkdirs())
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            photo.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        return photo
    }

    private fun openExport() {
        awaitText("菜单")
        compose.onNode(hasText("菜单") and hasClickAction()).performClick()
        awaitText("导出数据")
        compose.onNodeWithText("导出数据").performScrollTo().performClick()
        awaitText("选择开始日期")
    }

    private fun selectRange(from: LocalDate, to: LocalDate) {
        selectDate("选择开始日期", from)
        selectDate("选择结束日期", to)
        compose.onNodeWithText(rangeLabel(from, to)).assertExists()
    }

    private fun selectDate(button: String, date: LocalDate) {
        compose.onNodeWithText(button).performScrollTo().performClick()
        if (compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithContentDescription("切换到文本字段输入模式").performClick()
        }
        compose.onNode(hasSetTextAction()).performTextReplacement(
            String.format(Locale.ROOT, "%04d%02d%02d", date.year, date.monthValue, date.dayOfMonth),
        )
        compose.onNodeWithText("确定").performClick()
    }

    private fun assertFrozenDraft(from: LocalDate, to: LocalDate, photos: Boolean) {
        awaitText("正在生成…")
        compose.onNodeWithText(rangeLabel(from, to)).assertExists()
        compose.onNodeWithText("选择开始日期").assertIsNotEnabled().performScrollTo().performClick()
        compose.onNodeWithText("选择结束日期").assertIsNotEnabled().performScrollTo().performClick()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNode(isToggleable()).assertIsNotEnabled().performScrollTo().performClick()
        assertPhotos(photos)
        compose.onNodeWithText("正在生成…").assertIsNotEnabled().performScrollTo().performClick()
        compose.onNodeWithText(TXT).assertIsNotEnabled().performScrollTo().performClick()
    }

    private fun assertIdleDraft(from: LocalDate, to: LocalDate, photos: Boolean) {
        awaitText(PDF)
        compose.onNodeWithText(rangeLabel(from, to)).assertExists()
        compose.onNodeWithText("选择开始日期").assertIsEnabled()
        compose.onNodeWithText("选择结束日期").assertIsEnabled()
        compose.onNode(isToggleable()).assertIsEnabled()
        assertPhotos(photos)
        compose.onNodeWithText(PDF).assertIsEnabled()
        compose.onNodeWithText(TXT).assertIsEnabled()
        compose.onNodeWithText("正在生成…").assertDoesNotExist()
    }

    private fun assertPhotos(expected: Boolean) {
        if (expected) compose.onNode(isToggleable()).assertIsOn()
        else compose.onNode(isToggleable()).assertIsOff()
    }

    private fun assertPreview(fixture: Fixture, from: LocalDate, to: LocalDate, includeStart: Boolean) {
        compose.onNodeWithText("预览").assertExists()
        val preview = compose.onNodeWithText("宝宝：${fixture.babyName}", substring = true)
        preview.assertTextContains("范围：$from ~ $to", substring = true)
        preview.assertTextContains(fixture.endNote, substring = true)
        if (includeStart) preview.assertTextContains(fixture.startNote, substring = true)
        else compose.onNodeWithText(fixture.startNote, substring = true).assertDoesNotExist()
        compose.onNodeWithText(fixture.excludedBefore, substring = true).assertDoesNotExist()
        compose.onNodeWithText(fixture.excludedAfter, substring = true).assertDoesNotExist()
    }

    private fun assertDocumentText(text: String, fixture: Fixture, from: LocalDate, to: LocalDate, includeStart: Boolean) {
        assertTrue(text.startsWith("乐记导出\n宝宝：${fixture.babyName}\n范围：$from ~ $to\n"))
        assertEquals(includeStart, text.contains(fixture.startNote))
        assertTrue(text.contains(fixture.endNote))
        assertFalse(text.contains(fixture.excludedBefore))
        assertFalse(text.contains(fixture.excludedAfter))
    }

    @Suppress("DEPRECATION")
    private fun awaitShare(count: Int, mime: String, title: String): SharedFile {
        compose.waitUntil(35_000) { chooserIntents().size >= count }
        assertEquals("One chooser per explicit successful attempt", count, chooserIntents().size)
        val chooser = chooserIntents().last()
        assertEquals(title, chooser.getCharSequenceExtra(Intent.EXTRA_TITLE)?.toString())
        val send = requireNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals(mime, send.type)
        assertEquals("乐记导出", send.getStringExtra(Intent.EXTRA_SUBJECT))
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val uri = requireNotNull(send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals("content", uri.scheme)
        assertEquals(context.packageName + ".fileprovider", uri.authority)
        assertEquals(1, send.clipData?.itemCount)
        assertEquals(uri, send.clipData?.getItemAt(0)?.uri)
        assertEquals(mime, context.contentResolver.getType(uri))
        val file = File(exportDir, requireNotNull(uri.lastPathSegment))
        assertTrue(file.name.startsWith("lezi-") && file.isFile && file.length() > 0)
        assertEquals(exportDir.canonicalFile, file.canonicalFile.parentFile)
        assertArrayEquals(file.readBytes(), readUri(uri))
        return SharedFile(uri, file)
    }

    private fun assertPdf(uri: Uri, pageCount: Int, hasPhoto: Boolean) {
        assertEquals("%PDF-", readUri(uri).take(5).toByteArray().toString(Charsets.US_ASCII))
        PdfRenderer(requireNotNull(context.contentResolver.openFileDescriptor(uri, "r"))).use { pdf ->
            assertEquals(pageCount, pdf.pageCount)
            pdf.openPage(0).use { page -> assertTrue(page.width > 0 && page.height > 0) }
            if (hasPhoto) pdf.openPage(1).use { page ->
                val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val color = bitmap.getPixel(80, 100)
                    assertTrue(Color.red(color) > 180 && Color.green(color) < 80 && Color.blue(color) < 80)
                } finally {
                    bitmap.recycle()
                }
            }
        }
    }

    private fun assertSourceUnchanged(fixture: Fixture) = runBlocking {
        assertEquals(fixture.records, db.get().recordDao().listForBaby(fixture.babyId))
        assertEquals(fixture.media, db.get().mediaAssetDao().listForRecords(fixture.records.map { it.id }))
    }

    private fun assertInventory(fixture: Fixture, vararg shares: SharedFile) {
        assertEquals(fixture.beforeFiles + shares.map { it.file.name }, exportNames())
        assertFalse(exportDir.listFiles().orEmpty().any { it.extension == "partial" })
    }

    private fun clickExport(label: String) = compose.onNodeWithText(label).performScrollTo().performClick()
    private fun awaitText(text: String, substring: Boolean = false) = compose.waitUntil(15_000) {
        compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
    }
    private fun awaitHeld(held: HeldRouteRead) = compose.waitUntil(15_000) { held.entered.isCompleted }
    private fun releaseAndAwait(held: HeldRouteRead) {
        held.release()
        if (held.entered.isCompleted) runBlocking { withTimeout(15_000) { held.completed.await() } }
    }
    private fun chooserIntents(): List<Intent> = Intents.getIntents().filter { it.action == Intent.ACTION_CHOOSER }
    private fun exportNames(): Set<String> = exportDir.listFiles().orEmpty().map { it.name }.toSet()
    private fun readUri(uri: Uri): ByteArray = requireNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytes() }
    private fun digest(file: File): List<Byte> = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
    private fun rangeLabel(from: LocalDate, to: LocalDate) = "${ProductDateTime.yearMonthDay(from)} — ${ProductDateTime.yearMonthDay(to)}"

    private data class SharedFile(val uri: Uri, val file: File)
    private data class Fixture(
        val babyId: Long,
        val babyName: String,
        val startNote: String,
        val endNote: String,
        val excludedBefore: String,
        val excludedAfter: String,
        val records: List<RecordEntity>,
        val media: List<MediaAssetEntity>,
        val beforeFiles: Set<String>,
    ) {
        fun request(from: LocalDate, to: LocalDate) = ExportReadControl.Request(babyId, dayStart(from), dayStart(to.plusDays(1)))
    }

    private companion object {
        val FROM: LocalDate = LocalDate.of(2025, 1, 30)
        val TO: LocalDate = LocalDate.of(2025, 2, 2)
        const val PDF = "导出 PDF 并分享"
        const val TXT = "导出 TXT 并分享"
        fun dayStart(date: LocalDate): Long = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}
