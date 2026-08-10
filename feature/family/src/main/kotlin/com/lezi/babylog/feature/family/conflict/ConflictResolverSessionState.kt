package com.lezi.babylog.feature.family.conflict

import androidx.lifecycle.SavedStateHandle
import com.lezi.babylog.domain.carelog.ConflictResolverSavedState
import com.lezi.babylog.sync.conflict.ConflictSnapshotValidation

/** Process-death state for the single app-shell resolution attempt. */
internal class ConflictResolverSessionState(
    private val savedState: SavedStateHandle,
) {
    fun persist(state: ConflictResolverSavedState) {
        validateHeader(state.conflictId, state.snapshotToken, state.resolutionMutationId)
        val sortedChoices = state.selectedChoiceIds.toSortedMap()
        require(sortedChoices.size <= ConflictSnapshotValidation.MAX_RESOLUTION_PATHS)
        sortedChoices.forEach { (path, choiceId) ->
            ConflictSnapshotValidation.requirePointer(path, "resolver saved path")
            ConflictSnapshotValidation.requireRuntimeToken(choiceId, "resolver saved choice_id")
        }
        require(!state.submitted || sortedChoices.isNotEmpty())
        savedState[FRAME] = ArrayList(buildList {
            add(FRAME_VERSION)
            add(state.conflictId)
            add(state.snapshotToken)
            add(state.resolutionMutationId)
            add(if (state.submitted) SUBMITTED else DRAFT)
            add(sortedChoices.size.toString())
            sortedChoices.forEach { (path, choiceId) ->
                add(path)
                add(choiceId)
            }
        })
    }

    fun restore(conflictId: String): ConflictResolverSavedState? {
        val frame = savedState.get<ArrayList<String>>(FRAME) ?: return null
        return runCatching { decodeFrame(frame, conflictId) }.getOrNull().also { restored ->
            if (restored == null) clear()
        }
    }

    fun clear() {
        savedState.remove<ArrayList<String>>(FRAME)
    }

    private fun decodeFrame(frame: List<String>, requestedConflictId: String): ConflictResolverSavedState {
        require(frame.size >= HEADER_SIZE && frame[0] == FRAME_VERSION)
        val conflictId = frame[1]
        val token = frame[2]
        val mutation = frame[3]
        val submitted = when (frame[4]) {
            SUBMITTED -> true
            DRAFT -> false
            else -> error("resolver saved submitted 无效")
        }
        val count = frame[5].toIntOrNull()
            ?.takeIf { it in 0..ConflictSnapshotValidation.MAX_RESOLUTION_PATHS }
            ?: error("resolver saved choice count 无效")
        require(frame.size == HEADER_SIZE + count * 2)
        require(conflictId == requestedConflictId)
        validateHeader(conflictId, token, mutation)
        val choices = linkedMapOf<String, String>()
        repeat(count) { index ->
            val path = frame[HEADER_SIZE + index * 2]
            val choiceId = frame[HEADER_SIZE + index * 2 + 1]
            ConflictSnapshotValidation.requirePointer(path, "resolver saved path")
            ConflictSnapshotValidation.requireRuntimeToken(choiceId, "resolver saved choice_id")
            require(choices.put(path, choiceId) == null)
        }
        require(choices.keys.toList() == choices.keys.sorted())
        require(!submitted || choices.isNotEmpty())
        return ConflictResolverSavedState(
            conflictId,
            token,
            mutation,
            choices,
            submitted,
        )
    }

    private fun validateHeader(conflictId: String, token: String, mutation: String) {
        ConflictSnapshotValidation.requireUuid(conflictId, "resolver saved conflict_id")
        ConflictSnapshotValidation.requireRuntimeToken(token, "resolver saved snapshot_token")
        ConflictSnapshotValidation.requireUuid(mutation, "resolver saved resolution_mutation_id")
    }

    private companion object {
        const val FRAME = "conflict_resolver_attempt_v1"
        const val FRAME_VERSION = "1"
        const val DRAFT = "0"
        const val SUBMITTED = "1"
        const val HEADER_SIZE = 6
    }
}
