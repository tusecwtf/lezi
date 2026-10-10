package com.lezi.babylog.core.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomItemSaveCommandTest {
    @Test fun retainedSaveRejectsAnotherDraftUntilOriginalOutcomeIsPublished() = runBlocking {
        val owner = CustomItemSaveCommand()
        val finish = CompletableDeferred<String?>()
        val original = CustomItemSaveDraft(7, "散步", 2)
        val pending = launch(start = CoroutineStart.UNDISPATCHED) {
            owner.save(original) { finish.await() }
        }
        assertTrue(owner.state.value.saving)
        assertFalse(owner.save(CustomItemSaveDraft(null, "新草稿", 3)) { null })
        finish.complete("项目已不存在")
        pending.join()
        // A newly mounted view observes the original identity and error without old callbacks.
        assertEquals(original, owner.state.value.draft)
        assertEquals("项目已不存在", owner.state.value.error)
        assertTrue(owner.state.value.completed)
        owner.consume()
        assertFalse(owner.state.value.completed)
    }

    @Test fun unexpectedWriteFailureStillReleasesTheRetainedSaveGate() = runBlocking {
        val owner = CustomItemSaveCommand()
        val draft = CustomItemSaveDraft(null, "散步", 0)
        owner.save(draft) { error("storage failure") }
        assertFalse(owner.state.value.saving)
        assertTrue(owner.state.value.completed)
        assertEquals(draft, owner.state.value.draft)
    }
}
