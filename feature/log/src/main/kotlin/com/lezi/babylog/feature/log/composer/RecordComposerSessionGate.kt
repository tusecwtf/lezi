package com.lezi.babylog.feature.log.composer
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.timeline.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.layout.*
import com.lezi.babylog.feature.log.photo.*
internal data class RecordComposerSessionToken(
    val generation: Long,
)

/**
 * Routes asynchronous Composer results to the request that started them.
 *
 * ViewModel work runs on the main dispatcher, so opening or closing a request
 * cannot interleave with [deliver] while its non-suspending callback executes.
 */
internal class RecordComposerSessionGate {
    private var nextGeneration = 0L
    private var activeToken: RecordComposerSessionToken? = null

    fun open(): RecordComposerSessionToken {
        val token = RecordComposerSessionToken(generation = ++nextGeneration)
        activeToken = token
        return token
    }

    fun close() {
        activeToken = null
    }

    fun current(): RecordComposerSessionToken? = activeToken

    fun deliver(
        token: RecordComposerSessionToken,
        result: () -> Unit,
    ): Boolean {
        if (activeToken != token) return false
        result()
        return true
    }
}
