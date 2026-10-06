package com.lezi.babylog.feature.family.wizard

import androidx.compose.runtime.Composable
import com.lezi.babylog.core.ui.memberloginqr.MemberLoginQrScanCopy
import com.lezi.babylog.core.ui.memberloginqr.MemberLoginQrScanner
import com.lezi.babylog.core.ui.memberloginqr.MemberLoginQrScannerOutcome
import com.lezi.babylog.core.ui.memberloginqr.rememberMemberLoginQrScanAction
import com.lezi.babylog.core.ui.memberloginqr.rememberMemberLoginQrScanner
import com.lezi.babylog.sync.qr.MemberLoginQrPayload

/** Production-used scanner binding; tests may substitute only the hardware Adapter. */
@Composable
internal fun rememberFamilyMemberLoginQrScanAction(
    onReady: (MemberLoginQrPayload) -> Unit,
    onMessage: (String) -> Unit,
    scannerFactory: @Composable (
        (MemberLoginQrScannerOutcome) -> Unit,
    ) -> MemberLoginQrScanner = { onOutcome ->
        rememberMemberLoginQrScanner(onOutcome = onOutcome)
    },
): () -> Unit = rememberMemberLoginQrScanAction(
    onReady = onReady,
    onMessage = onMessage,
    copy = MemberLoginQrScanCopy.Family,
    scannerFactory = scannerFactory,
)

