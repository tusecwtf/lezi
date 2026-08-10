package com.lezi.babylog.feature.log.timeline

import androidx.lifecycle.SavedStateHandle
import com.lezi.babylog.domain.carelog.ConflictResolverSavedState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConflictResolverSessionStateTest {
    @Test
    fun processRecreationRestoresOneOpaqueAttemptFrame() {
        val handle = SavedStateHandle()
        val expected = validState(submitted = true)
        ConflictResolverSessionState(handle).persist(expected)

        val restored = ConflictResolverSessionState(handle).restore(CONFLICT_ID)

        assertEquals(expected, restored)
        assertEquals(setOf(FRAME_KEY), handle.keys())
    }

    @Test
    fun wrongConflictClearsTheWholeAttempt() {
        val handle = SavedStateHandle()
        val store = ConflictResolverSessionState(handle)
        store.persist(validState())

        assertNull(store.restore(FOREIGN_CONFLICT_ID))
        assertNull(store.restore(CONFLICT_ID))
        assertNull(handle.get<ArrayList<String>>(FRAME_KEY))
    }

    @Test
    fun everyTornFrameShapeClearsInsteadOfPartiallyRestoring() {
        val source = SavedStateHandle().also {
            ConflictResolverSessionState(it).persist(validState(submitted = true))
        }.get<ArrayList<String>>(FRAME_KEY)!!

        source.indices.forEach { missingIndex ->
            val torn = ArrayList(source).also { it.removeAt(missingIndex) }
            val handle = SavedStateHandle(mapOf(FRAME_KEY to torn))

            assertNull(
                "missing frame index $missingIndex",
                ConflictResolverSessionState(handle).restore(CONFLICT_ID),
            )
            assertNull(handle.get<ArrayList<String>>(FRAME_KEY))
        }
    }

    @Test
    fun nonCanonicalTokenMutationPointerAndChoiceClearTheAttempt() {
        val canonical = SavedStateHandle().also {
            ConflictResolverSessionState(it).persist(validState())
        }.get<ArrayList<String>>(FRAME_KEY)!!
        val corruptions = listOf(
            2 to "a".repeat(42),
            3 to "00000000-0000-0000-0000-0000000000AB",
            6 to "note",
            7 to "choice-note",
        )
        corruptions.forEach { (index, value) ->
            val frame = ArrayList(canonical).also { it[index] = value }
            val handle = SavedStateHandle(mapOf(FRAME_KEY to frame))

            assertNull(ConflictResolverSessionState(handle).restore(CONFLICT_ID))
            assertNull(handle.get<ArrayList<String>>(FRAME_KEY))
        }
    }

    private fun validState(submitted: Boolean = false) = ConflictResolverSavedState(
        conflictId = CONFLICT_ID,
        snapshotToken = "a".repeat(43),
        resolutionMutationId = "00000000-0000-0000-0000-000000000020",
        selectedChoiceIds = mapOf(
            "/note" to "b".repeat(43),
            "/timestamp" to "c".repeat(43),
        ),
        submitted = submitted,
    )
}

private const val FRAME_KEY = "conflict_resolver_attempt_v1"
private const val CONFLICT_ID = "00000000-0000-0000-0000-000000000010"
private const val FOREIGN_CONFLICT_ID = "00000000-0000-0000-0000-000000000011"
