package com.lezi.babylog.domain.carelog
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.businessLabel
import org.junit.Test
import com.lezi.babylog.domain.CareLog

/**
 * Locks the SQL-facing search helpers that production [CareLog.search] feeds
 * into Room `LIKE … ESCAPE '\'`. These pure tests are the JVM stand-in for
 * real DAO semantics without requiring instrumented Room.
 */
class RecordSearchTest {

    @Test
    fun everyTypeSearchesByTheCanonicalUserVisibleLabel() {
        RecordType.entries.forEach { type ->
            assertThat(type.candidateSearchTerms().first()).isEqualTo(type.businessLabel())
            assertThat(type.candidateSearchTerms()).contains(type.businessLabel())
        }
    }

    @Test
    fun toSqlLikePattern_wrapsAndEscapesMetacharacters() {
        assertThat("hello".toSqlLikePattern()).isEqualTo("%hello%")
        assertThat("a%b".toSqlLikePattern()).isEqualTo("%a\\%b%")
        assertThat("a_b".toSqlLikePattern()).isEqualTo("%a\\_b%")
        assertThat("a\\b".toSqlLikePattern()).isEqualTo("%a\\\\b%")
        assertThat("%".toSqlLikePattern()).isEqualTo("%\\%%")
        assertThat("_".toSqlLikePattern()).isEqualTo("%\\_%")
        assertThat("\\".toSqlLikePattern()).isEqualTo("%\\\\%")
        assertThat("100%_x\\y".toSqlLikePattern()).isEqualTo("%100\\%\\_x\\\\y%")
    }

    @Test
    fun toSqlLikePattern_withSqlLike_matchesLiteralSubstringOnly() {
        // Production path: LOWER(note) LIKE escapedPattern ESCAPE '\'
        val note = "配方 100% 浓度 a_b path\\file"
        assertThat(matchesSqlLike(note.lowercase(), "100%".toSqlLikePattern())).isTrue()
        assertThat(matchesSqlLike(note.lowercase(), "100".toSqlLikePattern())).isTrue()
        assertThat(matchesSqlLike(note.lowercase(), "a_b".toSqlLikePattern())).isTrue()
        assertThat(matchesSqlLike(note.lowercase(), "path\\file".toSqlLikePattern())).isTrue()

        // Unescaped wildcard semantics must NOT apply to user query chars.
        // If `%` were a wildcard, "100%" would match any "100…" string.
        assertThat(matchesSqlLike("100ml formula".lowercase(), "100%".toSqlLikePattern()))
            .isFalse()
        // If `_` were a single-char wildcard, "a_b" would match "axb".
        assertThat(matchesSqlLike("axb".lowercase(), "a_b".toSqlLikePattern())).isFalse()
        // Backslash is literal, not an escape introducer in the user needle.
        assertThat(matchesSqlLike("pathfile".lowercase(), "path\\file".toSqlLikePattern()))
            .isFalse()
    }

    @Test
    fun toSqlLikePattern_emptyAndUnicode() {
        assertThat("".toSqlLikePattern()).isEqualTo("%%")
        assertThat(matchesSqlLike("anything", "".toSqlLikePattern())).isTrue()
        assertThat(matchesSqlLike("布洛芬 2.5ml", "布洛芬".toSqlLikePattern())).isTrue()
        assertThat(matchesSqlLike("布洛芬 2.5ml", "xyz".toSqlLikePattern())).isFalse()
    }

    @Test
    fun payloadSearchNeedle_stripsKnownUnitSuffixes() {
        assertThat("120ml".payloadSearchNeedle()).isEqualTo("120")
        assertThat("120毫升".payloadSearchNeedle()).isEqualTo("120")
        assertThat("6.35kg".payloadSearchNeedle()).isEqualTo("6.35")
        assertThat("37.5℃".payloadSearchNeedle()).isEqualTo("37.5")
        assertThat("30分钟".payloadSearchNeedle()).isEqualTo("30")
        assertThat("30分".payloadSearchNeedle()).isEqualTo("30")
        assertThat("50cm".payloadSearchNeedle()).isEqualTo("50")
        assertThat("布洛芬".payloadSearchNeedle()).isEqualTo("布洛芬")
        assertThat("ml".payloadSearchNeedle()).isEqualTo("ml")
        // Bare unit-only after strip would be empty → keep original.
        assertThat("ml".removeSuffix("ml").trim()).isEmpty()
        assertThat("分钟".payloadSearchNeedle()).isEqualTo("分钟")
    }

    @Test
    fun payloadSearchNeedle_thenToSqlLike_matchesPayloadSubstring() {
        val payload = """{"amount_ml":120,"unit":"ml"}"""
        val needle = "120ml".payloadSearchNeedle().toSqlLikePattern()
        assertThat(needle).isEqualTo("%120%")
        assertThat(matchesSqlLike(payload.lowercase(), needle)).isTrue()
    }

    @Test
    fun sqlLike_escapeItselfAndAdjacentMetas() {
        assertThat(matchesSqlLike("100%_done", "%100\\%\\_done%")).isTrue()
        assertThat(matchesSqlLike("100XYdone", "%100\\%\\_done%")).isFalse()
        assertThat(matchesSqlLike("a\\b", "%a\\\\b%")).isTrue()
        assertThat(matchesSqlLike("ab", "%a\\\\b%")).isFalse()
    }

    @Test
    fun typeTermMatchesQuery_latinAliasUsesPrefixNotMidSubstring() {
        assertThat(typeTermMatchesQuery("pee", "e")).isFalse()
        assertThat(typeTermMatchesQuery("sleep", "e")).isFalse()
        assertThat(typeTermMatchesQuery("formula", "a")).isFalse()
        assertThat(typeTermMatchesQuery("bath", "a")).isFalse()
        assertThat(typeTermMatchesQuery("pee", "p")).isTrue()
        assertThat(typeTermMatchesQuery("pee", "pe")).isTrue()
        assertThat(typeTermMatchesQuery("pee", "pee")).isTrue()
        assertThat(typeTermMatchesQuery("formula", "form")).isTrue()
        assertThat(typeTermMatchesQuery("both diaper", "diaper")).isTrue()
        assertThat(typeTermMatchesQuery("pumped feed", "pump")).isTrue()
    }

    @Test
    fun typeTermMatchesQuery_chineseKeepsSubstring() {
        assertThat(typeTermMatchesQuery("尿尿", "尿")).isTrue()
        assertThat(typeTermMatchesQuery("换尿布", "尿布")).isTrue()
        assertThat(typeTermMatchesQuery("配方奶", "配方")).isTrue()
        assertThat(typeTermMatchesQuery("睡眠", "xyz")).isFalse()
    }

    @Test
    fun typeTermMatchesQuery_longerQueryMayContainUnitToken() {
        // Unit-suffixed numeric queries still classify milk/weight candidates.
        assertThat(typeTermMatchesQuery("ml", "120ml")).isTrue()
        assertThat(typeTermMatchesQuery("kg", "6.35kg")).isTrue()
        assertThat(typeTermMatchesQuery("ml", "ml")).isTrue()
        // But short query must not mid-hit a longer unit/alias.
        assertThat(typeTermMatchesQuery("ml", "m")).isTrue() // prefix
        assertThat(typeTermMatchesQuery("ml", "l")).isFalse()
    }
}
