package com.lezi.babylog.sync.media

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.backend.CausalCommitBatchResult
import com.lezi.babylog.sync.backend.CausalCommitRejectedException
import com.lezi.babylog.sync.backend.CausalMutationUnit
import com.lezi.babylog.sync.backend.ReauthRequiredException
import com.lezi.babylog.sync.backend.RefreshingSyncBackend
import com.lezi.babylog.sync.backend.RemoteDeviceRemovedException
import com.lezi.babylog.sync.backend.SessionRefreshResult
import com.lezi.babylog.sync.backend.SyncHttpException
import com.lezi.babylog.sync.backend.SyncBackend
import com.lezi.babylog.sync.engine.CausalMediaSettlementPhase
import com.lezi.babylog.sync.engine.decodeCausalMediaSettlementOrNull
import com.lezi.babylog.sync.session.PolicyClock
import com.lezi.babylog.sync.session.SyncSession
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/** Real Rust authentication replies through production HTTP parser + refresh + engine. */
class RealServerCommitAuthenticationTest {
    @Test
    fun commit401RefreshesAndReplaysTheIdenticalCanonicalMutation() = runBlocking {
        runCase("once")
    }

    @Test
    fun commit401AfterRefreshRequiresReauthAndRetainsTheFrozenUnknown() = runBlocking {
        runCase("always")
    }

    @Test
    fun revokedDeviceCommitConfirmsRemovalWithOneRefreshWithoutBusinessRejection() = runBlocking {
        runCase("revoked")
    }

