package io.github.tancheng33.tingo.models

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/** 下载用的最小 HTTP 抽象：测试里换成假实现，不必联网。 */
interface HttpFetcher {
    /** 从 [rangeStart] 起打开一个字节流；服务器不支持 Range 时返回码由 [statusOf] 说明。 */
    fun open(url: String, rangeStart: Long): FetchedStream

    data class FetchedStream(
        val stream: InputStream,
        /** true 表示服务器接受了 Range，可以从 [rangeStart] 续写 */
        val resumed: Boolean,
    )
}

/** 下载进度。 */
data class DownloadProgress(val id: String, val downloaded: Long, val total: Long) {
    val fraction: Float
        get() = if (total <= 0) 0f else (downloaded.toFloat() / total).coerceIn(0f, 1f)
}

/**
 * 模型仓库：按清单下载到指定目录，支持续传与校验。
 *
 * 三件事各自可测：续传（部分文件接着写）、校验（摘要/体积不符就删掉重来）、删除。
 * 网络只有 [HttpFetcher] 一个出口，所以单测不需要真的联网。
 */
class ModelStore(
    private val dir: File,
    private val fetcher: HttpFetcher,
    private val bufferSize: Int = 64 * 1024,
) {
    fun fileFor(file: ModelFile): File = File(dir, file.fileName)

    /**
     * 模型是否已就绪。
     *
     * 除了体积，还要求有 [markerFor] 里的校验记录：只看体积的话，一个同尺寸的坏文件
     * 会被当成好模型喂给推理引擎，表现为莫名其妙的识别质量下降。
     */
    fun isReady(file: ModelFile): Boolean {
        val f = fileFor(file)
        if (!f.isFile || f.length() != file.bytes) return false
        val marker = markerFor(file)
        if (!marker.isFile) return false
        val expected = file.sha256 ?: return true
        return marker.readText().trim().equals(expected, ignoreCase = true)
    }

    /** 校验通过后写下的指纹文件；改模型或删模型时一起消失。 */
    private fun markerFor(file: ModelFile): File = File(dir, "${file.fileName}.verified")

    /** 作废一个模型（校验不符或推理时崩了）：删文件与指纹，下次会重新下载。 */
    fun invalidate(file: ModelFile) {
        fileFor(file).delete()
        markerFor(file).delete()
    }

    /** 已占用的字节数（只统计清单里的文件）。 */
    fun usedBytes(catalog: ModelCatalog): Long =
        catalog.files.sumOf { fileFor(it).takeIf { f -> f.isFile }?.length() ?: 0L }

    /**
     * 下载一个文件。已存在且校验通过时直接返回。
     *
     * @param onProgress 每写入一块回调一次
     * @param verify 校验通过返回 true；默认按体积 + 摘要
     */
    fun download(
        file: ModelFile,
        onProgress: (DownloadProgress) -> Unit = {},
        verify: (File) -> Boolean = { verifyDefault(file, it) },
    ): Result<File> {
        dir.mkdirs()
        val target = fileFor(file)
        // 已经下过并且校验过才算数：只比体积会把同尺寸坏文件当成好模型
        if (isReady(file)) return Result.success(target)

        // 校验不通过或体积不对：从干净状态重来，避免在半截脏数据上叠加
        // 没下完的半截文件可以续传；"体积已经对但没有校验指纹"的文件反而是可疑的，
        // 整份重下（同尺寸坏文件正是 isReady 要挡掉的那种）
        val start = if (target.isFile) {
            val length = target.length()
            when {
                length < file.bytes -> length
                markerFor(file).isFile -> 0L
                else -> {
                    target.delete()
                    0L
                }
            }
        } else {
            0L
        }
        if (start == 0L && markerFor(file).isFile) markerFor(file).delete()
        if (start == 0L && target.isFile) target.delete()

        return try {
            val fetched = fetcher.open(file.url, start)
            val append = fetched.resumed && start > 0
            if (!append && start > 0) target.delete()

            RandomAccessFile(target, "rw").use { out ->
                if (append) out.seek(start) else out.setLength(0)
                var written = if (append) start else 0L
                val buffer = ByteArray(bufferSize)
                fetched.stream.use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        written += read
                        onProgress(DownloadProgress(file.id, written, file.bytes))
                    }
                }
            }
            if (verify(target)) {
                markerFor(file).writeText(file.sha256 ?: "size-only")
                Result.success(target)
            } else {
                target.delete()
                Result.failure(IllegalStateException("模型文件校验失败：${file.fileName}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 删除清单里的全部模型文件，返回释放的字数。 */
    fun deleteAll(catalog: ModelCatalog): Long {
        val freed = usedBytes(catalog)
        catalog.files.forEach {
            fileFor(it).delete()
            markerFor(it).delete()
        }
        return freed
    }

    private fun verifyDefault(file: ModelFile, target: File): Boolean {
        if (!target.isFile || target.length() != file.bytes) return false
        val expected = file.sha256 ?: return true
        return sha256(target).equals(expected, ignoreCase = true)
    }

    companion object {
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
