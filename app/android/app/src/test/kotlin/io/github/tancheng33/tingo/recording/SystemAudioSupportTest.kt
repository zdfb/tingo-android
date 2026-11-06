package io.github.tancheng33.tingo.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class SystemAudioSupportTest {

    @Test
    fun notRequestedWhenTheSwitchIsOff() {
        assertEquals(
            SystemAudioSupport.NotRequested,
            SystemAudioSupport.decide(requested = false, granted = true, sdk = 34),
        )
    }

    @Test
    fun unsupportedBelowAndroidTen() {
        assertEquals(
            SystemAudioSupport.UnsupportedOs,
            SystemAudioSupport.decide(requested = true, granted = true, sdk = 28),
        )
    }

    @Test
    fun deniedWhenUserRejectsScreenCapture() {
        assertEquals(
            SystemAudioSupport.PermissionDenied,
            SystemAudioSupport.decide(requested = true, granted = false, sdk = 34),
        )
    }

    @Test
    fun availableOnAndroidTenWithPermission() {
        assertEquals(
            SystemAudioSupport.Available,
            SystemAudioSupport.decide(requested = true, granted = true, sdk = 29),
        )
        assertEquals(
            SystemAudioSupport.Available,
            SystemAudioSupport.decide(requested = true, granted = true, sdk = 36),
        )
    }

    @Test
    fun onlyAvailableActuallyCaptures() {
        val decisions = listOf(
            SystemAudioSupport.Available,
            SystemAudioSupport.NotRequested,
            SystemAudioSupport.UnsupportedOs,
            SystemAudioSupport.PermissionDenied,
        )

        assertEquals(
            listOf(SystemAudioSupport.Available),
            decisions.filter { SystemAudioSupport.run { it.canCapture() } },
        )
    }
}
