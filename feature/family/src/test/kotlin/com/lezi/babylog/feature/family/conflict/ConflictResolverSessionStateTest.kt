package com.lezi.babylog.feature.family.conflict

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
        assertEquals(expected, ConflictResolverSessionState(handle).restore(CONFLICT_ID))
        assertEquals(setOf(FRAME_KEY), handle.keys())
    }

    @Test
    fun wrongConflictAndEveryTornFrameClearTheWholeAttempt() {
        val handle = SavedStateHandle()
        ConflictResolverSessionState(handle).persist(validState())
        assertNull(ConflictResolverSessionState(handle).restore(FOREIGN_CONFLICT_ID))
        assertNull(ConflictResolverSessionState(handle).restore(CONFLICT_ID))

        val canonical = SavedStateHandle().also {
            ConflictResolverSessionState(it).persist(validState(submitted = true))
        }.get<ArrayList<String>>(FRAME_KEY)!!
        canonical.indices.forEach { missingIndex ->
            val torn = ArrayList(canonical).also { it.removeAt(missingIndex) }
            val tornHandle = SavedStateHandle(mapOf(FRAME_KEY to torn))
            assertNull(ConflictResolverSessionState(tornHandle).restore(CONFLICT_ID))
            assertNull(tornHandle.get<ArrayList<String>>(FRAME_KEY))
        }
    }

    private fun validState(submitted: Boolean = false) = ConflictResolverSavedState(
        conflictId = CONFLICT_ID,
        snapshotToken = "a".repeat(43),
        resolutionMutationId = "00000000-0000-0000-0000-000000000020",
        selectedChoiceIds = mapOf("/note" to "b".repeat(43)),
        submitted = submitted,
    )
}

private const val FRAME_KEY = "conflict_resolver_attempt_v1"
private const val CONFLICT_ID = "00000000-0000-0000-0000-000000000010"
private const val FOREIGN_CONFLICT_ID = "00000000-0000-0000-0000-000000000011"
