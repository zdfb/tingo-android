package io.github.tancheng33.tingo.importing

import io.github.tancheng33.tingo.testing.FakeEnginePort
import io.github.tancheng33.tingo.transcription.Transcript
import io.github.tancheng33.tingo.transcription.Transcriber
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入编排：解码 → 落成会议 → 转写。
 *
 * 真解码要 MediaCodec，只能真机跑；这里换掉解码器，验的是编排本身：
 * 交给引擎的是不是解码产物、标题怎么定、转写写进哪个目录、进度是否单调、失败会不会留垃圾。
 */
class AudioImportTest {

    /** 替身：不真解码，只记下调用并产出一个占位 wav。 */
    private class FakeDecoding(val works: Boolean = true) : AudioDecoding {
        var source: File? = null

        override fun toWav(source: File, target: File): File {
            this.source = source
            if (!works) throw IllegalArgumentException("这个文件里没有音频")
            target.writeBytes(ByteArray(44))
            return target
        }
    }

    private class FakeTranscriber : Transcriber {
        var directory: File? = null

        override suspend fun transcribe(wav: File, outputDir: File, onProgress: (Float) -> Unit): Transcript {
            directory = outputDir
            onProgress(0.5f)
            onProgress(1f)
            return Transcript(source = wav.name, durationMs = 1_000, sentences = emptyList())
        }
    }

    private fun importer(
        decoding: AudioDecoding = FakeDecoding(),
        transcriber: Transcriber = FakeTranscriber(),
        engine: FakeEnginePort = FakeEnginePort(),
    ): AudioImport = AudioImport(
        engine = engine,
        transcriber = transcriber,
        cacheDir = Files.createTempDirectory("tingo-import").toFile(),
        decoding = decoding,
    )

    /** 造一个"用户选中的文件"：`createTempFile` 要求前缀至少三个字符，所以补个后缀。 */
    private fun source(name: String): File = File.createTempFile("${name}source", ".m4a").apply {
        writeBytes(ByteArray(10))
        deleteOnExit()
    }

    @Test
    fun importDecodesThenHandsTheWavToTheEngine() = runTest {
        val file = source("会议")
        val engine = FakeEnginePort()

        val id = importer(engine = engine).run(file, title = "", modifiedMs = 0)

        val (path, title, _) = engine.importedAudio.single()
        assertTrue("交给引擎的应是解码后的 wav：$path", path.endsWith(".wav"))
        // 没给标题就用文件名，与桌面一致
        assertTrue("标题应取文件名：$title", title.isNotBlank())
        assertTrue("要返回会议 id", id.isNotBlank())
    }

    @Test
    fun transcriptionWritesIntoTheNewMeetingFolder() = runTest {
        val transcriber = FakeTranscriber()

        importer(transcriber = transcriber).run(source("会议"), title = "产品周会", modifiedMs = 0)

        assertTrue(
            "转写要落在会议目录里：${transcriber.directory}",
            transcriber.directory?.path?.contains("产品周会") == true,
        )
    }

    @Test
    fun progressIsMonotonicFromZeroToOne() = runTest {
        val seen = mutableListOf<Float>()

        importer().run(source("会议"), title = "周会", modifiedMs = 0) { seen += it }

        assertEquals(0f, seen.first(), 0f)
        assertEquals(1f, seen.last(), 0f)
        // 进度倒退比不动更让人怀疑出了错
        assertTrue("进度倒退了：$seen", seen.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun decodeFailureLeavesNoHalfImportedMeeting() = runTest {
        val engine = FakeEnginePort()

        val failure = runCatching {
            importer(decoding = FakeDecoding(works = false), engine = engine)
                .run(source("坏文件"), title = "坏文件", modifiedMs = 0)
        }.exceptionOrNull()

        assertTrue("解码失败要抛出来：$failure", failure is IllegalArgumentException)
        assertTrue("不该落成一场会议", engine.importedAudio.isEmpty())
    }

    @Test
    fun temporaryWavIsDeletedAfterImport() = runTest {
        val cache = Files.createTempDirectory("tingo-import-clean").toFile()
        val import = AudioImport(FakeEnginePort(), FakeTranscriber(), cache, FakeDecoding())

        import.run(source("会议"), title = "周会", modifiedMs = 0)

        // 中转文件不清理，每次导入都会在缓存里留一份整段录音
        assertTrue("缓存里不该留中转 wav：${cache.list()?.toList()}", cache.listFiles().orEmpty().isEmpty())
    }
}
