package com.lezi.babylog.domain

import java.util.Base64
import org.junit.rules.TemporaryFolder

/** Real, readable synthetic PNG bytes for existing public attachment-inheritance controls. */
internal fun TemporaryFolder.syntheticPhoto(name: String): String = newFile(name).also { file ->
    file.writeBytes(Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNgYGD4DwABBAEAX+XDSwAAAABJRU5ErkJggg==",
    ))
}.path
