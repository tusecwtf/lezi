package com.lezi.gf.kernel

import java.util.UUID

@JvmInline
value class ClientUuid(val value: String) {
    init {
        require(value.isNotBlank()) { "client uuid blank" }
    }

    companion object {
        fun generate(): ClientUuid = ClientUuid(UUID.randomUUID().toString())
    }
}

@JvmInline
value class MembershipId(val value: String)

@JvmInline
value class DeviceId(val value: String)

@JvmInline
value class FamilyId(val value: String)

/** Clock seam for deterministic L1 tests. */
fun interface Clock {
    fun nowEpochMs(): Long
}

object SystemClock : Clock {
    override fun nowEpochMs(): Long = System.currentTimeMillis()
}

class FixedClock(private var ms: Long) : Clock {
    override fun nowEpochMs(): Long = ms
    fun advance(byMs: Long) {
        ms += byMs
    }
    fun set(ms: Long) {
        this.ms = ms
    }
}
