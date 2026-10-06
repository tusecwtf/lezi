package com.lezi.babylog.designsystem

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView

/**
 * Outcome tone a haptic communicates (0.5.4 ticket 15, spec §M3):
 * - [Confirm] — light acknowledgment that a discrete action committed
 *   (record saved, delete executed, timer started/paused/completed, baby cycled).
 * - [Reject] — the requested action was refused.
 *
 * Both resolve to the platform outcome vocabulary ([HapticFeedbackConstants.CONFIRM] /
 * [HapticFeedbackConstants.REJECT], API 30+) because the Compose BOM only exposes
 * LongPress / TextHandleMove; below API 30 the Compose LongPress tier is the closest
 * settings-respecting fallback.
 */
enum class LeziHapticTone { Confirm, Reject }

/** How a tone is physically realized. Internal: call sites only name the tone. */
internal sealed interface LeziHapticChannel {
    /** Platform view constant (CONFIRM/REJECT) — the outcome vocabulary on API 30+. */
    data class Platform(val constant: Int) : LeziHapticChannel

    /** Compose [LocalHapticFeedback] fallback below API 30 (LongPress / TextHandleMove only). */
    data class ComposeFallback(val type: HapticFeedbackType) : LeziHapticChannel
}

/**
 * Pure tone→channel resolution (JVM-tested seam). Never returns an
 * ignore-settings flag path: both channels honor the system haptic setting.
 */
internal fun leziHapticChannel(
    tone: LeziHapticTone,
    sdkInt: Int,
): LeziHapticChannel =
    if (sdkInt >= Build.VERSION_CODES.R) {
        LeziHapticChannel.Platform(
            when (tone) {
                LeziHapticTone.Confirm -> HapticFeedbackConstants.CONFIRM
                LeziHapticTone.Reject -> HapticFeedbackConstants.REJECT
            },
        )
    } else {
        // Below R the platform has no outcome constants; Compose LongPress is the
        // light-confirmation tier both tones degrade to (settings-respecting).
        LeziHapticChannel.ComposeFallback(HapticFeedbackType.LongPress)
    }

/** Outcome haptics for discrete, committed transitions — never per-frame or per-tick. */
interface LeziHaptics {
    /** Light confirmation after a committed action (save/delete done, timer toggle, baby cycle). */
    fun confirm()

    /** Refusal feedback when a requested action is rejected. */
    fun reject()
}

private class ViewLeziHaptics(
    private val compose: HapticFeedback,
    private val view: View,
) : LeziHaptics {
    override fun confirm() = perform(LeziHapticTone.Confirm)

    override fun reject() = perform(LeziHapticTone.Reject)

    private fun perform(tone: LeziHapticTone) {
        when (val channel = leziHapticChannel(tone, Build.VERSION.SDK_INT)) {
            is LeziHapticChannel.Platform ->
                // No flags: View.performHapticFeedback honors the system haptic setting.
                view.performHapticFeedback(channel.constant)
            is LeziHapticChannel.ComposeFallback ->
                compose.performHapticFeedback(channel.type)
        }
    }
}

/**
 * Device-test seam (LayoutMotionHapticsDeviceTest pattern): tests provide a recording
 * [LeziHaptics] here; production leaves it unset and gets the real view-backed instance.
 */
val LocalLeziHaptics = compositionLocalOf<LeziHaptics?> { null }

/**
 * Outcome haptics for the composition. Existing swipe/layout-drag haptic points keep
 * their direct [LocalHapticFeedback] usage; this seam is for outcome confirmations.
 */
@Composable
fun rememberLeziHaptics(): LeziHaptics {
    val override = LocalLeziHaptics.current
    if (override != null) return override
    val compose = LocalHapticFeedback.current
    val view = LocalView.current
    return remember(compose, view) { ViewLeziHaptics(compose, view) }
}
