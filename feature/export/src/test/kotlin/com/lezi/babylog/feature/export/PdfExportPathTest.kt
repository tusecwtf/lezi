package com.lezi.babylog.feature.export

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.LocalDate

class PdfExportPathTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun onlyAllowsCanonicalRecordMediaChildren() {
        val filesDir = temp.newFolder("files")
        val media = File(filesDir, "record-media").apply { mkdirs() }
        val ok = File(media, "shot.jpg").apply { writeText("x") }
        val outside = File(filesDir, "secrets.txt").apply { writeText("secret") }
        val nestedEscape = File(media, "nested").apply { mkdirs() }
        val traversal = File(nestedEscape, "../../secrets.txt")

        assertTrue(PdfExport.isUnderCanonicalRoot(media, ok))
        assertFalse(PdfExport.isUnderCanonicalRoot(media, outside))
        assertFalse(PdfExport.isUnderCanonicalRoot(media, traversal))
        assertFalse(PdfExport.isUnderCanonicalRoot(media, File("/data/local/tmp/pwn.jpg")))
    }

    @Test
    fun clearExportCacheDeletesFiles() {
        val dir = temp.newFolder("export")
        val leftover = File(dir, "old.pdf").apply { writeText("pdf") }
        PdfExport.clearExportCache(dir)
        assertFalse(leftover.exists())
    }

    @Test
    fun largePhotosAreDecodedNearPdfResolution() {
        assertTrue(PdfExport.sampleSizeFor(8_000, 6_000, 1_190, 1_484) >= 4)
        assertTrue(PdfExport.sampleSizeFor(800, 600, 1_190, 1_484) == 1)
    }

    @Test
    fun txtExportIsAnUtf8FileInsideExportCache() {
        val dir = temp.newFolder("txt-export")
        val file = ExportFileGenerator.writeTxtFile(
            exportDir = dir,
            body = "宝宝记录\n体温 37.2℃",
            date = LocalDate.of(2026, 7, 26),
        )

        assertTrue(file.name.endsWith(".txt"))
        assertTrue(file.canonicalPath.startsWith(dir.canonicalPath + File.separator))
        assertTrue(file.readBytes().toString(StandardCharsets.UTF_8).contains("体温 37.2℃"))
    }

    @Test
    fun cleanupDeletesOnlyAgedExportsAndNeverUsesChooserLaunchAsCompletion() {
        val dir = temp.newFolder("aged-export-cleanup")
        val now = 2 * ExportCacheCleanup.STALE_EXPORT_AGE_MS
        val stale = File(dir, "stale.pdf").apply {
            writeText("private health data")
            setLastModified(now - ExportCacheCleanup.STALE_EXPORT_AGE_MS - 1)
        }
        val fresh = File(dir, "fresh.txt").apply {
            writeText("active share")
            setLastModified(now - ExportCacheCleanup.STALE_EXPORT_AGE_MS + 1)
        }

        val targets = ExportCacheCleanup.staleFiles(listOf(stale, fresh), now)
        ExportCacheCleanup.deleteFiles(targets)

        assertFalse(stale.exists())
        assertTrue(fresh.exists())
    }
}
