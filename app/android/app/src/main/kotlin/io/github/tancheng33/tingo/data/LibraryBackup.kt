package io.github.tancheng33.tingo.data

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 整个会议库的备份与恢复。
 *
 * 为什么不是"改会议库位置"：SAF 选出来的目录是 `content://` URI，只有通过
 * `ContentResolver` 才能读写；引擎是原生代码，按绝对路径 `open`，拿到 URI 无能为力。
 * 给引擎加一层存储抽象能把这件事做成，但那是一整套 IO 重写，代价远大于收益。
 *
 * 用户真正的诉求是"换台手机也能带走/找回所有会议"，这一点用备份/恢复就能满足，
 * 而且不依赖存储位置——库依旧待在应用私有目录里，引擎不需要任何改动。
 */
object LibraryBackup {

    /** 恢复结果：成功写回几场、跳过了几场、拒了几个危险条目。 */
    data class RestoreReport(
        val restored: List<String> = emptyList(),
        val skipped: List<String> = emptyList(),
        val rejected: List<String> = emptyList(),
    ) {
        val isEmpty: Boolean
            get() = restored.isEmpty() && skipped.isEmpty() && rejected.isEmpty()
    }

    /**
     * 把 [meetingsRoot] 下每场会议打进一个 zip，条目路径形如 `2026-11-01_0900_周会/录音.wav`。
     *
     * @return 打包了多少场会议
     */
    fun backupTo(meetingsRoot: File, target: File): Int =
        backupTo(meetingsRoot) { target.outputStream() }

