package io.github.tancheng33.tingo.recording

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 静音自动结束的判断与"截掉静音尾巴"。
 *
 * 两条都容易做错且不容易发现：判断太灵敏会在有人小声说话时把录音掐掉；
 * 截断改不好会让播放器按原长度读（后面一段空白），或者干脆打不开文件。
 */
class SilenceWatcherTest {

    @Test
    fun voiceKeepsResettingTheClock() {
        val watcher = SilenceWatcher(silenceMinutes = 10)

        // 每 5 秒喂一次：前 9 分钟一直有人说话，第 10 分钟开始安静
        var stop: SilenceWatcher.AutoStop? = null
        (0..600).forEach { second ->
            val level = if (second < 540) 0.6f else 0.01f
            stop = watcher.observe(level, elapsedMs = second * 1_000L)
        }

        // 540 秒 + 600 秒 = 已安静 60 秒，不到 10 分钟，不该停
        assertNull("不该在安静 1 分钟时就停", stop)
    }

    @Test
    fun stopsAfterTheConfiguredSilence() {
        val watcher = SilenceWatcher(silenceMinutes = 5)
        var stop: SilenceWatcher.AutoStop? = null
        (0..600).forEach { second ->
            val level = if (second < 60) 0.6f else 0f
            val now = watcher.observe(level, elapsedMs = second * 1_000L)
            if (now != null) stop = now
        }

        // 最后有声在第 59 秒；5 分钟后（359 秒）才该停
        assertEquals(59_000L, watcher.lastVoiceMs)
        assertTrue("应该已经自动停止", stop != null)
        assertEquals(5, stop!!.minutes)
        // 只保留到最后有声之后 30 秒
        assertEquals(89_000L, stop!!.keepMs)
    }

    @Test
    fun zeroMinutesMeansNeverAutoStop() {
        val watcher = SilenceWatcher(silenceMinutes = 0)

        val stop = watcher.observe(level = 0f, elapsedMs = 10 * 60 * 60 * 1_000L)

        assertNull("设成 0 就是永远不自动停（与桌面同语义）", stop)
    }

    @Test
    fun quietNoiseFloorDoesNotCountAsSpeech() {
        val watcher = SilenceWatcher(silenceMinutes = 1)

        // 麦克风底噪 0.1 不该被当成有人说话：最后有声的时刻应该一直是 0，
        // 于是"安静 1 分钟"就从录音开始算起
        val stops = (0..120).mapNotNull { second ->
            watcher.observe(0.1f, elapsedMs = second * 1_000L)
        }

        assertEquals("底噪被当成了说话", 0L, watcher.lastVoiceMs)
        assertTrue("安静满 1 分钟就该停", stops.isNotEmpty())
        assertEquals(1, stops.first().minutes)
    }

    @Test
    fun resetStartsAFreshMeeting() {
        val watcher = SilenceWatcher(silenceMinutes = 5)
        watcher.observe(0.6f, elapsedMs = 30_000)

        watcher.reset()

        assertEquals(0L, watcher.lastVoiceMs)
        assertNull(watcher.observe(0f, elapsedMs = 60_000))
    }

    @Test
    fun trimmingCutsTheTailAndFixesTheHeader() {
        val file = Files.createTempFile("trim", ".wav").toFile().apply { deleteOnExit() }
        // 10 秒 16 kHz 单声道 = 320 000 字节数据
        val samples = ShortArray(160_000) { 1 }
        io.github.tancheng33.tingo.importing.WavFile.write(file, samples)

        val trimmed = WavTrim.trimTo(file, keepMs = 3_000)

        assertTrue("应当真的截短", trimmed)
        val format = io.github.tancheng33.tingo.transcription.WavReader.format(file)
        // 16 kHz 单声道 16 bit：3 秒 = 96 000 字节
        assertEquals(96_000L, format.dataBytes)
        // 头里的长度也要跟着改：否则播放器按 10 秒读，后面是空白
        assertEquals(3_000L, format.durationMs)
    }

    @Test
    fun trimmingShorterThanTheFileIsANoOp() {
        val file = Files.createTempFile("trim-noop", ".wav").toFile().apply { deleteOnExit() }
        io.github.tancheng33.tingo.importing.WavFile.write(file, ShortArray(16_000) { 1 })
        val before = file.length()

        val trimmed = WavTrim.trimTo(file, keepMs = 60_000)

        assertFalse("已经很短就不该动它", trimmed)
        assertEquals(before, file.length())
    }

    @Test
    fun trimmingIgnoresMissingFiles() {
        val missing = File("/nonexistent/x.wav")

        assertFalse(WavTrim.trimTo(missing, keepMs = 1_000))
    }
}
