/// Kotlin/JVM Char.isWhitespace is Java isWhitespace OR isSpaceChar.
/// Source: https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.text/is-whitespace.html
/// The explicit scalar set preserves the existing wire normalization contract:
/// NEL U+0085 is not whitespace; U+001C...U+001F and all Zs/Zl/Zp are.
/// Non-BMP scalars are not whitespace, just as neither UTF-16 surrogate is.
public enum AndroidText {
    public static func isBlank(_ value: String) -> Bool {
        value.unicodeScalars.allSatisfy(whitespace)
    }

    public static func trimmedStart(_ value: String) -> String {
        String(value.unicodeScalars.drop(while: whitespace))
    }

    public static func trimmed(_ value: String) -> String {
        let scalars = value.unicodeScalars
        var start = scalars.startIndex
        var end = scalars.endIndex
        while start < end, whitespace(scalars[start]) { start = scalars.index(after: start) }
        while start < end {
            let previous = scalars.index(before: end)
            guard whitespace(scalars[previous]) else { break }
            end = previous
        }
        return String(scalars[start..<end])
    }

    private static func whitespace(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar.value {
        case 0x09...0x0D, 0x1C...0x20, 0xA0, 0x1680, 0x2000...0x200A,
             0x2028, 0x2029, 0x202F, 0x205F, 0x3000: return true
        default: return false
        }
    }
}
