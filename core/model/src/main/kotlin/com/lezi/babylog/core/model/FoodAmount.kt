package com.lezi.babylog.core.model

/**
 * Food amount text helpers shared by the entry composer and summary aggregation.
 *
 * `amount` stays a single free-form string on the wire and in storage; these helpers only
 * define the local read/write conventions on top of it:
 *
 * - [normalizeFoodAmountText] — entry-side convention: fold supported full-width characters and
 *   rewrite a leading single hanzi numeral (`半碗` → `0.5碗`) so newly written records start with
 *   a digit. Ambiguous combinations (`十几`, `一两`, `半2`, `几`) are deliberately left untouched.
 * - [parseFoodAmountValue] — display/aggregation side: read a bounded leading arabic number
 *   (decimals allowed, unit characters dropped, no unit conversion). Historical hanzi-only or
 *   malformed amounts parse to null and surface as「未填量」instead.
 *
 * Neither helper is used by [RecordPayloadCodec]; decode stays intentionally lax so records
 * written by older APKs remain visible.
 */

/** Leading single hanzi numerals convertible to a digit prefix. */
private val FOOD_AMOUNT_HANZI_DIGITS = mapOf(
    '半' to "0.5",
    '一' to "1",
    '两' to "2",
    '二' to "2",
    '三' to "3",
    '四' to "4",
    '五' to "5",
    '六' to "6",
    '七' to "7",
    '八' to "8",
    '九' to "9",
    '十' to "10",
)

/**
 * Numeral-ish hanzi anywhere after the leading character that make a lone prefix ambiguous
 * (`十几口`, `一两勺`, `半碗半`). Their presence blocks normalization entirely.
 */
private val FOOD_AMOUNT_AMBIGUOUS_HANZI = FOOD_AMOUNT_HANZI_DIGITS.keys + '几'
private const val FOOD_AMOUNT_MAX_VALUE = 1_000_000.0

private val FOOD_AMOUNT_DIGIT_PREFIX = Regex("""^(\d+(?:\.\d+)?)""")
private val FOOD_AMOUNT_UNEXPECTED_NUMBER_PUNCTUATION = setOf('.', ',')

/** Folds supported full-width input and rewrites an unambiguous leading hanzi numeral. */
fun normalizeFoodAmountText(raw: String): String {
    val trimmed = raw.trim()
    val text = if (trimmed.any { it in '０'..'９' || it == '．' || it == '。' || it == '，' }) {
        buildString(trimmed.length) {
            trimmed.forEach { character ->
                append(
                    when (character) {
                        in '０'..'９' -> '0' + (character - '０')
                        '．', '。' -> '.'
                        '，' -> ','
                        else -> character
                    },
                )
            }
        }
    } else {
        trimmed
    }
    val head = text.firstOrNull() ?: return text
    if (head !in FOOD_AMOUNT_HANZI_DIGITS) {
        return text
    }
    val rest = text.drop(1)
    if (
        rest.firstOrNull()?.isDigit() == true ||
        rest.any { it in FOOD_AMOUNT_AMBIGUOUS_HANZI }
    ) {
        return text
    }
    return FOOD_AMOUNT_HANZI_DIGITS.getValue(head) + rest
}

/** Reads one bounded leading arabic number; malformed numeric punctuation rejects the whole text. */
fun parseFoodAmountValue(amount: String?): Double? {
    val text = amount?.trim().orEmpty()
    val match = FOOD_AMOUNT_DIGIT_PREFIX.find(text) ?: return null
    if (text.getOrNull(match.range.last + 1) in FOOD_AMOUNT_UNEXPECTED_NUMBER_PUNCTUATION) {
        return null
    }
    return match.groupValues[1]
        .toDoubleOrNull()
        ?.takeIf { it.isFinite() && it <= FOOD_AMOUNT_MAX_VALUE }
}
