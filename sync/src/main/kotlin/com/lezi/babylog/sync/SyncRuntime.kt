package com.lezi.babylog.sync

import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

fun interface PolicyClock {
    fun nowMillis(): Long
}

interface ForegroundState {
    fun isForeground(): Boolean
    fun setForeground(value: Boolean)
}

@Singleton
class ProcessForegroundState @Inject constructor() : ForegroundState {
    private val foreground = AtomicBoolean(false)

    override fun isForeground(): Boolean = foreground.get()

    override fun setForeground(value: Boolean) {
        foreground.set(value)
    }
}

class SystemPolicyClock @Inject constructor() : PolicyClock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}
