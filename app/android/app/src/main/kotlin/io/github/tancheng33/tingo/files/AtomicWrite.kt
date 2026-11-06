package io.github.tancheng33.tingo.files

import java.io.File

/**
 * 先写 `.tmp` 再改名。
 *
 * 会议目录里的 `transcript.json`、`纪要.md` 都是"要么完整要么不存在"的东西：
 * 被系统杀掉时留下半截 JSON，引擎会把它判成不可读，比没有文件更糟。
 */
fun writeAtomically(target: File, content: String) {
    target.parentFile?.mkdirs()
    val tmp = File(target.parentFile, "${target.name}.tmp")
    tmp.writeText(content)
    if (target.exists()) target.delete()
    check(tmp.renameTo(target)) { "无法写入 ${target.name}" }
}
