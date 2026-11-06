package io.github.tancheng33.tingo.engine

import android.os.Build

/**
 * 原生库的 ABI 支持检查。
 *
 * 只出 arm64-v8a（真机）与 x86_64（模拟器）两个 ABI，所以启动加载原生库之前
 * 必须先确认设备 ABI 命中，否则得到的会是一个看不懂的 `UnsatisfiedLinkError`。
 */
object AbiCheck {
    /** 打包进 APK 的 ABI，与 `build.gradle.kts` 里的 `tingoRustAbis` 保持一致。 */
    val supportedAbis: Set<String> = setOf("arm64-v8a", "x86_64")

    /** 设备是否至少命中一个受支持的 ABI。 */
    fun isSupported(deviceAbis: List<String> = Build.SUPPORTED_ABIS.toList()): Boolean =
        deviceAbis.any { it in supportedAbis }
}
