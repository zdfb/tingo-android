package io.github.tancheng33.tingo.recording

import io.github.tancheng33.tingo.ui.record.RecordUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingNotificationTest {

    @Test
    fun foregroundOnlyWhileRecordingOrPaused() {
        assertTrue(requiresForegroundService(RecordUiState.Recording(elapsedMs = 0, level = 0f)))
        assertTrue(requiresForegroundService(RecordUiState.Paused(elapsedMs = 1_000)))
        assertFalse(requiresForegroundService(RecordUiState.Idle))
        assertFalse(requiresForegroundService(RecordUiState.Finished("m1", 1_000)))
        assertFalse(requiresForegroundService(RecordUiState.Failed(1)))
    }

    @Test
    fun titleSaysWhetherItIsRecording() {
        assertEquals("正在录音 · 00:12", RecordingNotification.title(recording = true, elapsed = "00:12"))
        assertEquals("已暂停 · 00:12", RecordingNotification.title(recording = false, elapsed = "00:12"))
    }
}
