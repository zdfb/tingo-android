package io.github.tancheng33.tingo.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 备份/恢复在真实 Android 文件系统上走一遍。
 *
 * 单测跑在 JVM 上；这里额外确认 Android 上 `java.util.zip` 与中文文件名没问题。
 */
@RunWith(AndroidJUnit4::class)
class LibraryBackupInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun dir(name: String): File = File(context.cacheDir, name).apply {
        deleteRecursively()
        mkdirs()
    }

    @Test
    fun backupAndRestoreRoundTripOnDevice() {
        val source = dir("backup-src")
        val meeting = File(source, "2026-11-01_0900_周会").apply { mkdirs() }
        File(meeting, "纪要.md").writeText("# 一句话总结\n\n测试\n")
        File(meeting, "transcript.json").writeText("""{"source":"录音.wav","durationMs":1000,"sentences":[]}""")

        val zip = File(dir("backup-out"), "library.zip")
        assertEquals(1, LibraryBackup.backupTo(source, zip))
        assertTrue(zip.length() > 0)

        val target = dir("backup-restored")
        val report = LibraryBackup.restoreFrom(zip, target)

        assertEquals(listOf("2026-11-01_0900_周会"), report.restored)
        assertEquals(
            "# 一句话总结\n\n测试\n",
            File(target, "2026-11-01_0900_周会/纪要.md").readText(),
        )
        // 再恢复一次：同名会议应被跳过而不是覆盖
        assertEquals(listOf("2026-11-01_0900_周会"), LibraryBackup.restoreFrom(zip, target).skipped)
    }
}
