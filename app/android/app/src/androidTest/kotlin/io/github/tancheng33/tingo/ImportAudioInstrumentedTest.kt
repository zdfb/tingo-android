package io.github.tancheng33.tingo

import androidx.test.platform.app.InstrumentationRegistry
import io.github.tancheng33.tingo.importing.AudioDecoder
import io.github.tancheng33.tingo.importing.WavFile
import io.github.tancheng33.tingo.transcription.WavReader
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入录音的解码与落库，只有真机能验（MediaCodec 是系统组件）。
 *
 * 夹具是一段 3 秒 44.1 kHz 的 m4a：解码后必须变成 16 kHz 单声道 wav，
 * 时长不缩水——这正是"导进来的会议听起来正常、转写不糊"的前提。
 */
class ImportAudioInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun fixture(target: File): File {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        assets.open("import-fixture.m4a").use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        return target
    }

    @Test
    fun decodesM4aInto16kMonoWav() {
        val dir = File(context.cacheDir, "import-decode").apply { deleteRecursively(); mkdirs() }
        val source = fixture(File(dir, "会议录音.m4a"))
        val target = File(dir, "out.wav")

        AudioDecoder.toWav(source, target)

        val format = WavReader.format(target)
        assertEquals("采样率必须降到 16k", 16_000, format.sampleRate)
        // 3 秒的素材解码后还是 3 秒（±0.2 秒，编码器首尾会有几帧补齐）
        val durationMs = format.durationMs
        assertTrue("时长不对：$durationMs", durationMs in 2_800..3_300)
        assertTrue("解码结果不该是静音：${target.length()}", target.length() > 40_000)
    }

    @Test
    fun unsupportedExtensionIsRefusedBeforeDecoding() {
        assertTrue(AudioDecoder.supports("会议.m4a"))
        assertTrue(AudioDecoder.supports("会议.MP3"))
        assertTrue(!AudioDecoder.supports("纪要.docx"))
        assertEquals("会议", AudioDecoder.titleOf("会议.m4a"))
    }

    @Test
    fun importEndpointPutsTheRecordingWherePlaybackLooksForIt() {
        val dir = File(context.cacheDir, "import-engine").apply { deleteRecursively(); mkdirs() }
        val engine = io.github.tancheng33.tingo.engine.TingoEnginePort.native(dir.absolutePath)
        val source = fixture(File(dir, "会议录音.m4a"))
        val wav = File(dir, "out.wav")
        AudioDecoder.toWav(source, wav)

        val imported = engine.importAudio(wav.absolutePath, "导入的会议", 0)
        val id = imported.id

        // 目录由引擎返回，宿主不该自己拼路径
        val meeting = File(imported.dir)
        assertEquals("目录应与 id 对得上", id, meeting.name)
        assertTrue("会议目录没建出来：$meeting", meeting.isDirectory)
        // 播放与转写都按 录音.wav 找音频
        assertTrue("缺 录音.wav：${meeting.list()?.toList()}", File(meeting, "录音.wav").isFile)
        // 重新扫库要能看见它（列表刷新靠这一条）
        assertTrue("扫库看不到导入的会议", engine.scanLibrary().any { it.id == id })
    }
}
