package io.github.tancheng33.tingo.recording

import io.github.tancheng33.tingo.transcription.LiveAsr
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 边录边转：窗口对齐、短段丢弃、停止时把尾巴识别完。
 *
 * 这几条错了用户会看到"实时文字比最终转写少一截"或"录音卡住"，而且很难复现。
 */
class LiveTranscriberTest {

    /** 每 [windowsPerSegment] 个窗口就吐出一段人声。 */
    private class FakeAsr(private val windowsPerSegment: Int = 1) : LiveAsr {
        val windows = mutableListOf<FloatArray>()
        private val segments = ArrayDeque<FloatArray>()
        private var sinceLast = 0
        var flushed = false
        private val lock = Any()

        override fun acceptWindow(window: FloatArray) {
            synchronized(lock) {
                windows += window
                sinceLast++
                if (sinceLast >= windowsPerSegment) {
                    segments += FloatArray(16_000 * 1) // 1 秒的人声
                    sinceLast = 0
                }
            }
        }

        override fun hasSegment(): Boolean = synchronized(lock) { segments.isNotEmpty() }

        override fun popSegment(): FloatArray = synchronized(lock) { segments.removeFirst() }

        override fun flush() {
            flushed = true
        }

        override fun recognize(samples: FloatArray): String = "第 ${samples.size} 个样本的一段话"
    }

    private fun samples(count: Int, value: Float = 0.1f) = FloatArray(count) { value }

    @Test
    fun windowsAreFedAndSentencesComeOut() {
        val asr = FakeAsr()
        val lines = CopyOnWriteArrayList<String>()
        val live = LiveTranscriber(asr = asr, onSentence = { lines += it })

        live.start()
        live.offer(samples(512 * 4))
        // 推理线程异步：给它一点时间把窗口喂完
        val deadline = System.currentTimeMillis() + 2_000
        while (lines.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        live.close()

        assertTrue("应该识别出句子：$lines", lines.isNotEmpty())
        assertTrue("窗口必须按 512 对齐喂进去", asr.windows.all { it.size == 512 })
        assertTrue("停止时要 flush VAD，否则最后一句会丢", asr.flushed)
    }

    @Test
    fun partialWindowsAreCarriedOverNotDropped() {
        val asr = FakeAsr(windowsPerSegment = 1_000) // 不出句，只看窗口
        val live = LiveTranscriber(asr = asr, onSentence = {})

        live.start()
        live.offer(samples(512 * 2 + 100))
        val deadline = System.currentTimeMillis() + 2_000
        while (asr.windows.size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        live.close()

        assertEquals("只喂整窗口：不足一窗的样本留着等下一批", 2, asr.windows.size)
    }

    @Test
    fun aShortBlipIsNotShownAsASentence() {
        val asr = object : LiveAsr {
            var popped = false
            override fun acceptWindow(window: FloatArray) = Unit
            override fun hasSegment(): Boolean = !popped
            override fun popSegment(): FloatArray {
                popped = true
                return FloatArray(16_000 / 4) // 250 ms：噪声级别
            }
            override fun flush() = Unit
            override fun recognize(samples: FloatArray): String = "噪声"
        }
        val lines = CopyOnWriteArrayList<String>()
        val live = LiveTranscriber(asr = asr, onSentence = { lines += it })

        live.start()
        live.offer(samples(512))
        Thread.sleep(300)
        live.close()

        assertEquals("半秒以下的人声不该出现在实时文字里", emptyList<String>(), lines.toList())
    }

    @Test
    fun offeringAfterCloseIsIgnored() {
        val asr = FakeAsr()
        val lines = CopyOnWriteArrayList<String>()
        val live = LiveTranscriber(asr = asr, onSentence = { lines += it })
        live.close()

        live.offer(samples(512 * 100))
        Thread.sleep(200)

        assertTrue("关掉之后不该再有句子：$lines", lines.isEmpty())
        assertTrue("关掉之后也不该再喂 VAD", asr.windows.isEmpty())
    }
}
