package io.github.tancheng33.tingo.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingSessionTest {

    @Test
    fun elapsedGrowsWhileRecording() {
        val session = RecordingSession()
        session.start(nowMs = 1_000)
        assertEquals(0L, session.elapsedMs(1_000))
        assertEquals(2_500L, session.elapsedMs(3_500))
    }

    @Test
    fun pausedTimeIsNotCounted() {
        val session = RecordingSession()
        session.start(1_000)
        session.pause(3_000) // 录了 2 秒
        assertEquals(2_000L, session.elapsedMs(13_000)) // 暂停 10 秒也不算
        session.resume(13_000)
        assertEquals(2_500L, session.elapsedMs(13_500))
    }

    @Test
    fun finishFreezesDuration() {
        val session = RecordingSession()
        session.start(0)
        assertEquals(4_000L, session.finish(4_000))
        assertEquals(4_000L, session.elapsedMs(9_999))
        assertTrue(session.state is RecordingSession.State.Finished)
    }

    @Test
    fun cannotPauseWhenNotRecording() {
        val session = RecordingSession()
        assertTrue(runCatching { session.pause(0) }.isFailure)
    }

    @Test
    fun cannotResumeWhenNotPaused() {
        val session = RecordingSession()
        session.start(0)
        assertTrue(runCatching { session.resume(10) }.isFailure)
    }

    @Test
    fun failureKeepsReason() {
        val session = RecordingSession()
        session.start(0)
        session.fail("麦克风被占用")
        assertEquals(RecordingSession.State.Failed("麦克风被占用"), session.state)
        assertEquals(0L, session.elapsedMs(1_000))
    }
}
