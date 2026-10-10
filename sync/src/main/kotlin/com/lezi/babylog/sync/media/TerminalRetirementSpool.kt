package com.lezi.babylog.sync.media

import java.io.File

/** No arbitrary path deletion. The file spool mints this lease while its donor mutex is held. */
internal class TerminalSpoolLease(
    val paths: TerminalSpoolPaths,
    private val unlink: suspend () -> Unit,
) {
    suspend fun discard() = unlink()
}

internal data class TerminalSpoolPaths(val root: File, val directory: File, val media: List<File>)

/** Only the terminal retirement owner uses this capability; ordinary spool fakes fail closed. */
internal interface TerminalRetirementSpool {
    suspend fun recoverAndSweepRetainingOpaque(
        retainedMutationIds: Set<String>,
        opaqueGroups: List<ImmutableMediaSpoolGroup>,
    ): Map<String, ImmutableMediaSpoolRecovery>
    fun ownedGroupPaths(group: ImmutableMediaSpoolGroup): TerminalSpoolPaths
    suspend fun <T> withRetirementGroup(
        group: ImmutableMediaSpoolGroup,
        deleting: Boolean,
        block: suspend (TerminalSpoolLease) -> T,
    ): T
}
