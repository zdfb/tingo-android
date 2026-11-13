package io.github.tancheng33.tingo

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tancheng33.tingo.settings.AppLanguage
import io.github.tancheng33.tingo.ui.settings.SettingsScreen
import io.github.tancheng33.tingo.ui.theme.TingoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsScreenTest {
    @Test
    fun updateEntryOpensReleases() {
        var opened = false
        compose.setContent {
            TingoTheme {
                SettingsScreen(
                    language = AppLanguage.ZH, engineVersion = "test", dataRoot = "/data/tingo",
                    onLanguageChange = {}, onSaveMinutesLanguage = {}, onOpenReleases = { opened = true },
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.settings_check_updates)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(true, opened) }
    }
    @Test
    fun automaticProcessingDefaultsCanBeChanged() {
        var transcribe: Boolean? = null
        compose.setContent {
            TingoTheme {
                SettingsScreen(
                    language = AppLanguage.ZH, engineVersion = "test", dataRoot = "/data/tingo",
                    onLanguageChange = {}, onSaveMinutesLanguage = {},
                    onSaveAutoTranscribe = { transcribe = it },
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.auto_transcribe)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(false, transcribe) }
    }

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun settingsHasAccessibleTitleAndKeepsLanguageAndOnboardingActions() {
        var selectedLanguage: AppLanguage? = null
        var replayCount = 0

        compose.setContent {
            TingoTheme {
                SettingsScreen(
                    language = AppLanguage.ZH,
                    engineVersion = "test",
                    dataRoot = "/data/tingo",
                    onLanguageChange = { selectedLanguage = it },
                    onReplayOnboarding = { replayCount++ },
                    onSaveMinutesLanguage = {},
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.nav_settings))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
            .assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.settings_language)).assertIsDisplayed()
        compose.onAllNodesWithText(context.getString(R.string.settings_language_en))[0].performClick()
        compose.onNodeWithText(context.getString(R.string.settings_replay_onboarding)).performClick()
        compose.runOnIdle {
            assertEquals(AppLanguage.EN, selectedLanguage)
            assertEquals(1, replayCount)
        }
    }
}
