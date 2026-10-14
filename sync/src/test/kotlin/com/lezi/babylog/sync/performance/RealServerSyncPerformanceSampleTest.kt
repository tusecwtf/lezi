package com.lezi.babylog.sync.performance

import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.IsolatedLeziSyncServer
import com.lezi.babylog.sync.media.RealServerMediaReceiptFaultFixture
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit exploratory sample: real engine + HTTP/TLS + isolated server, in-memory DAOs. */
class RealServerSyncPerformanceSampleTest {
    @Test
    fun warmedQuietRoundsRetainThirtyRawProductionPhaseSamples() = runBlocking {
        assumeTrue("opt-in performance sample", System.getenv("LEZI_SYNC_PERF_SAMPLE") == "1")
        val sourceSha = requireNotNull(System.getenv("LEZI_SYNC_SOURCE_SHA"))
        require(sourceSha.matches(Regex("[0-9a-f]{40}"))) { "exact source commit SHA required" }
        val git = ProcessBuilder("git", "rev-parse", "HEAD")
            .directory(IsolatedLeziSyncServer.resolveRepoRoot())
            .start()
        val checkoutSha = git.inputStream.bufferedReader().use { it.readText().trim() }
        check(git.waitFor() == 0 && checkoutSha == sourceSha) { "source SHA must match checkout HEAD" }
        val workflowSha = System.getenv("GITHUB_SHA")?.takeIf {
            it.matches(Regex("[0-9a-f]{40}"))
        } ?: "none"
        val binary = File(requireNotNull(System.getenv("LEZI_SYNC_BIN")))
        require(binary.isFile && binary.canExecute()) { "explicit tree server binary required" }
        val digest = MessageDigest.getInstance("SHA-256")
        binary.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                digest.update(buffer, 0, count)
            }
        }
        val binarySha = digest.digest().joinToString("") { "%02x".format(it) }
        check(SyncPerformanceProbe.snapshot() == null) { "capture must not already be running" }
        RealServerMediaReceiptFaultFixture.open().use { fixture ->
            SyncPerformanceProbe.startCapture()
            try {
                repeat(5) {
                    fixture.engine.synchronize(fixture.preferences.current(), SyncTrigger.Foreground)
                }
            } finally {
                // Exercise the same enabled probe path, then discard all warmup values.
                SyncPerformanceProbe.stopCapture()
            }
            println("SYNC_PERF source_sha=$sourceSha server_sha256=$binarySha")
            println("SYNC_PERF workflow_sha=$workflowSha")
            println("SYNC_PERF runtime=${System.getProperty("java.runtime.version")}")
            println("SYNC_PERF workload=quiet_foreground anchor_babies=1 media=0 warmup=5 samples=30")
            println("SYNC_PERF environment=cloud_jvm_loopback_tls memory_daos=1 device=0 nas=0")
            println("SYNC_PERF_RAW sample,stage,ordinal,duration_nanos,succeeded")
            val firstMeasuredRequest = fixture.proxy.forwardedPaths.size
            try {
                repeat(30) { sample ->
                    SyncPerformanceProbe.startCapture()
                    fixture.engine.synchronize(fixture.preferences.current(), SyncTrigger.Foreground)
                    val rows = requireNotNull(SyncPerformanceProbe.stopCapture())
                    check(rows.sumOf { it.activeSpans } == 0)
                    check(rows.sumOf { it.failedCount } == 0L)
                    check(rows[SyncPerformanceStage.ReplicaRound.id].completedCount == 1L)
                    check(rows[SyncPerformanceStage.Handshake.id].completedCount == 1L)
                    check(rows[SyncPerformanceStage.PullRequest.id].completedCount >= 1L)
                    rows.forEach { row ->
                        check(row.completedCount == row.samples.size.toLong()) {
                            "sample exceeded raw retention capacity"
                        }
                        row.samples.forEachIndexed { ordinal, value ->
                            println(
                                "SYNC_PERF_RAW $sample,${row.stageId},$ordinal," +
                                    "${value.durationNanos},${value.succeeded}",
                            )
                        }
                    }
                }
                val requests = fixture.proxy.forwardedPaths.drop(firstMeasuredRequest)
                check(requests.all {
                    it.startsWith("POST /v1/sync/handshake") || it.startsWith("GET /v1/pull")
                }) { "quiet sample must contain only handshake and pull, no heartbeat or writes" }
            } finally {
                SyncPerformanceProbe.stopCapture()
            }
        }
    }
}
