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
    fun cancelledTxtWriteRemovesPartialFileAndAllowsAnotherExport() {
        val dir = temp.newFolder("cancelled-export")
        var checkpoints = 0
        val failure = runCatching {
            ExportFileGenerator.writeTxtFile(
                exportDir = dir,
                body = "宝宝记录".repeat(10_000),
                checkpoint = {
                    if (++checkpoints == 3) throw kotlinx.coroutines.CancellationException("leave export")
                },
            )
        }.exceptionOrNull()

        assertTrue(failure is kotlinx.coroutines.CancellationException)
        assertTrue(dir.listFiles().orEmpty().isEmpty())
        val retry = ExportFileGenerator.writeTxtFile(dir, "再试一次")
        assertTrue(retry.readText() == "再试一次")
    }

    @Test
    fun nextProcessCleanupRemovesAbandonedStagingButPreservesCurrentWorkAndShares() {
        val dir = temp.newFolder("abandoned-staging")
        val abandoned = File(dir, "previous-process-attempt.partial").apply { writeText("unfinished") }
        val request = File(dir, "previous-process-attempt.request").apply { writeText("private request") }
        val active = File(dir, ExportCacheCleanup.newAttemptToken() + ".partial").apply { writeText("active") }
        val shared = File(dir, "lezi-shared.pdf").apply { writeText("complete") }
        ExportCacheCleanup.deleteFiles(ExportCacheCleanup.staleFiles(dir.listFiles()!!.toList(), System.currentTimeMillis()))
        assertFalse(abandoned.exists())
        assertFalse(request.exists())
        assertTrue(active.exists())
        assertTrue(shared.exists())
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
