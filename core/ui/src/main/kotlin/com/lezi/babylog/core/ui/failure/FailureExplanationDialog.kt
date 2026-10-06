package com.lezi.babylog.core.ui.failure

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import com.lezi.babylog.core.common.failure.FailureAction
import com.lezi.babylog.core.common.failure.FailureKind
import com.lezi.babylog.core.common.failure.failureDialogContent
import com.lezi.babylog.designsystem.LeziAlertDialog
import com.lezi.babylog.designsystem.LeziTextButton
import com.lezi.babylog.designsystem.LeziTextButtonTone
import com.lezi.babylog.designsystem.LeziTypography

/**
 * Shared 0.4.4 failure confirmation chrome. The surface accepts only a
 * [FailureKind]; title is 「大类：细类标题」 and buttons come from 下一步.
 * Safety-check stuck uses the existing blocking page, not this dialog.
 */
@Composable
fun FailureExplanationDialog(
    kind: FailureKind,
    onAction: (FailureAction) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val content = failureDialogContent(kind) ?: return
    LeziAlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(content.title, style = LeziTypography.TitleSm) },
        text = { Text(content.body, style = LeziTypography.Body) },
        confirmButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.Center,
            ) {
                content.actions.forEachIndexed { index, action ->
                    LeziTextButton(
                        label = action.label,
                        onClick = { onAction(action) },
                        tone = if (index == 0) {
                            LeziTextButtonTone.Primary
                        } else {
                            LeziTextButtonTone.Neutral
                        },
                    )
                }
            }
        },
    )
}
