package com.lezi.babylog.feature.family.baby

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import com.lezi.babylog.core.model.Baby
import com.lezi.babylog.core.ui.CameraCaptureLauncher
import com.lezi.babylog.designsystem.LeziTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class BabyProfileValidationDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun createPreservesRejectedTextAndOnlyClearsTheEditedFieldsError() {
        var saved: Pair<String, Int?>? = null
        compose.setContent {
            LeziTheme {
                BabyCreateDialog(
                    busy = false,
                    onDismiss = {},
                    onCreate = { nickname, _, _, grams, _, _, done ->
                        saved = nickname to grams
                        done(null)
                    },
                    cameraCaptureLauncherFactory = { _, _ -> NoCamera },
                )
            }
        }

        exerciseInvalidFields("添加")
        compose.runOnIdle { assertNull(saved) }
        field(WeightLabel).performTextReplacement(" 3.20 ")
        field(WeightLabel).assert(NoFieldError)
        compose.onNodeWithText("添加").performClick()
        compose.runOnIdle { assertEquals("乐乐" to 3_200, saved) }
    }

    @Test
    fun editUsesTheSameParserAndFieldErrorIsolation() {
        var saved: Pair<String, Int?>? = null
        compose.setContent {
            LeziTheme {
                BabyEditDialog(
                    baby = BabyFixture,
                    canEditProfile = true,
                    canEditAvatar = false,
                    isCurrent = true,
                    canMoveEarlier = false,
                    canMoveLater = false,
                    onDismiss = {},
                    onSaveProfile = { nickname, _, _, grams, _, _, done ->
                        saved = nickname to grams
                        done(null)
                    },
                    onLocalTheme = { _, done -> done(null) },
                    onMoveLocal = { _, done -> done(null) },
                    onSetCurrent = {},
                    cameraCaptureLauncherFactory = { _, _ -> NoCamera },
                )
            }
        }

        exerciseInvalidFields("保存档案")
        compose.runOnIdle { assertNull(saved) }
        field(WeightLabel).performTextReplacement(" 3.20 ")
        field(WeightLabel).assert(NoFieldError)
        compose.onNodeWithText("保存档案").performClick()
        compose.runOnIdle { assertEquals("乐乐" to 3_200, saved) }
    }

    @Test
    fun createSaveFailureIsFormFeedbackAndKeepsTheDraftForRetry() {
        var attempts = 0
        compose.setContent {
            LeziTheme {
                BabyCreateDialog(
                    busy = false,
                    onDismiss = {},
                    onCreate = { _, _, _, _, _, _, done ->
                        attempts += 1
                        done("保存失败，请重试")
                    },
                    cameraCaptureLauncherFactory = { _, _ -> NoCamera },
                )
            }
        }
        field(NicknameLabel).performTextReplacement("乐乐")
        field(WeightLabel).performTextReplacement("3.20")
        compose.onNodeWithText("添加").performClick()
        field(NicknameLabel).assert(NoFieldError)
        field(WeightLabel).assert(NoFieldError).assertEditableText("3.20")
        compose.onNodeWithText("保存失败，请重试").assertExists()
        compose.onNodeWithText("添加").performClick()
        compose.runOnIdle { assertEquals(2, attempts) }
    }

    private fun exerciseInvalidFields(saveLabel: String) {
        field(NicknameLabel).performTextReplacement("")
        field(WeightLabel).performTextReplacement("-3.20")
        compose.onNodeWithText(saveLabel).performClick()
        field(NicknameLabel).assert(FieldError)
        field(WeightLabel).assert(FieldError).assertEditableText("-3.20")
        compose.onNodeWithText("请填写昵称").assertExists()
        compose.onNodeWithText("出生体重格式不正确，请填写数字（如 3.20）").assertExists()

        field(NicknameLabel).performTextReplacement(" 乐乐 ")
        field(NicknameLabel).assert(NoFieldError)
        field(WeightLabel).assert(FieldError)
        compose.onNodeWithText("请填写昵称").assertDoesNotExist()

        // Reintroduce a nickname error, then fix weight: the nickname error stays.
        field(NicknameLabel).performTextReplacement("")
        compose.onNodeWithText(saveLabel).performClick()
        field(WeightLabel).performTextReplacement("3.20")
        field(WeightLabel).assert(NoFieldError)
        field(NicknameLabel).assert(FieldError)
        field(NicknameLabel).performTextReplacement(" 乐乐 ")
    }

    private fun field(label: String): SemanticsNodeInteraction =
        compose.onNode(hasText(label) and hasSetTextAction()).performScrollTo()

    private fun SemanticsNodeInteraction.assertEditableText(text: String): SemanticsNodeInteraction =
        assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(text)))

    private companion object {
        const val NicknameLabel = "昵称（不可重复）"
        const val WeightLabel = "出生体重（kg，可选）"
        val FieldError = SemanticsMatcher.keyIsDefined(SemanticsProperties.Error)
        val NoFieldError = SemanticsMatcher.keyNotDefined(SemanticsProperties.Error)
        val NoCamera = object : CameraCaptureLauncher {
            override fun launch() = Unit
            override fun dispose() = Unit
        }
        val BabyFixture = Baby(
            id = 9,
            familyId = 1,
            nickname = "乐乐",
            birthdayEpochDay = 20_000,
            themeColorArgb = 0xff6688aa.toInt(),
            clientUuid = "baby-validation-9",
            updatedAt = 1,
            birthWeightGrams = 3_200,
        )
    }
}
