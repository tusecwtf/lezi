package com.lezi.babylog.sync.media

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import org.junit.Test

class MediaPrepareFailureTest {
    @Test
    fun outOfMemoryBecomesTypedRetryablePrepareFailure() {
        val failure = runCatching {
            translateMediaPrepareOutOfMemory {
                throw OutOfMemoryError("decode allocation")
            }
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(MediaPrepareException::class.java)
        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(failure?.cause).isInstanceOf(OutOfMemoryError::class.java)
    }
}
