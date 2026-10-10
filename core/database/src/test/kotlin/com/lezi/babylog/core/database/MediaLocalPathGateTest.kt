package com.lezi.babylog.core.database

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaLocalPathGateTest {
    @Test
    fun largeRestoreAcquiresAndReleasesWithoutRecursiveStackGrowth() = runBlocking {
        val gate = MediaLocalPathGate()
        val paths = (0 until 20_000).map { "photo-$it" }
        assertEquals(42, gate.withLocks(paths) { 42 })
        assertEquals(43, gate.withLocks(paths.reversed()) { 43 })
        Unit
    }

    @Test
    fun cancellationReleasesEarlierLocksWhileWaitingForAnotherPath() = runBlocking {
        val gate = MediaLocalPathGate()
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch { gate.withLock("b") { held.complete(Unit); release.await() } }
        held.await()
        val waiter = launch { gate.withLocks(listOf("a", "b")) { error("must remain blocked") } }
        kotlinx.coroutines.yield()
        waiter.cancelAndJoin()
        withTimeout(1_000) { assertEquals("released", gate.withLock("a") { "released" }) }
        release.complete(Unit)
        holder.join()
    }
}
