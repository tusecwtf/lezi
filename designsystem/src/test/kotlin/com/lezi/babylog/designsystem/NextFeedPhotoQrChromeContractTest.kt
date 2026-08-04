package com.lezi.babylog.designsystem

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket 04 (ui-drawing-polish): remaining designsystem chrome — next-feed plan
 * prompts, photo preview dismiss, and member-login QR confirm — must route through
 * Lezi wrappers so production paths have no user-visible bare Material.
 *
 * Public seams observed without private helpers:
 * - [LeziNextFeedPlanFlow] dialog actions (schedule / skip / edit / ack / reconcile)
 * - [LeziPhotoPreviewDialog] dismiss action + light-photo readability chrome
 * - [MemberLoginQrConfirmSurface] device-name field + confirm / manual-join / dismiss
 *
 * Product behavior (grants, plan events, photo decode targets) stays fixed.
 */
class NextFeedPhotoQrChromeContractTest {
    @Test
    fun `next-feed plan dialog actions use Lezi wrappers not bare Material`() {
        val source = read(
            "designsystem/src/main/kotlin/com/lezi/babylog/designsystem/NextFeedPlanFlow.kt",
        )

        assertTrue(source.contains("LeziTextButton("))
        assertTrue(source.contains("LeziAlertDialog("))
        assertTrue(source.contains("LeziClockDialDialog("))

        // Affirmative + busy plan actions keep primary tone; cancel/skip stay neutral.
        assertTrue(source.contains("LeziTextButtonTone.Primary"))
        assertTrue(source.contains("\"正在核对…\""))
        assertTrue(
            "reconciling busy must keep Primary tone like scheduling busy",
            source.contains("label = \"正在核对…\"") &&
                source.substringAfter("label = \"正在核对…\"").contains("LeziTextButtonTone.Primary"),
        )

        assertFalse(
            "next-feed flow must not import bare Material TextButton",
            source.contains("import androidx.compose.material3.TextButton"),
        )
        assertFalse(
            "next-feed flow must not call bare TextButton(",
            bareCall("TextButton").containsMatchIn(source),
        )
        assertFalse(
            "next-feed flow must not call bare AlertDialog(",
            bareCall("AlertDialog").containsMatchIn(source),
        )

        // Secondaries stack in dismiss slot (not a crowded Row of dual Touch actions).
        assertTrue(source.contains("Column(horizontalAlignment = Alignment.End)"))
        assertFalse(
            "secondary next-feed actions must not pack into a single-row dismiss slot",
            Regex("""dismissButton\s*=\s*\{\s*Row\s*\{""").containsMatchIn(source),
        )

        // Phase labels and plan event dispatches — product behavior unchanged.
        assertTrue(source.contains("\"安排下次喂养？\""))
        assertTrue(source.contains("\"确认安排\""))
        assertTrue(source.contains("\"不安排\""))
        assertTrue(source.contains("\"调整时间\""))
        assertTrue(source.contains("\"已安排下次喂养\""))
        assertTrue(source.contains("\"重新核对\""))
        assertTrue(source.contains("NextFeedPlanEvent.Schedule("))
        assertTrue(source.contains("NextFeedPlanEvent.Skip"))
        assertTrue(source.contains("NextFeedPlanEvent.EditTime"))
        assertTrue(source.contains("NextFeedPlanEvent.AcknowledgeScheduled"))
        assertTrue(source.contains("NextFeedPlanEvent.Reconcile"))
        assertTrue(source.contains("reduceNextFeedPlan("))
    }

    @Test
    fun `photo preview dismiss uses Lezi chrome and stays readable on light photos`() {
        val source = read(
            "designsystem/src/main/kotlin/com/lezi/babylog/designsystem/PhotoPreviewDialog.kt",
        )

        assertTrue(source.contains("LeziTextButton("))
        assertTrue(source.contains("\"关闭\""))
        // Strict OnMedia on dismiss — unrelated Color.White body copy must not satisfy this.
        assertTrue(source.contains("LeziTextButtonTone.OnMedia"))
        assertTrue(
            "dismiss label must use OnMedia tone",
            source.contains("label = \"关闭\"") &&
                source.substringAfter("label = \"关闭\"").contains("LeziTextButtonTone.OnMedia"),
        )
        // Named scrim + local chip under freeform dismiss (not fade-only white text).
        assertTrue(source.contains("PhotoChromeScrimAlpha"))
        assertTrue(source.contains("PhotoChromeChipAlpha"))
        assertTrue(source.contains("PhotoChromeTopScrimHeight"))
        assertTrue(source.contains("PhotoChromeBottomScrimHeight"))
        assertTrue(source.contains("Brush.verticalGradient("))
        assertTrue(
            "close control must sit on a local dark chip, not only the band scrim",
            source.contains("background(Color.Black.copy(alpha = PhotoChromeChipAlpha)"),
        )

        assertFalse(
            "photo preview must not import bare Material TextButton",
            source.contains("import androidx.compose.material3.TextButton"),
        )
        assertFalse(
            "photo preview must not call bare TextButton(",
            bareCall("TextButton").containsMatchIn(source),
        )

        // Decode contract unchanged: fullscreen target, pager, shared dismiss.
        assertTrue(source.contains("LocalPhotoTarget.FULLSCREEN"))
        assertTrue(source.contains("rememberLocalPhoto("))
        assertTrue(source.contains("HorizontalPager("))
        assertTrue(source.contains("onDismissRequest = onDismiss"))
        assertTrue(source.contains("onClick = onDismiss"))
        assertTrue(source.contains("\"无法预览图片\""))
    }

