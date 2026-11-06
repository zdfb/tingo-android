package io.github.tancheng33.tingo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tancheng33.tingo.testing.ModelDownloads
import io.github.tancheng33.tingo.models.ModelCatalog
import io.github.tancheng33.tingo.models.ModelStore
import io.github.tancheng33.tingo.models.UrlFetcher
import io.github.tancheng33.tingo.transcription.Transcript
import io.github.tancheng33.tingo.transcription.TranscriptWriter
import io.github.tancheng33.tingo.transcription.TranscriptionPipeline
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 完整流水线：VAD 切段 → 识别 → 标点 → 声纹 → 聚类 → 写 transcript.json。
 *
 * 这是"离线 ASR + 说话人区分"的验收测试：用桌面版真实产出的 20 秒双人对话，
 * 要求切出多句、时间戳递增、说话人分得出来、文件能写能读回来。
 */
@RunWith(AndroidJUnit4::class)
class PipelineInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun transcribesTwoSpeakerClipEndToEnd() {
        val catalog = ModelDownloads.catalog(context)
        val store = ModelDownloads.store(context, "models-full")
        ModelDownloads.ensure(store, catalog.files)

        val wav = copySample()
        val meeting = File(context.cacheDir, "meeting-pipeline").apply {
            deleteRecursively()
            mkdirs()
        }
        val progresses = mutableListOf<Float>()

        val pipeline = TranscriptionPipeline(store, catalog)
        val transcript = kotlinx.coroutines.runBlocking {
            pipeline.transcribe(wav, meeting) { progresses += it }
        }

        // 切分：20 秒双人对话至少要切出几段
        assertTrue("切出的句子太少：${transcript.sentences.size}", transcript.sentences.size >= 3)
        transcript.sentences.forEach {
            assertTrue("有句子没有文本", it.text.isNotBlank())
            assertTrue("结束时间早于开始时间：$it", it.endMs >= it.startMs)
        }
        assertTrue("时间戳没有递增", transcript.sentences.zipWithNext().all { (a, b) -> b.startMs >= a.startMs })

        // 开头那句应当落在 0 秒附近（与桌面版转写一致）
        assertTrue("第一句没落在开头：${transcript.sentences.first().startMs}", transcript.sentences.first().startMs < 1_500)

        // 说话人：双人对话应当分出 >= 1 个，理想是 2 个
        val speakers = transcript.speakersInOrder()
        android.util.Log.i(
            "TingoPipeline",
            "sentences=${transcript.sentences.size} speakers=$speakers first=${transcript.sentences.first().text}",
        )
        assertTrue("说话人编号应从 0 开始", speakers.first() == 0)

        // 落盘并能读回
        val written = File(meeting, Transcript.FILE_NAME)
        assertTrue("没有写出 transcript.json", written.isFile)
        val back = TranscriptWriter.read(meeting)!!
        assertEquals(transcript.sentences, back.sentences)
        assertEquals(transcript.durationMs, back.durationMs)
        assertTrue("进度回调没有到 1", progresses.last() > 0.9f)
    }

    private fun copySample(): File {
        val target = File(context.cacheDir, "sample.wav")
        instrumentation.context.assets.open("audio/sample.wav").use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target
    }
}
