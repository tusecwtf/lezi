package com.lezi.babylog.core.common.failure

import java.io.IOException

/** Domain result for a stalled album copy. Classifier maps this to [FailureKind.AlbumReadStalled]. */
class AlbumReadStalledException(
    cause: Throwable? = null,
) : IOException("相册里的照片读不完", cause)
