package com.lezi.babylog

import com.lezi.babylog.baseline.BaselineContext
import com.lezi.babylog.baseline.BaselineStore
import com.lezi.babylog.baseline.MediaContentDigest as BaselineDigest
import com.lezi.babylog.baseline.readBytesUpTo
import com.lezi.babylog.core.common.MediaContentDigest
import com.lezi.babylog.sync.backend.BodyConnection
import com.lezi.babylog.sync.backend.MAX_SYNC_MEDIA_RESPONSE_BYTES
import com.lezi.babylog.sync.backend.copyCurrentBody
import com.lezi.babylog.sync.media.OwnedMediaDownloadStore
import com.sun.management.ThreadMXBean
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** Body read + hash + successful save/ownership release; no network, TLS, Room or image decode. */
fun main() = runBlocking(Dispatchers.IO) {
    val allocation = ManagementFactory.getThreadMXBean() as ThreadMXBean
    check(allocation.isThreadAllocatedMemorySupported)
    allocation.isThreadAllocatedMemoryEnabled = true
    val root = Files.createTempDirectory("lezi-streaming-benchmark").toFile()
    try {
        val input = File(root, "body.bin")
        input.outputStream().use { out ->
            val chunk = ByteArray(8_192) { (it * 31 + 11).toByte() }
            repeat(1_024) { out.write(chunk) }
        }
        val expected = MediaContentDigest.ofReadableFile(input)
        val oldRoot = File(root, "baseline").apply { mkdir() }
        val newRoot = File(root, "current").apply { mkdir() }
        val baselineStore = BaselineStore(BaselineContext(oldRoot))
        val currentStore = OwnedMediaDownloadStore(newRoot)
        val uuid = UUID.fromString("11111111-1111-4111-8111-111111111111").toString()
        suspend fun baseline(): File {
            val bytes = input.inputStream().use {
                it.readBytesUpTo(MAX_SYNC_MEDIA_RESPONSE_BYTES, "媒体", input.length())
            }
            // Pre-streaming digest slice validates once and reuses this digest for its staged row.
            check(BaselineDigest.ofBytes(bytes) == expected)
            return File(baselineStore.saveDownloaded(uuid, "log", bytes, "image/jpeg"))
        }
        suspend fun current(): File {
            val target = currentStore.begin(uuid, "log", "image/jpeg")
            val file = target.use {
                val receipt = copyCurrentBody(BodyConnection(input.inputStream()), target.output,
                    MAX_SYNC_MEDIA_RESPONSE_BYTES, input.length())
                check(receipt.sha256 == expected && receipt.byteCount == input.length())
                File(target.complete(receipt))
            }
            target.release { /* Successful Room adoption: keep bytes, retire only the marker. */ }
            return file
        }
        suspend fun sample(variant: String): Sample {
            val thread = Thread.currentThread().threadId()
            val beforeBytes = allocation.getThreadAllocatedBytes(thread)
            val beforeNanos = System.nanoTime()
            val file = if (variant == "baseline") baseline() else current()
            val elapsed = System.nanoTime() - beforeNanos
            val allocated = allocation.getThreadAllocatedBytes(thread) - beforeBytes
            check(Thread.currentThread().threadId() == thread) { "Dispatcher moved the measured operation" }
            check(file.length() == input.length())
            // Final sample-file deletion is outside both measurements.
            check(file.delete())
            if (variant == "current") check(file.parentFile.delete())
            return Sample(elapsed.toDouble(), allocated.toDouble(), thread)
        }
        println("scope=host successful response-body-to-adopted-file; excludes HTTP/TLS/Room/device")
        println("java=${System.getProperty("java.runtime.version")}")
        println("jvm_flags=${ManagementFactory.getRuntimeMXBean().inputArguments.joinToString(" ")}")
        println("input_bytes=${input.length()},input_sha256=$expected")
        println("rounds=30,warmups_per_variant=10,iterations_per_sample=1,allocation=current_IO_thread_bytes")
        repeat(10) { sample("baseline"); sample("current") }
        val results = mapOf("baseline" to mutableListOf<Sample>(), "current" to mutableListOf<Sample>())
        println("variant,round,nanos,allocated_bytes,thread_id")
        repeat(30) { round ->
            val order = if (round % 2 == 0) listOf("baseline", "current") else listOf("current", "baseline")
            for (variant in order) {
                val result = sample(variant)
                results.getValue(variant) += result
                println("$variant,${round + 1},${result.nanos.toLong()},${result.allocated.toLong()},${result.threadId}")
            }
        }
        for ((variant, samples) in results) {
            val times = samples.map { it.nanos }.sorted()
            val bytes = samples.map { it.allocated }.sorted()
            println(String.format(Locale.ROOT,
                "summary,%s,median_ns=%.1f,p25_ns=%.1f,p75_ns=%.1f,min_ns=%.1f,max_ns=%.1f,median_allocated_bytes=%.1f",
                variant, percentile(times, .5), percentile(times, .25), percentile(times, .75),
                times.first(), times.last(), percentile(bytes, .5)))
        }
    } finally { root.deleteRecursively() }
}
private data class Sample(val nanos: Double, val allocated: Double, val threadId: Long)
private fun percentile(values: List<Double>, fraction: Double): Double {
    val index = fraction * (values.size - 1)
    val lower = index.toInt()
    return values[lower] + (index - lower) * (values[(lower + 1).coerceAtMost(values.lastIndex)] - values[lower])
}
