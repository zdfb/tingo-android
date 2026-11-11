package io.github.tancheng33.tingo

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tancheng33.tingo.ui.onboard.OnboardingScreen
import io.github.tancheng33.tingo.ui.onboard.OnboardingViewModel
import io.github.tancheng33.tingo.ui.theme.TingoTheme
import org.junit.Rule
import org.junit.Test

class OnboardingScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun welcomeTitleIsExposedAsAHeadingAndKeepsTheFiveStepActions() {
        compose.setContent {
            TingoTheme {
                OnboardingScreen(
                    state = OnboardingViewModel.UiState(step = OnboardingViewModel.Step.HELLO),
                    steps = OnboardingViewModel.Step.entries,
                    llmConfigured = false,
                    micGranted = false,
                    systemAudioSupported = false,
                    testing = false,
                    probe = null,
                    onTest = {},
                    onRequestPermission = {},
                    onNext = {},
                    onBack = {},
                    onSkip = {},
                    onInstallDemo = {},
                    onOpenSettings = {},
                )
            }
        }

        val title = context.getString(R.string.onboard_hello_title)
        compose.onNodeWithText(title)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
        compose.onNodeWithText(context.getString(R.string.onboard_next)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.onboard_skip)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.onboard_step_model)).assertIsDisplayed()
    }

    @Test
    fun eachOnboardingStepExposesItsTitleAsAHeading() {
        val steps = OnboardingViewModel.Step.entries
        val titleResources = listOf(
            R.string.onboard_hello_title,
            R.string.onboard_step_model,
            R.string.onboard_step_permission,
            R.string.onboard_step_llm,
            R.string.onboard_step_demo,
        )

        compose.setContent {
            var step by remember { mutableStateOf(OnboardingViewModel.Step.HELLO) }
            TingoTheme {
                OnboardingScreen(
                    state = OnboardingViewModel.UiState(step = step, modelReady = true),
                    steps = steps,
                    llmConfigured = false,
                    micGranted = false,
                    systemAudioSupported = false,
                    testing = false,
                    probe = null,
                    onTest = {},
                    onRequestPermission = {},
                    onNext = {
                        val index = steps.indexOf(step)
                        if (index < steps.lastIndex) step = steps[index + 1]
                    },
                    onBack = {
                        val index = steps.indexOf(step)
                        if (index > 0) step = steps[index - 1]
                    },
                    onSkip = {},
                    onInstallDemo = {},
                    onOpenSettings = {},
                )
            }
        }

        titleResources.forEachIndexed { index, resource ->
            val title = context.getString(resource)
            compose.onNode(
                SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit)
                    .and(hasText(title)),
            ).assertIsDisplayed()
            if (index < steps.lastIndex) {
                compose.onNodeWithText(context.getString(R.string.onboard_next)).performClick()
            }
        }
    }

    @Test
    fun welcomeShowsTheTingoBrandMark() {
        compose.setContent {
            TingoTheme {
                OnboardingScreen(
                    state = OnboardingViewModel.UiState(step = OnboardingViewModel.Step.HELLO),
                    steps = OnboardingViewModel.Step.entries,
                    llmConfigured = false,
                    micGranted = false,
                    systemAudioSupported = false,
                    testing = false,
                    probe = null,
                    onTest = {},
                    onRequestPermission = {},
                    onNext = {},
                    onBack = {},
                    onSkip = {},
                    onInstallDemo = {},
                    onOpenSettings = {},
                )
            }
        }

        compose.onNodeWithContentDescription(context.getString(R.string.app_name)).assertIsDisplayed()
    }
}
