package com.lezi.gf.kernel

sealed class GfResult<out T> {
    data class Ok<T>(val value: T) : GfResult<T>()
    data class Err(val error: GfError) : GfResult<Nothing>()

    fun getOrNull(): T? = (this as? Ok)?.value
    fun errorOrNull(): GfError? = (this as? Err)?.error
    inline fun <R> map(transform: (T) -> R): GfResult<R> = when (this) {
        is Ok -> Ok(transform(value))
        is Err -> this
    }
    inline fun onSuccess(block: (T) -> Unit): GfResult<T> {
        if (this is Ok) block(value)
        return this
    }
}

sealed class GfError {
    data class Validation(val message: String) : GfError()
    data class NotFound(val message: String) : GfError()
    data class Forbidden(val message: String) : GfError()
    data class Conflict(val message: String) : GfError()
    data class CapabilityMissing(val capability: String) : GfError()
    data class TrustBlocked(val reason: String) : GfError()
    data class Network(val message: String) : GfError()
    data class Protocol(val message: String) : GfError()
    data class UnrecoverableLocalData(val message: String) : GfError()
    data class Other(val message: String) : GfError()
}
