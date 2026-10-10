package com.lezi.babylog.sync.media

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** Raw restore metadata remains valid independently of its canonical compatibility. */
internal fun requireRawMediaMime(value: String?): String? = value.also {
    if (value != null) {
        var scalars = 0
        var offset = 0
        while (offset < value.length) {
            val char = value[offset++]
            if (Character.isHighSurrogate(char)) {
                require(offset < value.length && Character.isLowSurrogate(value[offset++])) {
                    "media MIME contains an invalid Unicode scalar"
                }
            } else {
                require(!Character.isLowSurrogate(char)) { "media MIME contains an invalid Unicode scalar" }
            }
            scalars++
        }
        require(scalars <= CausalMediaPolicy.maxMimeUnicodeScalars) { "media MIME exceeds 255 Unicode scalars" }
    }
}

/** Exact historical schema13 canonical domain; never normalize a raw value to fit it. */
internal fun requireCanonicalMediaMime(value: String?): String {
    requireRawMediaMime(value)
    require(value != null && value.isNotEmpty() && value.toByteArray(Charsets.UTF_8).size <= 128) {
        "media MIME is outside the schema13 canonical representation"
    }
    return value
}

internal fun parseRawMediaMime(value: JsonElement?): String? {
    require(value != null) { "media MIME field is missing" }
    if (value == JsonNull) return null
    require(value is JsonPrimitive && value.isString) { "media MIME must be a string or null" }
    return requireRawMediaMime(value.content)
}

internal fun parseCanonicalMediaMime(value: JsonElement?): String =
    requireCanonicalMediaMime(parseRawMediaMime(value))
