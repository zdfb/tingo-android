package io.github.tancheng33.tingo

import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * sherpa-onnx 运行时可用性：AAR 里的 .so 能在设备上加载，识别相关类型在 classpath 上。
 *
 * 真正跑一次识别需要模型文件（约 227 MB），不适合塞进测试 APK；模型链路由
 * `add-android-recording-asr` 的任务 3.1/3.3 用"下载后校验"的方式验证。
 */
class SherpaRuntimeTest {

    @Test
    fun nativeLibraryLoads() {
        // 这个类在静态初始化里 System.loadLibrary("sherpa-onnx-jni")，
        // 能加载说明 AAR 的 arm64-v8a / x86_64 .so 与 APK 的 ABI 对得上。
        val clazz = Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizer")
        assertNotNull(clazz)
    }

    @Test
    fun recognizerConfigIsInstantiable() {
        val config = OfflineRecognizerConfig()
        assertNotNull(config.modelConfig)
        // 默认配置下模型路径为空：说明拿到的是"未配置"的干净对象，不是带默认模型的假象
        assertTrue(config.modelConfig.paraformer.model.isEmpty())
    }

    @Test
    fun resultTypeCarriesTextAndTimestamps() {
        // 结果类型是我们后续写 transcript.json 的形状来源
        val fields = OfflineRecognizerResult::class.java.declaredFields.map { it.name }
        assertTrue(fields.contains("text"))
        assertTrue(fields.contains("timestamps"))
    }
}
