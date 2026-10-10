package com.lezi.babylog.validation

import com.lezi.babylog.core.database.LeziDatabase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Call only after verifying a fresh synthetic sandbox; no thread/process observer. */
internal class ProductionRoomTransactionLease(db: LeziDatabase, beforeCommit: () -> Unit = {}) : AutoCloseable {
    private val entered = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val finished = CountDownLatch(1)
    private val failure = AtomicReference<Throwable?>()
    private val aborted = AtomicBoolean(false)
    init {
        db.transactionExecutor.execute {
            try {
                db.runInTransaction {
                    entered.countDown()
                    check(release.await(90, TimeUnit.SECONDS)) { "UI fixture transaction lease expired" }
                    if (!aborted.get()) {
                        beforeCommit()
                        // A release timeout during the callback must roll its write back.
                        check(!aborted.get()) { "Room transaction fixture was aborted before commit" }
                    }
                }
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                finished.countDown()
            }
        }
        if (!entered.await(10, TimeUnit.SECONDS)) {
            // A queued runnable may acquire Room after this constructor times out.
            // Revoke its write before releasing it; await only this fixture's task.
            aborted.set(true)
            release.countDown()
            val timeout = AssertionError("Room transaction fixture did not acquire its lease")
            if (!finished.await(10, TimeUnit.SECONDS)) {
                timeout.addSuppressed(AssertionError("Timed-out Room fixture cleanup is still pending; write remains revoked"))
            }
            failure.get()?.let(timeout::addSuppressed)
            throw timeout
        }
    }
    fun releaseAndAwait() {
        release.countDown()
        if (!finished.await(10, TimeUnit.SECONDS)) {
            aborted.set(true)
            error("Room transaction fixture did not release")
        }
        failure.get()?.let { throw AssertionError("Room transaction fixture failed", it) }
    }
    override fun close() {
        // Failed assertions before the explicit release must clean up the lease,
        // not commit a synthetic tombstone after the test has already failed.
        if (release.count > 0) aborted.set(true)
        releaseAndAwait()
    }
}
