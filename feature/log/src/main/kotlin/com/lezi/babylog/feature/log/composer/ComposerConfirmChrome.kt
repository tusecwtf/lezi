package com.lezi.babylog.feature.log.composer
import com.lezi.babylog.designsystem.LeziConfirmAppearance
import com.lezi.babylog.designsystem.LeziConfirmChromeEvent
import com.lezi.babylog.designsystem.LeziConfirmChromeState
import com.lezi.babylog.designsystem.leziConfirmAppearance
import com.lezi.babylog.designsystem.reduceLeziConfirmChrome
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.timeline.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.layout.*
import com.lezi.babylog.feature.log.photo.*

/**
 * First invalid control to highlight / focus when the user requests an explanation.
 * Order of validation in [QuickRecordDraft.validationResult] must stay aligned with these.
 */
internal enum class ComposerInvalidField {
    StartTime,
    EndTime,
    Note,
    NursingDuration,
    NursingOrder,
    NursingAmount,
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

internal typealias ComposerConfirmAppearance = LeziConfirmAppearance
internal typealias ComposerConfirmChromeState =
    LeziConfirmChromeState<ComposerValidationResult>

internal val ComposerConfirmChromeState.reasonMessage: String?
    get() = shownReason?.message?.takeIf { reasonVisible }

internal val ComposerConfirmChromeState.focusField: ComposerInvalidField?
    get() = shownReason?.field?.takeIf { reasonVisible }

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
): ComposerConfirmAppearance = leziConfirmAppearance(busy, canConfirm)

internal fun reduceConfirmChrome(
    state: ComposerConfirmChromeState,
    event: ComposerConfirmChromeEvent,
): ComposerConfirmChromeState = reduceLeziConfirmChrome(
    state = state,
    event = when (event) {
        ComposerConfirmChromeEvent.DraftEdited -> LeziConfirmChromeEvent.DraftEdited
        ComposerConfirmChromeEvent.Dismissed -> LeziConfirmChromeEvent.Dismissed
        is ComposerConfirmChromeEvent.BusyChanged ->
            LeziConfirmChromeEvent.BusyChanged(event.busy)
        is ComposerConfirmChromeEvent.GreyConfirmTapped ->
            LeziConfirmChromeEvent.GreyConfirmTapped(event.validation)
    },
)
