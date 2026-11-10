package io.github.tancheng33.tingo.recording

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 试录探针：引导里那句"听到了声音"必须是真的测出来的。
 *
 * 判断错的两个方向都很难看：说有声音但其实没录到（用户以为能录，真开会才发现是哑的），
 * 或者没声音却说正常。这里把"峰值取样、失败传播、临时文件清理"都钉住。
 */
class CaptureProbeTest {

    private class FakeCapture(
        private val levels: List<Float>,
        private val failOnStart: Throwable? = null,
        private val failDuringRun: Throwable? = null,
    ) : AudioCapture {
        var started: File? = null
        var stopped = false
        private var index = 0

        override fun start(file: File) {
            failOnStart?.let { throw it }
            started = file
        }

        override fun pause() = Unit

        override fun resume() = Unit

        override fun stop(): Long {
            stopped = true
            return 0
        }

        override fun lastFailure(): Throwable? = failDuringRun

        override fun level(): Float = levels.getOrElse(index++) { levels.lastOrNull() ?: 0f }
    }

    private fun scratch(): File = Files.createTempDirectory("probe").resolve("probe.wav").toFile()

    @Test
    fun peakLevelDecidesWhetherSomethingWasHeard() = runTest {
        val loud = FakeCapture(listOf(0.001f, 0.3f, 0.002f))

        val result = probeCapture(loud, scratch(), seconds = 1, tickMs = 10)

        assertTrue("中间那一 tick 有声音就算听到了", result.heardSomething)
        assertEquals(0.3f, result.peak ?: 0f, 0.0001f)
    }

    @Test
    fun nearSilentLevelIsReportedAsQuiet() = runTest {
        val quiet = FakeCapture(listOf(0.001f, 0.008f))

        val result = probeCapture(quiet, scratch(), seconds = 1, tickMs = 10)

        assertFalse("只有底噪不算听到", result.heardSomething)
        assertNull("没有失败原因", result.failure)
    }

    @Test
    fun micThatCannotOpenIsReportedAsFailure() = runTest {
        val broken = FakeCapture(emptyList(), failOnStart = IllegalStateException("设备被占用"))

        val result = probeCapture(broken, scratch(), seconds = 1, tickMs = 10)

        assertNotNull("打不开要如实说，而不是悄悄判成没声音", result.failure)
        assertFalse(result.heardSomething)
    }

    @Test
    fun fatalErrorDuringRecordingSurfaces() = runTest {
        val capture = FakeCapture(listOf(0.5f), failDuringRun = IllegalStateException("麦克风被拔了"))

        val result = probeCapture(capture, scratch(), seconds = 1, tickMs = 10)

        assertNotNull(result.failure)
        assertFalse("出错时不能报「听到了声音」", result.heardSomething)
    }

    @Test
    fun thresholdIsTheDesktopsOneHundredth() = runTest {
        // 与桌面 /api/record/test 的 `l >= 0.01` 对齐：阈值高了会把安静但正常的房间判成没声音
        assertEquals(0.01f, CaptureProbeResult.VOICE_LEVEL, 0.0001f)

        val justSilent = FakeCapture(listOf(0.009f))
        val justAudible = FakeCapture(listOf(0.01f))

        assertFalse(probeCapture(justSilent, scratch(), seconds = 1, tickMs = 10).heardSomething)
        assertTrue("正好到门限就算听到（与桌面的 >= 一致）", probeCapture(justAudible, scratch(), seconds = 1, tickMs = 10).heardSomething)
    }

    @Test
    fun failingStopSurfacesInsteadOfLookingSilent() = runTest {
        val capture = object : AudioCapture {
            override fun start(file: File) = Unit
            override fun pause() = Unit
            override fun resume() = Unit
            override fun stop(): Long = throw IllegalStateException("wav 头写不进去")
            override fun level(): Float = 0f
        }

        val result = probeCapture(capture, scratch(), seconds = 1, tickMs = 10)

        assertNotNull("停止时的错误不能被吞成「没声音」", result.failure)
    }

    @Test
    fun zeroTickDoesNotCrashWhileTheMicIsOpen() = runTest {
        val capture = FakeCapture(listOf(0.2f))

        val result = probeCapture(capture, scratch(), seconds = 1, tickMs = 0)

        assertEquals(0.2f, result.peak ?: 0f, 0.0001f)
    }

    @Test
    fun scratchFileIsRemovedAndCaptureStopped() = runTest {
        val capture = FakeCapture(listOf(0.2f))
        val file = scratch()

        probeCapture(capture, file, seconds = 1, tickMs = 10)

        assertTrue("探针必须停下来，否则麦克风一直开着", capture.stopped)
        assertFalse("3 秒的探针文件不该留在缓存里", file.exists())
    }

    @Test
    fun secondsAreClampedToTheDesktopsOneToTen() = runTest {
        val capture = FakeCapture(listOf(0.2f))

        assertEquals(1, probeCapture(capture, scratch(), seconds = 0, tickMs = 10).seconds)
        assertEquals(10, probeCapture(capture, scratch(), seconds = 99, tickMs = 10).seconds)
    }
}
