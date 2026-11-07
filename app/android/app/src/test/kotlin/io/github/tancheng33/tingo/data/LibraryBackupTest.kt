package io.github.tancheng33.tingo.data

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 整库备份/恢复：能带走、能找回，且不会因为一个恶意 zip 写到会议目录之外。 */
class LibraryBackupTest {

    private fun dir(name: String): File = Files.createTempDirectory("tingo-$name").toFile()

    private fun meeting(root: File, id: String, files: Map<String, String>): File {
        val dir = File(root, id).apply { mkdirs() }
        files.forEach { (name, content) -> File(dir, name).writeText(content) }
        return dir
    }

    @Test
    fun backupContainsEveryMeetingAndFile() {
        val root = dir("backup")
        meeting(root, "2026-11-01_0900_周会", mapOf("transcript.json" to "{}", "纪要.md" to "# 总结"))
        meeting(root, "2026-11-02_1000_复盘", mapOf("transcript.json" to "{}"))

        val zip = File(dir("out"), "library.zip")
        val count = LibraryBackup.backupTo(root, zip)

        assertEquals(2, count)
        val names = java.util.zip.ZipFile(zip).use { z -> z.entries().toList().map { it.name }.toSet() }
        assertEquals(
            setOf(
                "2026-11-01_0900_周会/transcript.json",
                "2026-11-01_0900_周会/纪要.md",
                "2026-11-02_1000_复盘/transcript.json",
            ),
            names,
        )
    }

    @Test
    fun backupOfEmptyLibraryIsAnEmptyZip() {
        val zip = File(dir("out"), "empty.zip")

        assertEquals(0, LibraryBackup.backupTo(dir("empty"), zip))
        assertTrue(zip.isFile)
    }

    @Test
    fun restoreBringsMeetingsBack() {
        val source = dir("source")
        meeting(source, "2026-11-01_0900_周会", mapOf("纪要.md" to "# 总结", "录音.wav" to "fake"))
        val zip = File(dir("out"), "library.zip")
        LibraryBackup.backupTo(source, zip)

        val fresh = dir("fresh")
        val report = LibraryBackup.restoreFrom(zip, fresh)

        assertEquals(listOf("2026-11-01_0900_周会"), report.restored)
        assertEquals("# 总结", File(fresh, "2026-11-01_0900_周会/纪要.md").readText())
        assertEquals("fake", File(fresh, "2026-11-01_0900_周会/录音.wav").readText())
    }

    @Test
    fun existingMeetingsAreNotOverwrittenByDefault() {
        val source = dir("source")
        meeting(source, "2026-11-01_0900_周会", mapOf("纪要.md" to "旧备份"))
        val zip = File(dir("out"), "library.zip")
        LibraryBackup.backupTo(source, zip)

        val target = dir("target")
        meeting(target, "2026-11-01_0900_周会", mapOf("纪要.md" to "现在更新的版本"))
        val report = LibraryBackup.restoreFrom(zip, target)

        assertEquals(listOf("2026-11-01_0900_周会"), report.skipped)
        assertEquals("现在更新的版本", File(target, "2026-11-01_0900_周会/纪要.md").readText())
    }

    @Test
    fun overwriteReplacesExistingMeeting() {
        val source = dir("source")
        meeting(source, "2026-11-01_0900_周会", mapOf("纪要.md" to "备份里的版本"))
        val zip = File(dir("out"), "library.zip")
        LibraryBackup.backupTo(source, zip)

        val target = dir("target")
        meeting(target, "2026-11-01_0900_周会", mapOf("纪要.md" to "旧版本"))
        val report = LibraryBackup.restoreFrom(zip, target, overwrite = true)

        assertEquals(listOf("2026-11-01_0900_周会"), report.restored)
        assertEquals("备份里的版本", File(target, "2026-11-01_0900_周会/纪要.md").readText())
    }

