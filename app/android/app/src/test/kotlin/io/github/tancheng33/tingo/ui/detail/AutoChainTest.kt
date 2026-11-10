package io.github.tancheng33.tingo.ui.detail

import io.github.tancheng33.tingo.R
import io.github.tancheng33.tingo.transcription.Transcript
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「录完 → 转写 → 纪要」的自动链规则。
 *
 * 每条 false 分支都对应一个会被用户看见的坏结果：覆盖旧纪要、没配服务时白等一次失败、
 * 或者手动转写被顺带写出一篇纪要。
 */
class AutoChainTest {

    private fun done() = TranscriptionUiState.Done(Transcript(source = "录音.wav", durationMs = 0, sentences = emptyList()))

    @Test
    fun theHappyPathStartsMinutes() {
        assertTrue(
            AutoChain.shouldAutoStartMinutes(
                armed = true,
                transcription = done(),
                hasMinutes = false,
                minutes = MinutesUiState.Idle,
            ),
        )
    }

    @Test
    fun manualTranscriptionDoesNotWriteMinutes() {
        assertFalse(
            AutoChain.shouldAutoStartMinutes(
                armed = false,
                transcription = done(),
                hasMinutes = false,
                minutes = MinutesUiState.Idle,
            ),
        )
    }

    @Test
    fun existingMinutesAreNeverOverwritten() {
        assertFalse(
            AutoChain.shouldAutoStartMinutes(
                armed = true,
                transcription = done(),
                hasMinutes = true,
                minutes = MinutesUiState.Idle,
            ),
        )
    }

    @Test
    fun transcriptionThatFailedDoesNotStartMinutes() {
        assertFalse(
            AutoChain.shouldAutoStartMinutes(
                armed = true,
                transcription = TranscriptionUiState.Failed(R.string.err_no_recording),
                hasMinutes = false,
                minutes = MinutesUiState.Idle,
            ),
        )
        assertFalse(
            AutoChain.shouldAutoStartMinutes(
                armed = true,
                transcription = TranscriptionUiState.Running(0.5f),
                hasMinutes = false,
                minutes = MinutesUiState.Idle,
            ),
        )
    }

    @Test
    fun anUnconfiguredModelServiceIsNotAnAutoStart() {
        assertFalse(
            AutoChain.shouldAutoStartMinutes(
                armed = true,
                transcription = done(),
                hasMinutes = false,
                minutes = MinutesUiState.NotConfigured,
            ),
        )
    }

    @Test
    fun minutesAlreadyRunningIsLeftAlone() {
        assertFalse(
            AutoChain.shouldAutoStartMinutes(
                armed = true,
                transcription = done(),
                hasMinutes = false,
                minutes = MinutesUiState.Generating(),
            ),
        )
    }
}
