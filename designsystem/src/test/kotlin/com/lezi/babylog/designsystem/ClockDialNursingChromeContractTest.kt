package com.lezi.babylog.designsystem

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket 03 (ui-drawing-polish): high-frequency clock dial + nursing confirm chrome
 * must route through Lezi wrappers — same language as quick-record composer.
 *
 * Public seams observed without private helpers:
 * - [LeziClockDialDialog] confirm/dismiss, date dialog, dropdown fields
 * - [LeziNursingConfirmFields] order chips + integer fields
 * Geometry, DST reject string, digit filters, and contentDescriptions stay fixed.
 */
class ClockDialNursingChromeContractTest {
    @Test
    fun `clock dial actions fields and date shell use Lezi wrappers not bare Material`() {
        val source = read(
            "designsystem/src/main/kotlin/com/lezi/babylog/designsystem/ClockDial.kt",
        )

        // Confirm / dismiss on the main dial dialog and date shell.
        assertTrue(source.contains("LeziTextButton("))
        assertTrue(source.contains("LeziDatePickerDialog("))
        assertTrue(source.contains("LeziTextField("))

        assertFalse(
            "clock dial must not import bare Material TextButton",
            source.contains("import androidx.compose.material3.TextButton"),
        )
        assertFalse(
            "clock dial must not call bare TextButton(",
            bareCall("TextButton").containsMatchIn(source),
        )
        assertFalse(
            "clock dial must not import bare OutlinedTextField",
            source.contains("import androidx.compose.material3.OutlinedTextField"),
        )
        assertFalse(
            "clock dial must not call bare OutlinedTextField(",
            bareCall("OutlinedTextField").containsMatchIn(source),
        )
        assertFalse(
            "clock dial must not import bare DatePickerDialog",
            source.contains("import androidx.compose.material3.DatePickerDialog"),
        )
        assertFalse(
            "clock dial must not call bare DatePickerDialog(",
            bareCall("DatePickerDialog").containsMatchIn(source),
        )
    }

    @Test
    fun `clock dial keeps geometry content descriptions and DST reject chrome`() {
        val source = read(
            "designsystem/src/main/kotlin/com/lezi/babylog/designsystem/ClockDial.kt",
        )

        // Dial geometry tokens (chrome-only ticket must not resize the face).
        assertTrue(source.contains("private val ClockDialSize = 256.dp"))
        assertTrue(source.contains("private val TimeDisplayNumberWidth = 96.dp"))
        assertTrue(source.contains("private val PeriodToggleWidth = 52.dp"))

        // Accessibility / TalkBack strings used by hour/minute pickers.
        assertTrue(source.contains("\"选择小时，24 小时制\""))
        assertTrue(source.contains("\"选择分钟\""))
        assertTrue(source.contains("\"选择日期，"))

        // Domain DST gap message — validation unchanged.
        assertTrue(source.contains("该时刻因夏令时切换不存在，请选择其他时刻"))
        assertTrue(source.contains("RecordTimeDecision.RejectedGap"))
        assertTrue(source.contains("RecordTime.merge("))
    }

    @Test
    fun `nursing confirm chips and fields use Lezi wrappers not bare Material`() {
        val source = read(
            "designsystem/src/main/kotlin/com/lezi/babylog/designsystem/NursingConfirmFields.kt",
        )

        assertTrue(source.contains("LeziFilterChip("))
        assertTrue(source.contains("LeziTextField("))

        assertFalse(
            "nursing fields must not import bare FilterChip",
            source.contains("import androidx.compose.material3.FilterChip"),
        )
        assertFalse(
            "nursing fields must not call bare FilterChip(",
            bareCall("FilterChip").containsMatchIn(source),
        )
        assertFalse(
            "nursing fields must not import bare OutlinedTextField",
            source.contains("import androidx.compose.material3.OutlinedTextField"),
        )
        assertFalse(
            "nursing fields must not call bare OutlinedTextField(",
            bareCall("OutlinedTextField").containsMatchIn(source),
        )
    }

    @Test
    fun `nursing confirm keeps digit filter maxDigits and shared surface entry`() {
        val fields = read(
            "designsystem/src/main/kotlin/com/lezi/babylog/designsystem/NursingConfirmFields.kt",
        )
        assertTrue(fields.contains("fun LeziNursingConfirmFields("))
        assertTrue(fields.contains("it.filter(Char::isDigit).take(maxDigits)"))
        assertTrue(fields.contains("maxDigits = 4"))
        assertTrue(fields.contains("maxDigits = 3"))
        assertTrue(fields.contains("NURSING_ORDER_CHOICES"))
        assertTrue(fields.contains("NursingConfirmField.Duration"))
        assertTrue(fields.contains("NursingConfirmField.Order"))
        assertTrue(fields.contains("NursingConfirmField.Amount"))
        assertTrue(fields.contains("KeyboardType.Number"))
    }

    private fun bareCall(name: String): Regex =
        Regex("""(?<![A-Za-z.])$name\(""")

    private fun read(relativePath: String): String =
        repositoryRoot().resolve(relativePath).readText()

    private fun repositoryRoot(): File = generateSequence(
        seed = File(requireNotNull(System.getProperty("user.dir"))),
        nextFunction = { it.parentFile },
    ).first { candidate -> candidate.resolve("settings.gradle.kts").isFile }
}
