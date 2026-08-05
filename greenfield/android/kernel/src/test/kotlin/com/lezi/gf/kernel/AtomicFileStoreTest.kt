package com.lezi.gf.kernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class AtomicFileStoreTest {
    @Test
    fun writeAtomicRoundTripAndCorruptDetection() {
        val dir = createTempDirectory("gf-atomic").toFile()
        try {
            val target = File(dir, "care.json")
            AtomicFileStore.writeAtomicText(target, """{"records":[],"plans":[]}""")
            assertTrue(target.isFile)
            assertEquals("""{"records":[],"plans":[]}""", AtomicFileStore.readText(target))
            assertFalse(AtomicFileStore.isLikelyCorruptJsonObject(AtomicFileStore.readText(target)!!))

            // Overwrite atomically
            AtomicFileStore.writeAtomicText(target, """{"records":[{"clientUuid":"a"}],"plans":[]}""")
            assertTrue(AtomicFileStore.readText(target)!!.contains("clientUuid"))

            assertTrue(AtomicFileStore.isLikelyCorruptJsonObject(""))
            assertTrue(AtomicFileStore.isLikelyCorruptJsonObject("{"))
            assertTrue(AtomicFileStore.isLikelyCorruptJsonObject("""{"a":1"""))
            assertTrue(AtomicFileStore.isLikelyCorruptJsonObject("not-json"))
            assertFalse(AtomicFileStore.isLikelyCorruptJsonObject("{}"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