    @Test
    fun `member-login QR confirm fields and actions use Lezi wrappers not bare Material`() {
        val source = read(
            "designsystem/src/main/kotlin/com/lezi/babylog/designsystem/MemberLoginQrConfirmSurface.kt",
        )

        assertTrue(source.contains("LeziTextField("))
        assertTrue(source.contains("LeziTextButton("))
        assertTrue(source.contains("LeziAlertDialog("))

        assertFalse(
            "QR confirm must not import bare Material TextButton",
            source.contains("import androidx.compose.material3.TextButton"),
        )
        assertFalse(
            "QR confirm must not call bare TextButton(",
            bareCall("TextButton").containsMatchIn(source),
        )
        assertFalse(
            "QR confirm must not import bare OutlinedTextField",
            source.contains("import androidx.compose.material3.OutlinedTextField"),
        )
        assertFalse(
            "QR confirm must not call bare OutlinedTextField(",
            bareCall("OutlinedTextField").containsMatchIn(source),
        )

        // Grant / login labels and enablement seams stay fixed.
        assertTrue(source.contains("\"这台设备的名称 *\""))
        assertTrue(source.contains("\"改用加入家庭\""))
        assertTrue(source.contains("\"在这台设备登录\""))
        assertTrue(source.contains("\"取消\""))
        assertTrue(source.contains("\"重新确认\""))
        assertTrue(source.contains("\"重试首次同步\""))
        assertTrue(source.contains("verificationRetryRequired"))
        assertTrue(source.contains("recoveryRetryRequired"))
        assertTrue(source.contains("onConfirm"))
        assertTrue(source.contains("onManualJoin"))
        assertTrue(source.contains("onDismiss"))
    }

    @Test
    fun `page chrome icon actions use LeziIconButton with themed content color`() {
        // Same-pass residual: detail/brand top bars still had bare IconButton.
        val page = read(
            "designsystem/src/main/kotlin/com/lezi/babylog/designsystem/PageComponents.kt",
        )
        assertTrue(page.contains("LeziIconButton("))
        assertFalse(
            page.contains("import androidx.compose.material3.IconButton"),
        )
        assertFalse(bareCall("IconButton").containsMatchIn(page))
        assertTrue(page.contains("contentDescription = \"返回\""))
        assertTrue(page.contains("contentDescription = \"搜索\""))

        // Wrapper contract: themed LocalContentColor so dark-mode back chrome cannot vanish.
        val iconButton = read(
            "designsystem/src/main/kotlin/com/lezi/babylog/designsystem/ActionStateComponents.kt",
        )
        val body = iconButton.substringAfter("fun LeziIconButton(")
            .substringBefore("fun LeziDestructiveButton(")
        assertTrue(body.contains("LocalContentColor"))
        assertTrue(body.contains("CompositionLocalProvider"))
        assertTrue(body.contains("MaterialTheme.colorScheme.onSurface"))
        assertTrue(body.contains("LeziAlphas.Disabled"))

        // Freeform text actions (photo close) meet dual-axis Touch floor.
        val textButton = iconButton.substringAfter("fun LeziTextButton(")
            .substringBefore("fun LeziFilterChip(")
        assertTrue(textButton.contains("minWidth = LeziSpacing.Touch"))
        assertTrue(textButton.contains("minHeight = LeziSpacing.Touch"))
    }

    private fun bareCall(name: String): Regex =
        DesignsystemSourceFixtures.bareMaterialCall(name)

    private fun read(relativePath: String): String =
        DesignsystemSourceFixtures.read(relativePath)
}
