package com.lezi.babylog.feature.export

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

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
}
