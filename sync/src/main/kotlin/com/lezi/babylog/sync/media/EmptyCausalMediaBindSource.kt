package com.lezi.babylog.sync.media

import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Zero-body causal media PUT. [declaredByteSize] is the family blob size the
 * receipt must echo; [contentLength] is always 0 so the wire sends no bytes.
 */
class EmptyCausalMediaBindSource(
    override val declaredByteSize: Long,
    override val mime: String?,
) : SyncMediaUploadSource {
    init {
        require(declaredByteSize > 0L) { "bind declared byte size must be positive" }
    }

    override val contentLength: Long = 0L

    override fun openStream(): InputStream = ByteArrayInputStream(ByteArray(0))
}
