package io.github.tancheng33.tingo.models

import android.content.res.AssetManager
import java.io.File
import java.io.InputStream

/**
 * 内置在 APK 里的模型包。
 *
 * 应用把模型随 APK 一起分发（`assets/models/`），安装后首次启动时解包到自己的模型目录：
 * 这样端侧推理完全不需要网络，也不会因为镜像波动而装不上模型。
 */
interface ModelBundle {
    /** 包里有没有这个文件。 */
    fun has(name: String): Boolean

    /** 打开包内文件；不存在时返回 null。 */
    fun open(name: String): InputStream?

    /** 包内文件大小；未知时返回 -1。 */
    fun sizeOf(name: String): Long
}

/** 真正实现：读 APK 的 assets。 */
class AssetModelBundle(private val assets: AssetManager) : ModelBundle {

    override fun has(name: String): Boolean = open(name)?.use { true } ?: false

    override fun open(name: String): InputStream? =
        runCatching { assets.open("models/$name") }.getOrNull()

    override fun sizeOf(name: String): Long = runCatching {
        assets.openFd("models/$name").use { it.length }
    }.getOrDefault(-1L)
}

/**
 * 把模型包里的文件解包到 [ModelStore] 的目录。
 *
 * 逐块拷贝，边写边报进度：700 MB 的模型在低端机上要几十秒，界面需要知道进展。
 * 已经校验过的文件直接跳过，所以中断后重进会接着装（不会从零开始）。
 */
object ModelInstaller {

    /**
     * @param onProgress 0..1
     * @return 解包结束时清单里是否全部就绪
     */
    fun install(
        store: ModelStore,
        catalog: ModelCatalog,
        bundle: ModelBundle,
        onProgress: (Float) -> Unit = {},
    ): Boolean {
        val targets = catalog.files.filter { bundle.has(it.fileName) }
        if (targets.isEmpty()) return catalog.files.all { store.isReady(it) }

        val total = targets.sumOf { file -> bundle.sizeOf(file.fileName).coerceAtLeast(0) }
            .coerceAtLeast(1)
        var done = 0L

        for (file in targets) {
            if (store.isReady(file)) {
                done += bundle.sizeOf(file.fileName).coerceAtLeast(0)
                onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                continue
            }
            val target = store.fileFor(file)
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, "${target.name}.installing")
            val copied = runCatching {
                bundle.open(file.fileName)?.use { input ->
                    temp.outputStream().use { out -> input.copyTo(out, bufferSize = 256 * 1024) }
                } ?: 0L
            }.getOrElse {
                temp.delete()
                return false
            }
            if (copied <= 0L || temp.length() != file.bytes) {
                temp.delete()
                return false
            }
            // 解包结果必须和清单一致：宁可失败，也不要留一个坏模型给推理引擎
            if (!temp.renameTo(target)) {
                temp.delete()
                return false
            }
            store.markVerified(file)
            done += file.bytes
            onProgress((done.toFloat() / total).coerceIn(0f, 1f))
        }
        return catalog.files.all { store.isReady(it) }
    }
}