    private suspend fun runCase(mode: String) {
        RealServerMediaReceiptFaultFixture.open(controlledReceiptClock = true).use { fixture ->
            val seeded = fixture.seedRecordMedia(
                recordUuid = "00000000-0000-4000-8000-000000002501",
                mediaUuid = "00000000-0000-4000-8000-000000002502",
                localUri = "content://synthetic/commit-auth", bytes = byteArrayOf(2, 5, 0, 1),
            )
            var commitCalls = 0
            val attemptedMutations = mutableListOf<List<CausalMutationUnit>>()
            val commitAuthFailures = mutableListOf<SyncHttpException>()
            val refreshAuthFailures = mutableListOf<SyncHttpException>()
            val instrumented = object : SyncBackend by fixture.backend {
                override suspend fun causalCommit(session: SyncSession, units: List<CausalMutationUnit>): CausalCommitBatchResult {
                    commitCalls++
                    attemptedMutations += units.toList()
                    if (mode == "revoked") {
                        // Use the public authority route so its cache invalidation
                        // and persistent revocation both take effect.
                        fixture.backend.revokeFamilyDevice(session, session.deviceId)
                    } else if (mode == "always" || commitCalls == 1) {
                        // Expire the real issued access token against the server's
                        // clock, including any already-warm auth-cache entry. Direct
                        // SQL edits would bypass that cache and do not simulate TTL.
                        fixture.server.setReceiptClock(session.accessExpiresAtEpochSeconds + 1)
                    }
                    return try {
                        fixture.backend.causalCommit(session, units)
                    } catch (failure: SyncHttpException) {
                        commitAuthFailures += failure
                        throw failure
                    }
                }

                override suspend fun refresh(
                    baseUrl: String,
                    refreshToken: String,
                    refreshRequestId: String,
                ): SessionRefreshResult = try {
                    fixture.backend.refresh(baseUrl, refreshToken, refreshRequestId)
                } catch (failure: SyncHttpException) {
                    refreshAuthFailures += failure
                    throw failure
                }
            }
            val refreshing = RefreshingSyncBackend(instrumented, fixture.preferences, object : PolicyClock {
                override fun nowMillis() = System.currentTimeMillis()
            })
            val beforeBodies = fixture.proxy.commitBodies.size
            val beforeStatuses = fixture.proxy.commitStatuses.size
            val beforePaths = fixture.proxy.forwardedPaths.size
            val original = fixture.preferences.current()
            val failure = runCatching {
                fixture.newEngine(refreshing).synchronize(original, SyncTrigger.LocalWrite)
            }.exceptionOrNull()
            val bodies = fixture.proxy.commitBodies.drop(beforeBodies)
            val statuses = fixture.proxy.commitStatuses.drop(beforeStatuses)
            val refreshes = fixture.proxy.forwardedPaths.drop(beforePaths).filter { it.contains("/refresh") }
            assertThat(failure).isNotInstanceOf(CausalCommitRejectedException::class.java)
            assertThat(bodies.distinct()).hasSize(1)
            assertThat(attemptedMutations.distinct()).hasSize(1)
            if (mode == "once") {
                assertThat(failure).isNull()
                assertThat(statuses).containsExactly(401, 200).inOrder()
                assertThat(refreshes).hasSize(1)
                assertThat(fixture.recordVersionCount()).isEqualTo(1)
                assertThat(fixture.records.getByClientUuid(seeded.recordUuid)?.syncDirty).isFalse()
                assertThat(fixture.preferences.current().refreshToken).isNotEqualTo(original.refreshToken)
            } else {
                assertThat(failure).isInstanceOf(
                    if (mode == "revoked") RemoteDeviceRemovedException::class.java else ReauthRequiredException::class.java,
                )
                assertThat(statuses).containsExactlyElementsIn(if (mode == "revoked") listOf(401) else listOf(401, 401)).inOrder()
                assertThat(refreshes).hasSize(1)
                if (mode == "revoked") {
                    // Commit deliberately closes every authentication failure as
                    // unauthenticated. Only the one refresh identifies removal;
                    // there must be no second commit or generic reauth cleanup.
                    assertThat(commitCalls).isEqualTo(1)
                    val rejectedCommit = commitAuthFailures.single()
                    assertThat(rejectedCommit.statusCode).isEqualTo(401)
                    assertThat(Json.parseToJsonElement(rejectedCommit.responseBody)).isEqualTo(
                        Json.parseToJsonElement(
                            """{"status":"rejected","error":{"code":"unauthenticated","retryable":false}}""",
                        ),
                    )
                    val rejectedRefresh = refreshAuthFailures.single()
                    assertThat(rejectedRefresh.statusCode).isEqualTo(401)
                    assertThat(Json.parseToJsonElement(rejectedRefresh.responseBody)
                        .jsonObject["code"]?.jsonPrimitive?.content).isEqualTo("device_removed")
                    assertThat(fixture.preferences.current().accessToken).isEqualTo(original.accessToken)
                    assertThat(fixture.preferences.current().refreshToken).isEqualTo(original.refreshToken)
                    assertThat(fixture.preferences.current().reauthRequired).isFalse()
                } else {
                    assertThat(refreshAuthFailures).isEmpty()
                }
                assertThat(fixture.recordVersionCount()).isEqualTo(0)
                val pendingRecord = requireNotNull(fixture.records.getByClientUuid(seeded.recordUuid))
                assertThat(pendingRecord.syncDirty).isTrue()
                assertThat(pendingRecord.mutationId).isNotEmpty()
                val pending = requireNotNull(decodeCausalMediaSettlementOrNull(
                    fixture.conflictDetails.listFrozenMediaSpoolManifests().single().payloadJson,
                ))
                // The journal is durably marked before sending. An auth error is
                // not an authoritative causal rejection and cannot retire evidence.
                assertThat(pending.phase).isEqualTo(CausalMediaSettlementPhase.CommitUnknown)
                assertThat(pending.mutation).isEqualTo(attemptedMutations.first().single())
                assertThat(pending.mutation.mutationId).isEqualTo(pendingRecord.mutationId)
                val item = pending.manifest.items.single()
                assertThat(item.mediaUuid).isEqualTo(seeded.mediaUuid)
                assertThat(item.byteSize).isEqualTo(seeded.frozenBytes.size.toLong())
                assertThat(item.sha256).isEqualTo(
                    com.lezi.babylog.core.common.MediaContentDigest.ofBytes(seeded.frozenBytes),
                )
                assertThat(fixture.immutableMediaSpool.open(pending.mutation.mutationId, item)
                    .openStream().use { it.readBytes() }).isEqualTo(seeded.frozenBytes)
                assertThat(fixture.immutableMediaSpool.discardedMutationIds).isEmpty()
            }
        }
    }
}
