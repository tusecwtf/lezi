package com.lezi.babylog.sync.backend.deadline

import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException

fun interface FamilyHttpNameResolver {
    fun resolve(host: String): Array<InetAddress>
}

internal object SystemFamilyHttpNameResolver : FamilyHttpNameResolver {
    override fun resolve(host: String): Array<InetAddress> = InetAddress.getAllByName(host)
}

// Some platform resolvers do not promptly react to interruption. Bound both
// running work and queued references; saturation must never run DNS on callers.
// Keep the existing two-worker cap and allow only one waiting wave (two tasks)
// across concurrent trusted-endpoint probes, heartbeat, and family HTTP callers.
internal fun newFamilyHttpDnsExecutor(): ThreadPoolExecutor = ThreadPoolExecutor(
    2, 2, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(2),
    { runnable -> Thread(runnable, "lezi-family-http-dns").apply { isDaemon = true } },
    ThreadPoolExecutor.AbortPolicy(),
)

private val familyHttpDnsExecutor = newFamilyHttpDnsExecutor()

internal fun resolveFamilyHttpHost(
    host: String,
    timeoutMillis: Int,
    resolver: FamilyHttpNameResolver,
    executor: ThreadPoolExecutor = familyHttpDnsExecutor,
): Array<InetAddress> {
    val normalized = host.trim()
    require(normalized.isNotEmpty()) { "家庭服务器地址缺少主机名" }
    if (isLiteralIpAddress(normalized)) {
        return arrayOf(InetAddress.getByName(normalized))
    }
    // timeoutMillis is the caller's already-bounded remaining connect allowance.
    // Charge submission/queue time to that same allowance, never reset it at get().
    val startedAtNanos = System.nanoTime()
    val allowanceNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis.toLong())
    fun remainingNanos(): Long = allowanceNanos - (System.nanoTime() - startedAtNanos)
    val future = FutureTask { resolver.resolve(normalized) }
    try {
        if (remainingNanos() <= 0) throw TimeoutException("DNS deadline exhausted before submission")
        try {
            executor.execute(future)
        } catch (saturated: RejectedExecutionException) {
            // No resolver slot became available within this attempt. Fail closed
            // as DNS-unavailable, without sleeping, replaying, or starting a worker.
            throw FamilyHttpException(FamilyHttpFailureKind.AddressNotFound, saturated)
        }
        val remaining = remainingNanos()
        if (remaining <= 0) throw TimeoutException("DNS deadline exhausted before wait")
        val addresses = future.get(remaining, TimeUnit.NANOSECONDS)
        if (remainingNanos() <= 0) throw TimeoutException("DNS result arrived after deadline")
        if (addresses.isNullOrEmpty()) {
            throw FamilyHttpException(FamilyHttpFailureKind.AddressNotFound)
        }
        return addresses
    } catch (timeout: TimeoutException) {
        throw FamilyHttpException(FamilyHttpFailureKind.AddressNotFound, timeout)
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        throw CancellationException("家庭 HTTP 域名解析已取消").apply {
            initCause(interrupted)
        }
    } catch (execution: ExecutionException) {
        val cause = execution.cause ?: execution
        if (cause is UnknownHostException || cause is FamilyHttpException) {
            throw classifyFamilyHttpFailure(cause)
        }
        throw classifyFamilyHttpFailure(cause, operation = null)
    } finally {
        future.cancel(true)
        // FutureTask.cancel marks a queued task but does not unlink it.
        executor.remove(future)
    }
}

internal fun isLiteralIpAddress(host: String): Boolean {
    if (host.startsWith('[') && host.endsWith(']')) {
        return true
    }
    if (host.all { it.isDigit() || it == '.' }) {
        return host.split('.').size == 4
    }
    return host.contains(':')
}
