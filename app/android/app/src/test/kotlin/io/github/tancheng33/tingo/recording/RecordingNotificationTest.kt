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
        // 模板由资源提供（中英各一份），这里只验证填充逻辑
        assertEquals("正在录音 · 00:12", RecordingNotification.title("正在录音 · %1\$s", "00:12"))
        assertEquals("Recording · 01:05", RecordingNotification.title("Recording · %1\$s", "01:05"))
    }
}
