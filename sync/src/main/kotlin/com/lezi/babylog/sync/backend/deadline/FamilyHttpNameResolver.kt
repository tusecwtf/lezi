package com.lezi.babylog.sync.backend.deadline

import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException

fun interface FamilyHttpNameResolver {
    fun resolve(host: String): Array<InetAddress>
}

internal object SystemFamilyHttpNameResolver : FamilyHttpNameResolver {
    override fun resolve(host: String): Array<InetAddress> = InetAddress.getAllByName(host)
}

private val familyHttpDnsExecutor: ExecutorService = Executors.newFixedThreadPool(2) { runnable ->
    Thread(runnable, "lezi-family-http-dns").apply { isDaemon = true }
}

internal fun resolveFamilyHttpHost(
    host: String,
    timeoutMillis: Int,
    resolver: FamilyHttpNameResolver,
): Array<InetAddress> {
    val normalized = host.trim()
    require(normalized.isNotEmpty()) { "家庭服务器地址缺少主机名" }
    if (isLiteralIpAddress(normalized)) {
        return arrayOf(InetAddress.getByName(normalized))
    }
    val future: Future<Array<InetAddress>> = familyHttpDnsExecutor.submit<Array<InetAddress>> {
        resolver.resolve(normalized)
    }
    try {
        val addresses = future.get(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
        if (addresses.isNullOrEmpty()) {
            throw FamilyHttpException(FamilyHttpFailureKind.AddressNotFound)
        }
        return addresses
    } catch (timeout: TimeoutException) {
        future.cancel(true)
        throw FamilyHttpException(FamilyHttpFailureKind.AddressNotFound, timeout)
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        future.cancel(true)
        throw CancellationException("家庭 HTTP 域名解析已取消").apply {
            initCause(interrupted)
        }
    } catch (execution: ExecutionException) {
        val cause = execution.cause ?: execution
        if (cause is UnknownHostException || cause is FamilyHttpException) {
            throw classifyFamilyHttpFailure(cause)
        }
        throw classifyFamilyHttpFailure(cause, operation = null)
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
