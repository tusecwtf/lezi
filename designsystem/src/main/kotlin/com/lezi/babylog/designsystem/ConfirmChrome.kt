package com.lezi.babylog.designsystem

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics

enum class LeziConfirmAppearance {
    Enabled,
    ExplainedDisabled,
    BusyDisabled,
}

data class LeziConfirmChromeState<T>(
    val reasonVisible: Boolean = false,
    val shownReason: T? = null,
)

sealed interface LeziConfirmChromeEvent<out T> {
    data object DraftEdited : LeziConfirmChromeEvent<Nothing>

    data class GreyConfirmTapped<T>(val validation: T?) : LeziConfirmChromeEvent<T>

    data object Dismissed : LeziConfirmChromeEvent<Nothing>

    data class BusyChanged(val busy: Boolean) : LeziConfirmChromeEvent<Nothing>
}

fun leziConfirmAppearance(
    busy: Boolean,
    canConfirm: Boolean,
): LeziConfirmAppearance = when {
    busy -> LeziConfirmAppearance.BusyDisabled
    canConfirm -> LeziConfirmAppearance.Enabled
    else -> LeziConfirmAppearance.ExplainedDisabled
}

fun <T> reduceLeziConfirmChrome(
    state: LeziConfirmChromeState<T>,
    event: LeziConfirmChromeEvent<T>,
): LeziConfirmChromeState<T> = when (event) {
    LeziConfirmChromeEvent.DraftEdited -> LeziConfirmChromeState()
    LeziConfirmChromeEvent.Dismissed -> LeziConfirmChromeState()
    is LeziConfirmChromeEvent.BusyChanged -> if (event.busy) {
        LeziConfirmChromeState()
    } else {
        state
    }
    is LeziConfirmChromeEvent.GreyConfirmTapped -> {
        val validation = event.validation ?: return state
        if (state.reasonVisible) {
            LeziConfirmChromeState()
        } else {
            LeziConfirmChromeState(reasonVisible = true, shownReason = validation)
        }
    }
}

@Composable
fun LeziConfirmReasonCard(reason: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = reason
                liveRegion = LiveRegionMode.Polite
            },
        shape = LeziThemeExt.controlShape,
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.92f),
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Text(
            reason,
            modifier = Modifier.padding(LeziThemeExt.density.cardPad),
            style = LeziTypography.BodyStrong,
        )
    }
}
