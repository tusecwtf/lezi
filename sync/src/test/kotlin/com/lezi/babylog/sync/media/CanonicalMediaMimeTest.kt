package com.lezi.babylog.sync.media

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertThrows
import org.junit.Test

class CanonicalMediaMimeTest {
    @Test fun rawMetadataStillAcceptsItsOriginalLosslessDomain() {
        for (mime in listOf(null, "", "  ", "x".repeat(255), "😀".repeat(255)))
            assertThat(requireRawMediaMime(mime)).isEqualTo(mime)
    }
    @Test fun schema13CanonicalMimePreservesExactNonemptyUtf8ValuesWithin128Bytes() {
        for (mime in listOf("image/jpeg", "  ", "x".repeat(128), "😀".repeat(32))) {
            assertThat(requireCanonicalMediaMime(mime)).isEqualTo(mime)
            assertThat(parseCanonicalMediaMime(JsonPrimitive(mime))).isEqualTo(mime)
        }
    }
    @Test fun broaderRawDomainDoesNotWidenTheCanonicalSchema13Domain() {
        for (mime in listOf(null, "", "x".repeat(129), "😀".repeat(33)))
            assertThrows(IllegalArgumentException::class.java) { requireCanonicalMediaMime(mime) }
        assertThrows(IllegalArgumentException::class.java) { parseCanonicalMediaMime(JsonNull) }
    }
    @Test fun missingNonstringAndMalformedRawUnicodeFailClosed() {
        assertThrows(IllegalArgumentException::class.java) { parseCanonicalMediaMime(null) }
        assertThrows(IllegalArgumentException::class.java) { parseCanonicalMediaMime(JsonPrimitive(3)) }
        assertThrows(IllegalArgumentException::class.java) { requireRawMediaMime("😀".repeat(256)) }
        assertThrows(IllegalArgumentException::class.java) { requireRawMediaMime("\uD800") }
    }
}