    @Test
    fun pathTraversalEntriesAreRejected() {
        val zip = File(dir("evil"), "evil.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            listOf(
                "../escaped.txt",
                "/absolute/evil.txt",
                "meeting/../../evil.txt",
                "meeting/sub/deep.txt",
                "ok/纪要.md",
            ).forEach { name ->
                out.putNextEntry(ZipEntry(name))
                out.write("x".toByteArray())
                out.closeEntry()
            }
        }

        val root = dir("target")
        val report = LibraryBackup.restoreFrom(zip, root)

        assertEquals(listOf("ok"), report.restored)
        assertTrue("四个危险条目都要被拒绝：${report.rejected}", report.rejected.size == 4)
        assertFalse("不能写到会议库外面", File(root.parentFile, "escaped.txt").exists())
        assertFalse("不能写到绝对路径", File("/absolute/evil.txt").exists())
        assertFalse("不能有多余层级", File(root, "meeting/sub/deep.txt").exists())
    }

    @Test
    fun restoreIntoMissingRootCreatesIt() {
        val source = dir("source")
        meeting(source, "2026-11-01_0900_周会", mapOf("纪要.md" to "x"))
        val zip = File(dir("out"), "library.zip")
        LibraryBackup.backupTo(source, zip)

        val root = File(dir("maybe"), "nested/library")
        val report = LibraryBackup.restoreFrom(zip, root)

        assertEquals(listOf("2026-11-01_0900_周会"), report.restored)
        assertTrue(root.isDirectory)
    }

    @Test
    fun backupRoundTripsThroughRestoreIntoAnotherLocation() {
        val source = dir("source")
        meeting(source, "2026-11-01_0900_周会", mapOf("transcript.json" to """{"a":1}""", "纪要.md" to "# 一"))
        meeting(source, "2026-11-02_1000_复盘", mapOf("纪要.md" to "# 二"))
        val zip = File(dir("out"), "library.zip")
        LibraryBackup.backupTo(source, zip)

        val restored = dir("restored")
        LibraryBackup.restoreFrom(zip, restored)

        assertEquals(
            LibraryBackup.meetings(source).map { it.name },
            LibraryBackup.meetings(restored).map { it.name },
        )
        assertEquals(
            File(source, "2026-11-01_0900_周会/transcript.json").readText(),
            File(restored, "2026-11-01_0900_周会/transcript.json").readText(),
        )
    }
}

/** 恢复要能识别"选错文件"，而不是安静地报告"恢复 0 场"。 */
class LibraryBackupBadInputTest {

    private fun dir(): File = Files.createTempDirectory("tingo-badzip").toFile()

    @Test
    fun nonZipFileIsRejected() {
        val bogus = File(dir(), "notes.zip").apply { writeText("这不是 zip") }

        val failure = runCatching { LibraryBackup.restoreFrom(bogus, dir()) }.exceptionOrNull()

        assertTrue("不该把普通文件当成空备份", failure is IllegalArgumentException)
    }

    @Test
    fun emptyFileIsRejected() {
        val empty = File(dir(), "empty.zip").apply { writeBytes(ByteArray(0)) }

        assertTrue(runCatching { LibraryBackup.restoreFrom(empty, dir()) }.isFailure)
    }

    @Test
    fun streamApiWorksForSourcesWithoutAPath() {
        // SAF 只能给流：这里模拟"没有文件路径也能备份/恢复"
        val source = dir()
        File(source, "2026-11-01_0900_周会").apply { mkdirs() }
        File(source, "2026-11-01_0900_周会/纪要.md").writeText("# 一")
        val bytes = java.io.ByteArrayOutputStream()

        LibraryBackup.backupTo(source) { bytes }
        val target = dir()
        val report = LibraryBackup.restoreFrom({ bytes.toByteArray().inputStream() }, target)

        assertEquals(listOf("2026-11-01_0900_周会"), report.restored)
    }
}
