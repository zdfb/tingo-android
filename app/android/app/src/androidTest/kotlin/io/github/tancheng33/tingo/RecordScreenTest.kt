package io.github.tancheng33.tingo

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tancheng33.tingo.ui.record.RecordScreen
import io.github.tancheng33.tingo.ui.record.RecordUiState
import io.github.tancheng33.tingo.ui.theme.TingoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RecordScreenTest {
    @Test
    fun recordingWorkspaceShowsTranscriptAreaBeforeFirstSentence() {
        compose.setContent {
            TingoTheme {
                RecordScreen(
                    state = RecordUiState.Recording(elapsedMs = 1_000, level = 0f),
                    onStart = {}, onPause = {}, onResume = {}, onStop = {}, onDiscard = {}, onOpenMeeting = {},
                )
            }
        }
        compose.onNodeWithTag("live-transcript").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.record_stop)).assertIsDisplayed()
    }
    @Test
    fun finishedMeetingOffersNewMeetingSetup() {
        var selected = false
        compose.setContent {
            TingoTheme {
                RecordScreen(
                    state = RecordUiState.Finished("meeting", 1_000),
                    onStart = {}, onPause = {}, onResume = {}, onStop = {}, onDiscard = {}, onOpenMeeting = {},
                    onNewMeeting = { selected = true },
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.record_new_meeting)).performClick()
        compose.runOnIdle { assertEquals(true, selected) }
    }
    @Test
    fun incompleteLiveTranscriptWarningKeepsRecordingControlsVisible() {
        compose.setContent {
            TingoTheme {
                RecordScreen(
                    state = RecordUiState.Recording(elapsedMs = 1000, level = 0f),
                    onStart = {}, onPause = {}, onResume = {}, onStop = {}, onDiscard = {}, onOpenMeeting = {},
                    liveTranscriptIncomplete = true,
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.record_live_incomplete)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.record_stop)).assertIsDisplayed()
    }
    @Test
    fun liveTranscriptShowsProvisionalSpeaker() {
        compose.setContent {
            TingoTheme {
                RecordScreen(
                    state = RecordUiState.Recording(elapsedMs = 1000, level = 0f),
                    onStart = {}, onPause = {}, onResume = {}, onStop = {}, onDiscard = {}, onOpenMeeting = {},
                    liveEntries = listOf(io.github.tancheng33.tingo.ui.record.RecordViewModel.LiveEntry(1, "讨论方案", 2)),
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.record_provisional_speaker, 2)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.record_speaker_provisional_hint)).assertIsDisplayed()
        compose.onNodeWithText("讨论方案").assertIsDisplayed()
    }
    @Test
    fun automaticProcessingCanBeChangedForTheMeeting() {
        var selected: Boolean? = null
        compose.setContent {
            TingoTheme {
                RecordScreen(
                    state = RecordUiState.Idle, onStart = {}, onPause = {}, onResume = {},
                    onStop = {}, onDiscard = {}, onOpenMeeting = {},
                    autoTranscribe = true, onAutoTranscribeChange = { selected = it },
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.auto_transcribe)).performClick()
        compose.runOnIdle { assertEquals(false, selected) }
    }

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun recordingScreenCanMarkImportantMoments() {
        var marked = 0
        compose.setContent {
            TingoTheme {
                RecordScreen(
                    state = RecordUiState.Recording(elapsedMs = 1_000, level = 0f),
                    onStart = {}, onPause = {}, onResume = {}, onStop = {},
                    onDiscard = {}, onOpenMeeting = {}, onMarkHighlight = { marked++ },
                    highlights = listOf(1_000),
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.record_mark_highlight)).performClick()
        compose.onNodeWithText("00:01").assertExists()
        compose.runOnIdle { assertEquals(1, marked) }
    }

    @Test
    fun longLiveTranscriptCanBeScrolledWhileRecordingControlsRemainVisible() {
        compose.setContent {
            TingoTheme {
                RecordScreen(
                    state = RecordUiState.Recording(elapsedMs = 65_000, level = 0.4f),
                    onStart = {}, onPause = {}, onResume = {}, onStop = {},
                    onDiscard = {}, onOpenMeeting = {},
                    liveLines = (1..100).map { "Sentence $it" },
                )
            }
        }

        compose.onNodeWithTag("live-transcript").performScrollToNode(hasText("Sentence 100"))
        compose.onNodeWithText("Sentence 100").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.record_stop)).assertIsDisplayed()
    }

    @Test
    fun idleScreenHasAccessiblePageTitleAndStartsRecording() {
        var startCount = 0

        compose.setContent {
            TingoTheme {
                RecordScreen(
                    state = RecordUiState.Idle,
                    onStart = { startCount++ },
                    onPause = {},
                    onResume = {},
                    onStop = {},
                    onDiscard = {},
                    onOpenMeeting = {},
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.nav_record))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
            .assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.record_start))
            .assertIsDisplayed()
            .performClick()
        compose.runOnIdle { assertEquals(1, startCount) }
    }

    @Test
    fun recordingStateShowsElapsedTimeAndKeepsPauseAndFinishActions() {
        var pauseCount = 0
        var stopCount = 0

        compose.setContent {
            TingoTheme {
                RecordScreen(
                    state = RecordUiState.Recording(elapsedMs = 65_000, level = 0.4f),
                    onStart = {},
                    onPause = { pauseCount++ },
                    onResume = {},
                    onStop = { stopCount++ },
                    onDiscard = {},
                    onOpenMeeting = {},
                    liveLines = listOf("A sentence from the meeting"),
                )
            }
        }

        compose.onNodeWithText("01:05").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.record_live_hint)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.record_pause)).performClick()
        compose.onNodeWithText(context.getString(R.string.record_stop)).performClick()
        compose.runOnIdle {
            assertEquals(1, pauseCount)
            assertEquals(1, stopCount)
        }
    }
}
