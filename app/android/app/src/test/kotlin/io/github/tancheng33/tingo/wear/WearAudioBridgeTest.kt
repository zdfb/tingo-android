package io.github.tancheng33.tingo.wear

import io.github.zdfb.tingo.wear.protocol.WearAudioFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WearAudioBridgeTest {
    private fun frame(sequence: Long) = WearAudioFrame("session", sequence, 16_000, 1, shortArrayOf(1, 2))

    @Test
    fun bridgeForwardsAcceptedFramesToAttachedListener() {
        val received = mutableListOf<Long>()
        val bridge = WearAudioBridge()
        bridge.attach { received += it.sequence }
        bridge.begin("session")

        assertEquals(WearReceiveResult.Accepted, bridge.accept(frame(0)))
        assertEquals(listOf(0L), received)
        assertTrue(bridge.state.value is WearBridgeState.Receiving)
    }

    @Test
    fun bridgeExposesMissingFrameState() {
        val bridge = WearAudioBridge()
        bridge.begin("session")
        bridge.accept(frame(0))

        assertEquals(WearReceiveResult.Gap(1, 4), bridge.accept(frame(4)))
        assertEquals(WearBridgeState.MissingFrames(1, 4), bridge.state.value)
    }
}
