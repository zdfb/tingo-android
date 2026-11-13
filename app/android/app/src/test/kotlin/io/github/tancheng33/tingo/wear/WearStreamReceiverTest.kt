package io.github.tancheng33.tingo.wear

import io.github.zdfb.tingo.wear.protocol.WearAudioFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WearStreamReceiverTest {
    private fun frame(session: String = "s", sequence: Long) = WearAudioFrame(
        sessionId = session,
        sequence = sequence,
        sampleRate = 16_000,
        channels = 1,
        samples = shortArrayOf(1, 2, 3),
    )

    @Test
    fun acceptsOnlyCurrentSessionInOrder() {
        val received = mutableListOf<Long>()
        val receiver = WearStreamReceiver(onAudio = { received += it.sequence })
        receiver.begin("s")

        assertEquals(WearReceiveResult.Accepted, receiver.accept(frame(sequence = 0)))
        assertEquals(WearReceiveResult.Accepted, receiver.accept(frame(sequence = 1)))
        assertEquals(listOf(0L, 1L), received)
    }

    @Test
    fun duplicateIsIgnoredAndGapIsReported() {
        val gaps = mutableListOf<Pair<Long, Long>>()
        val receiver = WearStreamReceiver(onAudio = {}, onGap = { expected, actual -> gaps += expected to actual })
        receiver.begin("s")

        receiver.accept(frame(sequence = 0))
        assertEquals(WearReceiveResult.Duplicate, receiver.accept(frame(sequence = 0)))
        assertEquals(WearReceiveResult.Gap(1, 3), receiver.accept(frame(sequence = 3)))
        assertEquals(listOf(1L to 3L), gaps)
    }

    @Test
    fun oldSessionCannotFinishOrInjectAudio() {
        val received = mutableListOf<Long>()
        val receiver = WearStreamReceiver(onAudio = { received += it.sequence })
        receiver.begin("new")

        assertEquals(WearReceiveResult.IgnoredSession, receiver.accept(frame("old", 0)))
        assertFalse(receiver.finish("old"))
        assertTrue(receiver.finish("new"))
        assertFalse(receiver.isReceiving)
        assertTrue(received.isEmpty())
    }
}
