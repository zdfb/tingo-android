package io.github.tancheng33.tingo.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AbiCheckTest {

    @Test
    fun arm64DeviceIsSupported() {
        assertTrue(AbiCheck.isSupported(listOf("arm64-v8a", "armeabi-v7a", "armeabi")))
    }

    @Test
    fun emulatorX8664IsSupported() {
        assertTrue(AbiCheck.isSupported(listOf("x86_64", "x86")))
    }

    @Test
    fun thirtyTwoBitOnlyDeviceIsUnsupported() {
        // 只出 64 位原生库：32 位设备上应提前提示，而不是让动态库加载失败
        assertFalse(AbiCheck.isSupported(listOf("armeabi-v7a", "armeabi")))
    }

    @Test
    fun arm64PreferredOrderDoesNotMatter() {
        assertTrue(AbiCheck.isSupported(listOf("x86", "armeabi-v7a", "arm64-v8a")))
    }
}
