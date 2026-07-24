package com.lezi.babylog.designsystem

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * Tap blank areas to clear focus and hide the soft keyboard without
 * dismissing the surrounding sheet/dialog.
 *
 * Safe to stack with [androidx.compose.foundation.verticalScroll]: drags still
 * scroll; pure taps on non-interactive chrome clear the IME.
 */
fun Modifier.dismissKeyboardOnTap(): Modifier = composed {
    val dismiss = rememberDismissKeyboard()
    pointerInput(Unit) {
        detectTapGestures(onTap = { dismiss() })
    }
}

/** Imperative hide used by Done actions, confirm buttons, etc. */
@Composable
fun rememberDismissKeyboard(): () -> Unit {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    return remember(focusManager, keyboardController) {
        {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        }
    }
}
