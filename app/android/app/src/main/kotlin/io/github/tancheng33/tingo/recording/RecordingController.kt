package io.github.tancheng33.tingo.recording

import io.github.tancheng33.tingo.ui.record.RecordViewModel
import io.github.tancheng33.tingo.ui.record.RecordUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Keeps the notification connected to a recording when no activity is visible. */
class RecordingController(
    val viewModel: RecordViewModel,
    scope: CoroutineScope,
    private val stopService: () -> Unit,
    private val onCompleted: (CompletedRecording) -> Unit = {},
) : AutoCloseable {
    data class CompletedRecording(val meetingId: String, val transcribe: Boolean, val minutes: Boolean)
    data class ProcessingOptions(val transcribe: Boolean = true, val minutes: Boolean = true)
    @Volatile var processingOptions = ProcessingOptions()
        private set

    fun configureNextRecording(transcribe: Boolean, minutes: Boolean) {
        processingOptions = ProcessingOptions(transcribe, transcribe && minutes)
    }

    private val observer: Job

    init {
        viewModel.setCompletionListener { state ->
            val options = processingOptions
            onCompleted(CompletedRecording(state.meetingId, options.transcribe, options.minutes))
        }
        RecordingKeepAlive.onServiceDestroyed = { viewModel.onInterrupted() }
        RecordingKeepAlive.onStopRequested = { viewModel.stop() }
        RecordingKeepAlive.onSystemAudioCapture = { capture ->
            if (capture == null) viewModel.systemAudioFailed() else viewModel.attachSystemAudio(capture)
        }
        observer = scope.launch {
            var active = false
            viewModel.state.collect { state ->
                val nextActive = requiresForegroundService(state)
                RecordingKeepAlive.publish(if (nextActive) state else null)
                if (active && !nextActive) stopService()
                active = nextActive
            }
        }
    }

    override fun close() {
        observer.cancel()
        viewModel.setCompletionListener {}
        RecordingKeepAlive.onServiceDestroyed = {}
        RecordingKeepAlive.onStopRequested = {}
        RecordingKeepAlive.onSystemAudioCapture = { capture -> runCatching { capture?.stop() } }
        RecordingKeepAlive.publish(null)
    }
}
