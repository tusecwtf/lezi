package com.lezi.babylog.sync.engine

import java.io.IOException
import kotlinx.coroutines.CopyableThrowable
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * Fault-injection sentinel for tests that assert propagation and suppressed-error identity.
 * Coroutine stacktrace recovery copies ordinary IOExceptions across suspension boundaries.
 * Opt this sentinel out of copying so the tests can still detect wrapping, replacement, and
 * self-suppression without depending on whether coroutine debugging is enabled.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class IdentityPreservingIOException(message: String) :
    IOException(message), CopyableThrowable<IdentityPreservingIOException> {
    override fun createCopy(): IdentityPreservingIOException? = null
}
