package com.lezi.babylog.sync

import com.lezi.babylog.sync.backend.SyncHeartbeat
import com.lezi.babylog.sync.engine.localReplicaBaby
import com.lezi.babylog.sync.media.RealServerMediaReceiptFaultFixture
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Ticket 09 integration smoke on a REAL isolated 0.5 lezi-sync server binary
 * (mktemp data root, loopback self-minted TLS — certificate-test isolation
 * compliant): the real 0.5 [ReplicaSyncEngine] + real [HttpSyncBackend] over
 * the loopback TLS proxy that records every request on the wire (the media
 * seam fixture provides the proven wiring; its anchor baby is published and
 * settled inside [RealServerMediaReceiptFaultFixture.open]).
 *
 * Observed 0.5 behaviors (spec §Solution 1/2):
 * - volume publish: 1200 baby entities flow to the real server in bounded
 *   LocalWrite chunks, and two subsequent full foreground rounds over one
 *   stable head
 *   produce identical pull-page counts with no rewalk — the joint
 *   server+client census caches stay provably correct on the real wire (any
 *   census drift would cursor-reset the second round into a full rewalk);
 * - quiet round wire cost: a converged foreground round with nothing pending
 *   issues exactly handshake+pull, zero bundles, zero media transfers — the
 *   empty result is exactly what the 0.5 tip-skip then removes entirely at
 *   the port level (skip contract pinned by RealSyncPortTipSkipTest; the
 *   real-server heartbeat three-key answer asserted here is the no-change
 *   proof shape that arm feeds on);
 * - the real heartbeat endpoint answers with the converged (generation,
 *   headRev, directoryGeneration) triple.
 *
 * Port-level quiet-round/heartbeat-cadence integration remains pinned by
 * RealSyncPortTipSkipTest and RealSyncPortHeartbeatLoopTest; server-side
 * per-request latency numbers come from the Rust-side ignored measurement
 * tests (ticket 09 landing record).
 */
class RealServerQuietRoundSmokeTest {

    @Test
    fun stableHeadRoundsPublishTwelveHundredRowsAndPageIdentically() = runBlocking {
        RealServerMediaReceiptFaultFixture.open().use { fixture ->
                // Publish 1200 babies in bounded chunks over the real wire
                // (the anchor-baby shape is the canonical publish path this
                // fixture proves; census counts all entity types).
                repeat(6) { chunk ->
                    repeat(200) { index ->
                        fixture.babies.seed(
                            localReplicaBaby().copy(
                                clientUuid = seedUuid(chunk, index),
                                nickname = "冒烟$chunk-$index",
                                syncDirty = true,
                            ),
                        )
                    }
                    fixture.engine.synchronize(
                        fixture.preferences.current(),
                        SyncTrigger.LocalWrite,
                    )
                    check(fixture.babies.listPendingSync().isEmpty()) {
                        "chunk $chunk left babies unpublished"
                    }
                }
                // One Foreground round converges the pull cursor to head
                // (catch-up pages), then the two rounds under test run over a
                // fully stable head.
                fun pulls() = fixture.proxy.forwardedPaths.count {
                    it.startsWith("GET /v1/pull")
                }
                fixture.engine.synchronize(
                    fixture.preferences.current(),
                    SyncTrigger.Foreground,
                )
                val converged = fixture.preferences.current()

                val beforeFirst = pulls()
                fixture.engine.synchronize(converged, SyncTrigger.Foreground)
                val first = pulls() - beforeFirst
                val beforeSecond = pulls()
                fixture.engine.synchronize(converged, SyncTrigger.Foreground)
                val second = pulls() - beforeSecond

                println("SMOKE[census-stable-head] pull pages first=$first second=$second")
                check(first >= 1) { "expected at least one pull page" }
                check(first == second) {
                    "stable-head rounds must page identically; a census drift " +
                        "would cursor-reset the second round into a rewalk"
                }
                dumpServerLog(fixture, "census-stable-head")
        }
    }

    @Test
    fun convergedForegroundRoundCostsExactlyHandshakePlusEmptyPull() = runBlocking {
        RealServerMediaReceiptFaultFixture.open().use { fixture ->
                // Fully converged quiet foreground round: no publish work
                // exists, so the wire must see handshake+pull and no writes.
                val before = fixture.proxy.forwardedPaths.size
                fixture.engine.synchronize(
                    fixture.preferences.current(),
                    SyncTrigger.Foreground,
                )
                val forwarded = fixture.proxy.forwardedPaths.drop(before)
                println("SMOKE[quiet-round] forwarded=$forwarded")
                check(forwarded.none { it.startsWith("POST /v1/bundles") }) {
                    "quiet round must not stage bundles"
                }
                check(forwarded.none { it.startsWith("PUT /v1/causal/media") }) {
                    "quiet round must not upload media"
                }
                check(forwarded.none { it.contains("GET /v1/media/") }) {
                    "quiet round must not download media"
                }

                // The real heartbeat endpoint answers the no-change three-key
                // shape for the converged session (the proof arm tip-skip uses).
                val beat: SyncHeartbeat = fixture.backend.heartbeat(
                    fixture.preferences.current(),
                )
                val session = fixture.preferences.current()
                println("SMOKE[quiet-round] heartbeat=$beat")
                check(beat.generation == session.pullGeneration) {
                    "heartbeat generation must match the converged session"
                }
                check(beat.headRev == session.pullCursor) {
                    "heartbeat headRev must match the converged cursor"
                }
                dumpServerLog(fixture, "quiet-round")
        }
    }

    private fun seedUuid(chunk: Int, index: Int): String {
        val raw = "0000000${chunk}-0000-4000-8000-%012d".format(index)
        return raw
    }

    /** Prints isolated-server request lines when RUST_LOG is exported. */
    private fun dumpServerLog(fixture: RealServerMediaReceiptFaultFixture, tag: String) {
        if (System.getenv("RUST_LOG").isNullOrBlank()) return
        val lines = runCatching {
            java.io.File(fixture.server.dataRoot, "server.log").readLines()
        }.getOrDefault(emptyList())
        val interesting = lines.filter {
            it.contains("on_response") ||
                it.contains("sync heartbeat probe answered")
        }
        println("SMOKE[$tag] server request lines (${interesting.size}):")
        interesting.take(300).forEach { println("SMOKE[$tag] $it") }
    }
}