    /**
     * 流式版本：SAF 只给 `OutputStream`，没有可用的文件路径。
     */
    fun backupTo(meetingsRoot: File, open: () -> java.io.OutputStream): Int {
        val meetings = meetings(meetingsRoot)
        ZipOutputStream(open()).use { out ->
            meetings.forEach { dir ->
                dir.walkTopDown()
                    .filter { it.isFile }
                    .forEach { file ->
                        val entryName = "${dir.name}/${file.relativeTo(dir).invariantSeparatorsPath}"
                        out.putNextEntry(ZipEntry(entryName))
                        file.inputStream().use { it.copyTo(out) }
                        out.closeEntry()
                    }
            }
            val audioTrash = File(meetingsRoot, ".audio-trash")
            audioTrash.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.forEach { dir ->
                dir.listFiles()?.filter { it.isFile }?.forEach { file ->
                    out.putNextEntry(ZipEntry(".audio-trash/${dir.name}/${file.name}"))
                    file.inputStream().use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
            val meetingTrash = File(meetingsRoot, ".trash")
            meetingTrash.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.forEach { dir ->
                dir.walkTopDown().filter { it.isFile }.forEach { file ->
                    out.putNextEntry(ZipEntry(".trash/${dir.name}/${file.relativeTo(dir).invariantSeparatorsPath}"))
                    file.inputStream().use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
        }
        return meetings.size
    }

    /**
     * 从 zip 恢复会议。
     *
     * @param overwrite true 时覆盖同名会议；false 时同名会议整场跳过（默认，避免
     *   恢复旧备份把当前更完整的录音覆盖掉）
     */
    fun restoreFrom(zip: File, meetingsRoot: File, overwrite: Boolean = false): RestoreReport =
        restoreFrom({ zip.inputStream() }, meetingsRoot, overwrite)

    /**
     * 流式版本：SAF 只给 `InputStream`。
     *
     * @param open 每次调用返回一个从头开始的 zip 输入流
     */
    fun restoreFrom(
        open: () -> java.io.InputStream,
        meetingsRoot: File,
        overwrite: Boolean = false,
    ): RestoreReport {
        meetingsRoot.mkdirs()
        val stage = File(meetingsRoot, ".restore-${java.util.UUID.randomUUID()}")
        check(stage.mkdir()) { "无法创建恢复目录" }
        try {
            val report = restoreInto(open, stage, meetingsRoot, overwrite)
            stage.listFiles().orEmpty().forEach { staged ->
                if (staged.name == ".trash" || staged.name == ".audio-trash") {
                    val targetRoot = File(meetingsRoot, staged.name)
                    check(targetRoot.mkdirs() || targetRoot.isDirectory)
                    staged.listFiles().orEmpty().forEach { item ->
                        installRestored(item, File(targetRoot, item.name), meetingsRoot, overwrite)
                    }
                } else {
                    installRestored(staged, File(meetingsRoot, staged.name), meetingsRoot, overwrite)
                }
            }
            return report
        } finally {
            stage.deleteRecursively()
        }
    }

    private fun installRestored(staged: File, target: File, root: File, overwrite: Boolean) {
        check(target.canonicalPath.startsWith(root.canonicalPath + File.separator)) { "恢复路径不安全" }
        if (target.exists()) {
            check(overwrite) { "恢复期间出现同名会议" }
            check(staged.copyRecursively(target, overwrite = true)) { "无法恢复会议" }
        } else {
            check(staged.renameTo(target)) { "无法恢复会议" }
        }
    }

    private fun restoreInto(
        open: () -> java.io.InputStream,
        meetingsRoot: File,
        destinationRoot: File,
        overwrite: Boolean,
    ): RestoreReport {
        val restored = mutableListOf<String>()
        val skipped = mutableSetOf<String>()
        val skippedTrash = mutableSetOf<String>()
        val rejected = mutableListOf<String>()

        // 恢复前就存在的会议才需要"跳过"判断；恢复过程中刚建出来的目录不算
        val existing = meetings(destinationRoot).map { it.name }.toSet()
        val handled = mutableSetOf<String>()
        val existingAudioTrash = File(destinationRoot, ".audio-trash").listFiles()
            ?.filter { it.isDirectory }?.map { it.name }?.toSet().orEmpty()
        val existingMeetingTrash = File(destinationRoot, ".trash").listFiles()
            ?.filter { it.isDirectory }?.map { it.name }?.toSet().orEmpty()

        val source = java.io.PushbackInputStream(java.io.BufferedInputStream(open()), 4)
        // 先看魔数：空会议库的备份是一个"只有目录尾记录"的合法 zip（PK\x05\x06），
        // 不能和"选错文件"混为一谈
        val magic = ByteArray(4)
        var filled = 0
        while (filled < 4) {
            val read = source.read(magic, filled, 4 - filled)
            if (read <= 0) break
            filled += read
        }
        if (filled > 0) source.unread(magic, 0, filled)
        require(filled == 4 && isZipSignature(magic)) { "不是有效的备份文件" }

        ZipInputStream(source).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                if (entry.isDirectory) continue
                val segments = entry.name.split('/', '\\').filter { it.isNotEmpty() }
                if (segments.size == 3 && segments[0] == ".audio-trash" &&
                    !entry.name.startsWith("/") && !entry.name.contains(':') &&
                    segments[1].isNotBlank() && !segments[1].startsWith(".") &&
                    segments.none { it == "." || it == ".." }
                ) {
                    val id = segments[1]
                    if (!overwrite && (id in existingAudioTrash || id in existing)) {
                        skippedTrash += id
                        continue
                    }
                    val trashRoot = File(meetingsRoot, ".audio-trash")
                    val target = File(File(trashRoot, id), segments[2])
                    if (!target.canonicalPath.startsWith(trashRoot.canonicalPath + File.separator) ||
                        !target.canonicalPath.startsWith(meetingsRoot.canonicalPath + File.separator) ||
                        !safeDestination(target, meetingsRoot, destinationRoot)) {
                        rejected += entry.name
                        continue
                    }
                    target.parentFile?.mkdirs()
                    target.outputStream().use { out -> input.copyTo(out) }
                    continue
                }
                if (segments.size >= 3 && segments[0] == ".trash" &&
                    !entry.name.startsWith("/") && !entry.name.startsWith("\\") && !entry.name.contains(':') &&
                    segments[1].isNotBlank() && !segments[1].startsWith(".") &&
                    segments.none { it == "." || it == ".." }
                ) {
                    val id = segments[1]
                    if (!overwrite && (id in existingMeetingTrash || id in existing)) {
                        skippedTrash += id
                        continue
                    }
                    val trashRoot = File(meetingsRoot, ".trash")
                    val target = segments.drop(2).fold(File(trashRoot, id)) { parent, name -> File(parent, name) }
                    if (!target.canonicalPath.startsWith(trashRoot.canonicalPath + File.separator) ||
                        !target.canonicalPath.startsWith(meetingsRoot.canonicalPath + File.separator) ||
                        !safeDestination(target, meetingsRoot, destinationRoot)) {
                        rejected += entry.name
                        continue
                    }
                    target.parentFile?.mkdirs()
                    target.outputStream().use { out -> input.copyTo(out) }
                    continue
                }
                // 恢复子目录，同时拒绝隐藏的顶层目录、绝对路径和路径穿越。
                val unsafe = segments.size < 2 || segments[0].startsWith(".") || segments.any { it == "." || it == ".." } ||
                    entry.name.startsWith("/") || entry.name.startsWith("\\") || entry.name.contains(':')
                if (unsafe) {
                    rejected += entry.name
                    continue
                }
                val meetingId = segments[0]
                val meetingDir = File(meetingsRoot, meetingId)
                if (!overwrite && meetingId in existing && meetingId !in handled) {
                    skipped += meetingId
                    continue
                }
                if (meetingId in skipped) continue
                val target = segments.drop(1).fold(meetingDir) { parent, name -> File(parent, name) }
                // 再确认一次落点真的在会议目录里（不信任任何来自文件的路径）
                if (!target.canonicalPath.startsWith(meetingDir.canonicalPath + File.separator) ||
                    !target.canonicalPath.startsWith(meetingsRoot.canonicalPath + File.separator) ||
                    !safeDestination(target, meetingsRoot, destinationRoot)) {
                    rejected += entry.name
                    continue
                }
                target.parentFile?.mkdirs()
                target.outputStream().use { out -> input.copyTo(out) }
                handled += meetingId
                restored += meetingId
            }
        }
        return RestoreReport(
            restored = restored.distinct(),
            skipped = (skipped + skippedTrash).sorted(),
            rejected = rejected,
        )
    }

    private fun safeDestination(staged: File, stageRoot: File, destinationRoot: File): Boolean {
        val destination = File(destinationRoot, staged.relativeTo(stageRoot).path)
        return destination.canonicalPath.startsWith(destinationRoot.canonicalPath + File.separator)
    }

    /** zip 的三种开头：普通条目、空归档尾记录、跨卷条目。 */
    private fun isZipSignature(magic: ByteArray): Boolean =
        magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte() &&
            (magic[2].toInt() == 3 || magic[2].toInt() == 5 || magic[2].toInt() == 7)

    /** 会议库里的会议目录（跳过隐藏目录与普通文件）。 */
    fun meetings(meetingsRoot: File): List<File> =
        meetingsRoot.listFiles { file -> file.isDirectory && !file.name.startsWith(".") }
            ?.sortedBy { it.name }
            ?: emptyList()
}
