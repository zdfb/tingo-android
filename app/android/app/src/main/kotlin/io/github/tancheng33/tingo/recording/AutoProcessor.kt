package io.github.tancheng33.tingo.recording

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Starts the chosen processing chain independently of the current screen. */
class AutoProcessor(
    private val scope: CoroutineScope,
    private val transcribe: suspend (String) -> Boolean,
    private val minutes: suspend (String) -> Unit,
    private val onStarted: () -> Unit = {},
    private val onFinished: () -> Unit = {},
) {
    private var active = 0

    fun submit(recording: RecordingController.CompletedRecording) {
        if (!recording.transcribe) return
        synchronized(this) {
            if (active++ == 0) onStarted()
        }
        scope.launch {
            try {
                if (transcribe(recording.meetingId) && recording.minutes) minutes(recording.meetingId)
            } finally {
                synchronized(this@AutoProcessor) {
                    active--
                    if (active == 0) onFinished()
                }
            }
        }
    }
}
