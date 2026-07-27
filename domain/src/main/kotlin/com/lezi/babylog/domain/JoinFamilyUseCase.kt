package com.lezi.babylog.domain

import com.lezi.babylog.sync.JoinFamilyDraft
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.SyncSession
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.joinFamilyError
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

data class JoinFamilyRequest(
    val draft: JoinFamilyDraft,
    val displayName: String,
)

sealed interface JoinFamilyResult {
    data class Joined(val session: SyncSession) : JoinFamilyResult

    data class Failed(val message: String) : JoinFamilyResult
}

/**
 * The single join-family seam shared by onboarding and the account flow.
 *
 * Callers provide form state and render [JoinFamilyResult]. Validation, local scaffold
 * preparation, server join, membership-name caching, and the immediate pull request remain
 * ordered inside this module.
 */
interface JoinFamilyUseCase {
    suspend fun execute(request: JoinFamilyRequest): JoinFamilyResult
}

@Singleton
internal class DefaultJoinFamilyUseCase private constructor(
    private val sync: SyncPort,
    private val localStore: JoinFamilyLocalStore,
) : JoinFamilyUseCase {
    @Inject
    constructor(
        sync: SyncPort,
        careLog: CareLog,
    ) : this(sync, CareLogJoinFamilyLocalStore(careLog))

    internal constructor(
        localStore: JoinFamilyLocalStore,
        sync: SyncPort,
    ) : this(sync, localStore)

    override suspend fun execute(request: JoinFamilyRequest): JoinFamilyResult {
        val command = try {
            request.draft.toCommand(request.displayName)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return failure(error)
        }

        try {
            localStore.ensureScaffold()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return failure(JoinFamilyPreparationException(error))
        }

        val session = try {
            sync.joinFamily(command).getOrElse { error ->
                if (error is CancellationException) throw error
                return failure(error)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return failure(error)
        }

        try {
            localStore.cacheDisplayName(command.displayName)
        } catch (_: Exception) {
            // The server join and local session are already committed. A local
            // display-name cache failure or cancellation must not turn that
            // success into a retryable join failure.
        }
        try {
            sync.requestSync(SyncTrigger.PullToRefresh)
        } catch (_: Exception) {
            // Pull scheduling is best-effort after the joined session has been
            // committed. Cancellation before/during join still escapes above;
            // the next foreground trigger can retry this post-commit step.
        }
        return JoinFamilyResult.Joined(session)
    }

    private fun failure(error: Throwable): JoinFamilyResult.Failed =
        JoinFamilyResult.Failed(joinFamilyError(error))
}

internal interface JoinFamilyLocalStore {
    suspend fun ensureScaffold()

    suspend fun cacheDisplayName(displayName: String)
}

private class CareLogJoinFamilyLocalStore(
    private val careLog: CareLog,
) : JoinFamilyLocalStore {
    override suspend fun ensureScaffold() {
        careLog.ensureFamilyScaffold()
    }

    override suspend fun cacheDisplayName(displayName: String) {
        careLog.updateLocalDisplayName(displayName)
    }
}

private class JoinFamilyPreparationException(cause: Throwable) :
    IllegalStateException("准备本机家庭失败", cause)
