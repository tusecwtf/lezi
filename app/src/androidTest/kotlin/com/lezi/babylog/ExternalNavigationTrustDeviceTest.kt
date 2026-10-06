package com.lezi.babylog

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.designsystem.LeziTheme
import com.lezi.babylog.feature.settings.calendar.CarePlanReminderReceiver
import com.lezi.babylog.feature.widget.WidgetComposerContract
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExternalNavigationTrustDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun forgedWidgetIntentCannotAuthorizeComposerBeforeConfirmation() {
        val spoof = Intent(
            WidgetComposerContract.ACTION_OPEN_RECORD_COMPOSER,
            Uri.parse("lezi://composer/new?babyId=42&type=formula"),
        )
            .putExtra(WidgetComposerContract.EXTRA_BABY_ID, 42L)
            .putExtra(WidgetComposerContract.EXTRA_RECORD_TYPE, "formula")
        val request = parseUntrustedExternalNavigation(spoof)
        val authorized = mutableStateOf<AuthorizedExternalNavigation?>(null)

        compose.setContent {
            LeziTheme {
                UntrustedExternalNavigationConfirmationDialog(
                    request = requireNotNull(request),
                    onDismiss = {},
                    onConfirm = {
                        authorized.value = authorizeExternalNavigation(
                            request = request,
                            userConfirmed = true,
                        )
                    },
                )
            }
        }

        compose.onNodeWithTag(UNTRUSTED_NAVIGATION_CONFIRM_TAG).assertIsDisplayed()
        compose.runOnIdle { assertThat(authorized.value).isNull() }
    }

    @Test
    fun forgedBrowsableFulfillIsDeniedWhenUserCancels() {
        val spoof = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("lezi://care-plan/attacker-selected-plan"),
        ).addCategory(Intent.CATEGORY_BROWSABLE)
        val request = parseUntrustedExternalNavigation(spoof)
        val authorized = mutableStateOf<AuthorizedExternalNavigation?>(null)
        val dismissed = mutableStateOf(false)

        compose.setContent {
            LeziTheme {
                UntrustedExternalNavigationConfirmationDialog(
                    request = requireNotNull(request),
                    onDismiss = { dismissed.value = true },
                    onConfirm = {
                        authorized.value = authorizeExternalNavigation(
                            request = request,
                            userConfirmed = true,
                        )
                    },
                )
            }
        }

        compose.onNodeWithTag(UNTRUSTED_NAVIGATION_CANCEL_TAG).performClick()
        compose.runOnIdle {
            assertThat(dismissed.value).isTrue()
            assertThat(authorized.value).isNull()
        }
    }

    @Test
    fun extrasOnlyFulfillSpoofIsRejectedBeforeUi() {
        val spoof = Intent(Intent.ACTION_MAIN)
            .putExtra(CarePlanReminderReceiver.EXTRA_FULFILL_PLAN_ID, 73L)
            .putExtra(CarePlanReminderReceiver.EXTRA_FULFILL_PLAN_UUID, "plan-uuid")

        assertThat(parseUntrustedExternalNavigation(spoof)).isNull()
    }
}
