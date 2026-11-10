package io.github.tancheng33.tingo

import androidx.test.platform.app.InstrumentationRegistry
import io.github.tancheng33.tingo.engine.TingoEnginePort
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 清理旧音频在真机上跑一遍：真文件、真日期判断。
 *
 * 这条链路错了会很难看——会议还在列表里，点开却没有声音。所以断言两头：
 * 旧会议的音频真的被删、纪要还在、新会议的音频一根都没动。
 */
class AudioCleanupInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun engine(): TingoEnginePort {
        val root = File(context.cacheDir, "cleanup-audio-test").apply { deleteRecursively(); mkdirs() }
        return TingoEnginePort.native(root.absolutePath)
    }

    private fun meeting(root: String, id: String, audioBytes: Int): File {
        val dir = File(root, id).apply { mkdirs() }
        File(dir, "录音.wav").writeBytes(ByteArray(audioBytes))
        File(dir, "纪要.md").writeText("# 周会\n\n## 决议\n- 先修首屏\n")
        return dir
    }

    @Test
    fun oldAudioGoesWhileMinutesStay() {
        val engine = engine()
        val root = engine.meetingsRoot()
        val old = meeting(root, "2020-01-01_1000_旧会", 4096)
        val recent = meeting(root, "2026-11-10_1000_新会", 2048)

        val report = engine.cleanupAudio(olderThanDays = 30u, archivedOnly = false)

        assertEquals("只该清掉那场旧会议", 1u, report.meetings)
        assertEquals(4096uL, report.freedBytes)
        assertFalse("旧会议的录音该被删掉", File(old, "录音.wav").exists())
        assertTrue("纪要必须留着", File(old, "纪要.md").isFile)
        assertTrue("30 天内的录音不能动", File(recent, "录音.wav").isFile)
    }

    @Test
    fun limitingToArchivedLeavesLiveMeetingsAlone() {
        val engine = engine()
        val root = engine.meetingsRoot()
        val archived = meeting(root, "2020-01-01_1000_归档的", 512)
        val live = meeting(root, "2020-01-02_1000_没归档", 512)
        engine.setArchived("2020-01-01_1000_归档的", true)

        val report = engine.cleanupAudio(olderThanDays = 0u, archivedOnly = true)

        assertEquals(1u, report.meetings)
        assertFalse(File(archived, "录音.wav").exists())
        assertTrue("没归档的会议不该被动", File(live, "录音.wav").isFile)
    }
}
