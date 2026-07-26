package com.lezi.babylog.feature.log

/**
 * First invalid control to highlight / focus when the user requests an explanation.
 * Order of validation in [QuickRecordDraft.validationResult] must stay aligned with these.
 */
internal enum class ComposerInvalidField {
    StartTime,
    EndTime,
    Note,
    NursingDuration,
    MilkAmount,
    MilkPrepared,
    MilkDuration,
    Temperature,
    Body,
    MedicineName,
    HospitalReason,
    CustomTitle,
    FoodContent,
    VaccineName,
    MeasurementValue,
    PeeAmount,
    StoolAmount,
    StoolConsistency,
    StoolColor,
    Severity,
    Unsupported,
}

/** Concrete validation failure exposed to confirm chrome and TalkBack. */
internal data class ComposerValidationResult(
    val message: String,
    val field: ComposerInvalidField,
)

/** Visual / interactive mode of the composer primary confirm button. */
internal enum class ComposerConfirmAppearance {
    /** Valid draft — normal primary button that saves. */
    Enabled,

    /**
     * Invalid draft — same size as enabled, light-grey fill, grey border, no shadow.
     * Remains clickable only to explain; never persists.
     */
    ExplainedDisabled,

    /** Saving or deleting — truly non-interactive; never opens the reason card. */
    BusyDisabled,
}

/**
 * Lifecycle of the non-modal reason card above the confirm button.
 *
 * Pure state: show on grey-tap, clear on draft edit / re-tap / dismiss, never open while busy.
 */
internal data class ComposerConfirmChromeState(
    val reasonVisible: Boolean = false,
    val shownReason: ComposerValidationResult? = null,
) {
    val reasonMessage: String?
        get() = shownReason?.message?.takeIf { reasonVisible }

    val focusField: ComposerInvalidField?
        get() = shownReason?.field?.takeIf { reasonVisible }
}

internal sealed interface ComposerConfirmChromeEvent {
    /** Any user edit to the draft; re-validation happens outside and clears the card. */
    data object DraftEdited : ComposerConfirmChromeEvent

    /**
     * User tapped the grey (explained-disabled) confirm control.
     * First tap shows the reason; a re-tap while visible clears it.
     */
    data class GreyConfirmTapped(
        val validation: ComposerValidationResult?,
    ) : ComposerConfirmChromeEvent

    /** Composer closed or session replaced. */
    data object Dismissed : ComposerConfirmChromeEvent

    /** Saving/deleting started or finished; busy never opens a reason card. */
    data class BusyChanged(val busy: Boolean) : ComposerConfirmChromeEvent
}

internal fun confirmAppearance(
    busy: Boolean,
    canConfirm: Boolean,
): ComposerConfirmAppearance = when {
    busy -> ComposerConfirmAppearance.BusyDisabled
    canConfirm -> ComposerConfirmAppearance.Enabled
    else -> ComposerConfirmAppearance.ExplainedDisabled
}

internal fun reduceConfirmChrome(
    state: ComposerConfirmChromeState,
    event: ComposerConfirmChromeEvent,
): ComposerConfirmChromeState = when (event) {
    ComposerConfirmChromeEvent.DraftEdited -> ComposerConfirmChromeState()
    ComposerConfirmChromeEvent.Dismissed -> ComposerConfirmChromeState()
    is ComposerConfirmChromeEvent.BusyChanged -> if (event.busy) {
        ComposerConfirmChromeState()
    } else {
        state
    }
    is ComposerConfirmChromeEvent.GreyConfirmTapped -> {
        val validation = event.validation ?: return state
        if (state.reasonVisible) {
            ComposerConfirmChromeState()
        } else {
            ComposerConfirmChromeState(
                reasonVisible = true,
                shownReason = validation,
            )
        }
    }
}
