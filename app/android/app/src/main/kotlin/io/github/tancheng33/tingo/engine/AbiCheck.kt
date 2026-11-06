package io.github.tancheng33.tingo.engine

import android.os.Build
import io.github.tancheng33.tingo.BuildConfig

/**
 * 原生库的 ABI 支持检查。
 *
 * 只出 arm64-v8a（真机）与 x86_64（模拟器）两个 ABI，所以启动加载原生库之前
 * 必须先确认设备 ABI 命中，否则得到的会是一个看不懂的 `UnsatisfiedLinkError`。
 */
object AbiCheck {
    /**
     * 打包进 APK 的 ABI。
     *
     * 由 `build.gradle.kts` 从 `tingoRustAbis` 注入（BuildConfig.SUPPORTED_ABIS），
     * 不在这里再写一份——两份清单迟早会对不上。
     */
    val supportedAbis: Set<String> = BuildConfig.SUPPORTED_ABIS.split(",").toSet()

    /** 设备是否至少命中一个受支持的 ABI。 */
    fun isSupported(deviceAbis: List<String> = Build.SUPPORTED_ABIS.toList()): Boolean =
        deviceAbis.any { it in supportedAbis }
}
