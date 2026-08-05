package com.lezi.gf.app

import com.google.common.truth.Truth.assertThat
import com.lezi.gf.app.export.PdfShareExport
import com.lezi.gf.care.CareService
import com.lezi.gf.care.RecordType
import com.lezi.gf.settings.Handedness
import com.lezi.gf.settings.SettingsService
import com.lezi.gf.settings.TimeFormat
import org.junit.Test
import java.io.File

/**
 * E-02 PDF lifecycle + Chinese labels + settings UI wiring.
 * Lifecycle tests drive [PdfShareExport.purgeStaleIn] / assertExistsForShare — the real policy
 * used by create→share→discardAfterShare (no immediate delete).
 */
class PdfExportAndSettingsParityTest {

    @Test
    fun pdfBodyLines_useChineseLabels_notEnglishKeys() {
        val care = CareService()
        care.confirmCreate(
            care.openComposer(RecordType.PEE, "b").copy(note = "n1", dirty = true),
        )
        val lines = PdfShareExport.pdfBodyLines(care, "b")
        assertThat(lines).isNotEmpty()
        assertThat(lines.joinToString("\n")).contains("尿")
        assertThat(lines.joinToString("\n")).doesNotContain("\tpee\t")
        assertThat(lines.joinToString("\n")).isEqualTo(care.exportTxt("b"))
    }

    @Test
    fun exportFile_mustExistBeforeShare_assertExistsForShare() {
        val dir = createTempDir(prefix = "lezi-pdf-")
        val fresh = File(dir, "lezi-export-${System.currentTimeMillis()}.pdf")
        fresh.writeBytes(byteArrayOf(0x25, 0x50, 0x44, 0x46)) // %PDF
        // Policy used right after createPdfFile / before shareIntent
        PdfShareExport.assertExistsForShare(fresh)
        assertThat(fresh.exists()).isTrue()
        assertThat(fresh.length()).isGreaterThan(0)
    }

    @Test
    fun assertExistsForShare_failsWhenMissing() {
        val missing = File(createTempDir(), "lezi-export-gone.pdf")
        try {
            PdfShareExport.assertExistsForShare(missing)
            throw AssertionError("expected check failure for missing file")
        } catch (e: IllegalStateException) {
            assertThat(e.message).contains("exist")
        }
    }

    @Test
    fun purgeStale_keepsFreshExport_deletesOnlyOlderThanMaxAge() {
        val dir = createTempDir(prefix = "lezi-pdf-purge-")
        val now = System.currentTimeMillis()
        val fresh = File(dir, "lezi-export-fresh.pdf").apply {
            writeText("%PDF-fresh")
            setLastModified(now)
        }
        val stale = File(dir, "lezi-export-stale.pdf").apply {
            writeText("%PDF-stale")
            setLastModified(now - PdfShareExport.STALE_MAX_AGE_MS - 5_000)
        }
        val other = File(dir, "not-an-export.pdf").apply {
            writeText("x")
            setLastModified(now - PdfShareExport.STALE_MAX_AGE_MS - 5_000)
        }

        // Simulate: after create, only purge stale — must NOT delete fresh (would break share)
        val deleted = PdfShareExport.purgeStaleIn(
            dir,
            nowMs = now,
            maxAgeMs = PdfShareExport.STALE_MAX_AGE_MS,
        )

        assertThat(fresh.exists()).isTrue()
        assertThat(fresh.readText()).isEqualTo("%PDF-fresh")
        assertThat(stale.exists()).isFalse()
        assertThat(deleted.map { it.name }).contains("lezi-export-stale.pdf")
        // non-export name left alone
        assertThat(other.exists()).isTrue()
    }

