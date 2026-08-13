package com.lezi.babylog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConflictOverlayStateTest {
    @Test
    fun familyInboxHandsConflictIdToTheSingleResolverOverlay() {
        val inbox = ConflictOverlayState().openInbox()
        assertTrue(inbox.inboxVisible)
        assertNull(inbox.resolverConflictId)

        val resolver = inbox.openResolver("conflict-family")
        assertFalse(resolver.inboxVisible)
        assertEquals("conflict-family", resolver.resolverConflictId)
        assertEquals(ConflictOverlayState(), resolver.dismissResolver())
    }

    @Test
    fun recordContextOpensTheSameResolverWithoutAnInboxBackstackEntry() {
        val resolver = ConflictOverlayState().openResolver("conflict-record")

        assertFalse(resolver.inboxVisible)
        assertEquals("conflict-record", resolver.resolverConflictId)
    }
}
