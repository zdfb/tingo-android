package io.github.tancheng33.tingo

import androidx.test.platform.app.InstrumentationRegistry
import io.github.tancheng33.tingo.recording.MicRecorder
import io.github.tancheng33.tingo.recording.probeCapture
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 试录探针在真机上跑一遍：真麦克风、真耳机孔、真系统静音开关都可能让它失败。
 *
 * 用例会自己 `pm grant`（调试包上 shell 身份可用）；真给不了才跳过（`assumeTrue`），
 * 让它在没准备的机器上不至于红。
 */
class CaptureProbeInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * 用例自己授权：否则在干净设备/CI 上这条会永远跳过，"真机验证过"就成了空话。
     *
     * 先试 `pm grant`（shell 身份在调试包上可用），不行再看是不是本来就有权限。
     */
    private fun ensureGranted(): Boolean {
        if (granted()) return true
        runCatching {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("pm grant ${context.packageName} android.permission.RECORD_AUDIO")
                .close()
        }
        // 授权是异步生效的：给它一点时间
        repeat(20) {
            if (granted()) return true
            Thread.sleep(100)
        }
        return false
    }

    private fun granted(): Boolean = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

    @Test
    fun probeOpensTheMicAndCleansUp() = runBlocking {
        assumeTrue("没能授予录音权限，跳过", ensureGranted())
        val scratch = File(context.cacheDir, "probe-test.wav")

        val result = probeCapture(capture = MicRecorder(), scratch = scratch, seconds = 1, tickMs = 100)

        // 安静环境里峰值可能仍是 0，但"打开了麦克风并采到样"必须成立
        assertTrue("打不开麦克风：${result.failure}", result.failure == null)
        // 采到的电平必须是个数：`peak` 为 null 说明一次都没取样，那"听到了声音"就是假的
        val peak = result.peak
        assertNotNull("至少要采到一次电平", peak)
        assertTrue("电平该在 0..1：$peak", peak!! >= 0f && peak <= 1f)
        assertTrue("探针文件用完要删掉", !scratch.exists())
    }
}