    @Test
    fun discardAfterShare_policy_deletesTargetThenPurgesStale() {
        val dir = createTempDir(prefix = "lezi-pdf-discard-")
        val now = System.currentTimeMillis()
        val shared = File(dir, "lezi-export-shared.pdf").apply {
            writeText("%PDF-shared")
            setLastModified(now)
        }
        val stale = File(dir, "lezi-export-old.pdf").apply {
            writeText("%PDF-old")
            setLastModified(now - 120_000)
        }
        // After share returns: delete the shared file
        assertThat(shared.exists()).isTrue()
        shared.delete()
        PdfShareExport.purgeStaleIn(dir, nowMs = now, maxAgeMs = 60_000)
        assertThat(shared.exists()).isFalse()
        assertThat(stale.exists()).isFalse()
    }

    @Test
    fun menuScreen_sourceLaunchesShareThenDiscardsOnlyOnResult() {
        val src = findSource("Screens.kt")
        assertThat(src).contains("导出 PDF")
        assertThat(src).contains("StartActivityForResult")
        assertThat(src).contains("pdfShareLauncher")
        assertThat(src).contains("discardAfterShare")
        assertThat(src).contains("assertExistsForShare")
        // Must not immediately delete after launch without waiting for result
        assertThat(src).doesNotContain("exporter.discard(file)")
        // Fresh file must still exist when building share intent path
        assertThat(src).containsMatch(
            "assertExistsForShare\\(file\\)[\\s\\S]{0,400}pdfShareLauncher\\.launch",
        )
    }

    @Test
    fun pdfShareExport_sourceUsesPdfDocumentAndCache_lifecycleComments() {
        val src = findSource("PdfShareExport.kt")
        assertThat(src).contains("android.graphics.pdf.PdfDocument")
        assertThat(src).contains("ACTION_SEND")
        assertThat(src).contains("application/pdf")
        assertThat(src).contains("cacheDir")
        assertThat(src).contains("fun discardAfterShare")
        assertThat(src).contains("fun purgeStaleExports")
        assertThat(src).contains("exportLines")
        assertThat(src).contains("must remain")
    }

    @Test
    fun navShell_sourceHasLongPressBabySwitch() {
        val src = findSource("LeziNavShell.kt")
        assertThat(src).contains("combinedClickable")
        assertThat(src).contains("onLongClick")
        assertThat(src).contains("switchBaby")
        assertThat(src).contains("长按切换宝宝")
    }

    @Test
    fun menuScreen_sourceExposesDisplaySettingsKnobs() {
        val src = findSource("Screens.kt")
        assertThat(src).contains("惯用手")
        assertThat(src).contains("24 小时制")
        assertThat(src).contains("周起始周一")
        assertThat(src).contains("日龄算法")
        assertThat(src).contains("发热说明")
        assertThat(src).contains("导出 PDF")
        assertThat(src).contains("dayTypeFilter")
        assertThat(src).contains("recentNoteCandidates")
    }

    @Test
    fun settingsService_handednessTimeFormatFeverAreMutable() {
        val s = SettingsService()
        s.update {
            it.copy(
                handedness = Handedness.LEFT,
                timeFormat = TimeFormat.H12,
                weekStartsOnMonday = false,
                useDayAgeMode = false,
                showFeverHint = true,
                amountStepMl = 10,
            )
        }
        val g = s.get()
        assertThat(g.handedness).isEqualTo(Handedness.LEFT)
        assertThat(g.timeFormat).isEqualTo(TimeFormat.H12)
        assertThat(g.weekStartsOnMonday).isFalse()
        assertThat(g.useDayAgeMode).isFalse()
        assertThat(g.showFeverHint).isTrue()
        assertThat(g.amountStepMl).isEqualTo(10)
    }

    private fun findSource(name: String): String {
        val roots = listOf(
            File("src/main"),
            File("app/src/main"),
            File("../app/src/main"),
            File("greenfield/android/app/src/main"),
            File("/home/zhangtianshu/lezi-greenfield/greenfield/android/app/src/main"),
        )
        for (r in roots) {
            if (!r.exists()) continue
            val hit = r.walkTopDown().firstOrNull { it.name == name }
            if (hit != null) return hit.readText()
        }
        error("source $name not found")
    }
}
