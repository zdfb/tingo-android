package io.github.tancheng33.tingo.ui.design

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import io.github.tancheng33.tingo.ui.record.RecordScreen
import io.github.tancheng33.tingo.ui.record.RecordUiState
import io.github.tancheng33.tingo.ui.record.RecordViewModel
import io.github.tancheng33.tingo.ui.record.HomeStatus
import io.github.tancheng33.tingo.ui.record.HomeStatusText
import io.github.tancheng33.tingo.ui.theme.TingoTheme

/** Debug-only rendering of the production recording workspace. */
class RecordingWorkspacePreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TingoTheme {
                RecordScreen(
                    state = if (intent.getBooleanExtra("idle", false)) {
                        RecordUiState.Idle
                    } else {
                        RecordUiState.Recording(125_000, 0.45f)
                    },
                    onStart = {}, onPause = {}, onResume = {}, onStop = {}, onDiscard = {}, onOpenMeeting = {},
                    liveEntries = listOf(
                        RecordViewModel.LiveEntry(1, "我们先确认本周的安排，然后把需要跟进的问题列出来。", 1),
                        RecordViewModel.LiveEntry(2, "好，我会在会后整理结果并同步给大家。", 2),
                    ),
                    statusRows = listOf(
                        HomeStatus(HomeStatusText.ModelsReady),
                        HomeStatus(HomeStatusText.LlmMissing, io.github.tancheng33.tingo.ui.record.StatusLevel.Warn),
                        HomeStatus(HomeStatusText.Version("0.5.4")),
                    ),
                    modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
                )
            }
        }
    }
}
