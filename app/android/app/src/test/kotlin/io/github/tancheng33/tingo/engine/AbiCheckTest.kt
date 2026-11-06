package io.github.tancheng33.tingo.engine

import io.github.tancheng33.tingo.BuildConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ABI 前置校验。
 *
 * 期望值取自构建期注入的 `SUPPORTED_ABIS`，而不是写死："这个构建打了哪些 ABI"
 * 是 Gradle 的属性（`-PtingoRustAbis`），测试跟着它走才不会在单 ABI 构建里假失败。
 */
class AbiCheckTest {

    @Test
    fun everyPackagedAbiIsSupported() {
        assertTrue(AbiCheck.supportedAbis.isNotEmpty())
        for (abi in BuildConfig.SUPPORTED_ABIS.split(",")) {
            assertTrue("packaged abi $abi should be supported", AbiCheck.isSupported(listOf(abi)))
        }
    }

    @Test
    fun deviceAbiListOrderDoesNotMatter() {
        val packaged = BuildConfig.SUPPORTED_ABIS.split(",").first()
        assertTrue(AbiCheck.isSupported(listOf("armeabi-v7a", packaged, "armeabi")))
    }

    @Test
    fun thirtyTwoBitOnlyDeviceIsUnsupported() {
        // 只出 64 位原生库：32 位设备上应提前提示，而不是让动态库加载失败
        assertFalse(AbiCheck.isSupported(listOf("armeabi-v7a", "armeabi")))
    }

    @Test
    fun unknownAbiIsUnsupported() {
        assertFalse(AbiCheck.isSupported(listOf("riscv64")))
    }
}
