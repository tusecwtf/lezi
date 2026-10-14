package com.lezi.babylog

import com.lezi.babylog.baseline.MediaContentDigest as BaselineDigest
import com.lezi.babylog.baseline.retainedMediaSha256 as baselineRetainedMediaSha256
import com.lezi.babylog.core.common.MediaContentDigest
import com.sun.management.ThreadMXBean
import java.io.File
import java.lang.management.ManagementFactory
import java.util.Locale

@Volatile
private var consumed = ""

/** Local JVM microbenchmark; not a device latency or full sync benchmark. */
fun main() {
    val allocation = ManagementFactory.getThreadMXBean() as ThreadMXBean
    check(allocation.isThreadAllocatedMemorySupported)
    allocation.isThreadAllocatedMemoryEnabled = true
    val digest = ByteArray(32) { (it * 31).toByte() }
    val file = File.createTempFile("lezi-retained-media-benchmark", ".bin")
    try {
        val block = ByteArray(64 * 1024) { (it * 31).toByte() }
        file.outputStream().use { output -> repeat(256) { output.write(block) } }
        println("java=${System.getProperty("java.runtime.version")}")
        println("jvm_flags=${ManagementFactory.getRuntimeMXBean().inputArguments.joinToString(" ")}")
        println("file_bytes=${file.length()},file_sha256=${MediaContentDigest.ofReadableFile(file)}")
        println("rounds=30,hex_warmups=4000,hex_iterations=10000,file_warmups=8,file_iterations=8")
        println("case,variant,round,ns_per_op,allocated_bytes_per_op")
        compare(
            name = "hex_32_bytes",
            warmups = 4_000,
            iterations = 10_000,
            allocation = allocation,
            baseline = { BaselineDigest.hex(digest) },
            current = { MediaContentDigest.hex(digest) },
        )
        compare(
            name = "retained_media_16_mib",
            warmups = 8,
            iterations = 8,
            allocation = allocation,
            baseline = { baselineRetainedMediaSha256(file) },
            current = { file.inputStream().use { retainedMediaSha256(it, file.path) } },
        )
    } finally {
        check(file.delete())
    }
}

private data class Sample(val nanos: Double, val allocatedBytes: Double)

private fun compare(
    name: String,
    warmups: Int,
    iterations: Int,
    allocation: ThreadMXBean,
    baseline: () -> String,
    current: () -> String,
) {
    check(baseline() == current()) { "$name digest mismatch" }
    repeat(warmups) {
        consumed = baseline()
        consumed = current()
    }
    val before = mutableListOf<Sample>()
    val after = mutableListOf<Sample>()
    repeat(30) { round ->
        // Alternate order to limit simple ordering/cache bias.
        if (round % 2 == 0) {
            before += sample(iterations, allocation, baseline)
            after += sample(iterations, allocation, current)
        } else {
            after += sample(iterations, allocation, current)
            before += sample(iterations, allocation, baseline)
        }
        for ((variant, sample) in listOf("baseline" to before.last(), "current" to after.last())) {
            println(String.format(
                Locale.ROOT, "%s,%s,%d,%.1f,%.1f", name, variant, round + 1,
                sample.nanos, sample.allocatedBytes,
            ))
        }
    }
    for ((variant, samples) in listOf("baseline" to before, "current" to after)) {
        val nanos = samples.map { it.nanos }.sorted()
        val bytes = samples.map { it.allocatedBytes }.sorted()
        println(String.format(
            Locale.ROOT,
            "summary,%s,%s,median_ns=%.1f,p25_ns=%.1f,p75_ns=%.1f,min_ns=%.1f,max_ns=%.1f,median_allocated_bytes=%.1f",
            name, variant, percentile(nanos, 0.5), percentile(nanos, 0.25),
            percentile(nanos, 0.75), nanos.first(), nanos.last(), percentile(bytes, 0.5),
        ))
    }
}

private fun percentile(sorted: List<Double>, fraction: Double): Double {
    val position = fraction * (sorted.size - 1)
    val lower = position.toInt()
    val upper = (lower + 1).coerceAtMost(sorted.lastIndex)
    return sorted[lower] + (position - lower) * (sorted[upper] - sorted[lower])
}

private fun sample(iterations: Int, allocation: ThreadMXBean, block: () -> String): Sample {
    val threadId = Thread.currentThread().threadId()
    val beforeBytes = allocation.getThreadAllocatedBytes(threadId)
    val beforeNanos = System.nanoTime()
    repeat(iterations) { consumed = block() }
    val nanos = System.nanoTime() - beforeNanos
    val bytes = allocation.getThreadAllocatedBytes(threadId) - beforeBytes
    return Sample(nanos.toDouble() / iterations, bytes.toDouble() / iterations)
}
