package io.github.zdfb.tingo.wear.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WearProtocolTest {
    private fun frame(sequence: Long = 0) = WearAudioFrame(
        sessionId = "session-123",
        sequence = sequence,
        sampleRate = 16_000,
        channels = 1,
        samples = shortArrayOf(-320, 0, 320, 1024),
    )

    @Test
    fun audioFrameRoundTrips() {
        val decoded = WearFrameDecoder.decode(WearFrameDecoder.encode(frame(7)))
        assertEquals("session-123", decoded.sessionId)
        assertEquals(7, decoded.sequence)
        assertEquals(16_000, decoded.sampleRate)
        assertArrayEquals(shortArrayOf(-320, 0, 320, 1024), decoded.samples)
    }

    @Test
    fun decoderAcceptsArbitraryStreamChunks() {
        val encoded = WearFrameDecoder.encode(frame()) + WearFrameDecoder.encode(frame(1))
        val decoder = WearFrameDecoder()
        val result = encoded.asList().chunked(3).flatMap { decoder.offer(it.toByteArray()) }
        assertEquals(listOf(0L, 1L), result.map { it.sequence })
    }

    @Test
    fun decoderRejectsInvalidFrameHeader() {
        val decoder = WearFrameDecoder()
        val error = runCatching { decoder.offer(ByteArray(12) { 1 }) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun sequenceTrackerDistinguishesDuplicateAndGap() {
        val tracker = WearSequenceTracker()
        assertEquals(WearSequenceResult.Accepted, tracker.accept(0))
        assertEquals(WearSequenceResult.Duplicate, tracker.accept(0))
        assertEquals(WearSequenceResult.Gap(1, 3), tracker.accept(3))
        assertEquals(WearSequenceResult.Accepted, tracker.accept(1))
    }

    @Test
    fun controlMessageRoundTripsUnicodeDetail() {
        val original = WearControlMessage("session-123", WearControlAction.STATUS, "手机延迟 2 秒")
        assertEquals(original, WearControlCodec.decode(WearControlCodec.encode(original)))
    }
}
