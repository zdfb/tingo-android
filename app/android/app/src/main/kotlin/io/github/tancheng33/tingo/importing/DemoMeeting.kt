package io.github.tancheng33.tingo.importing

import android.content.Context
import io.github.tancheng33.tingo.engine.TingoEnginePort
import java.io.File

/**
 * 把内置的示例会议装进会议库。
 *
 * 桌面端的示例素材打包在 App 资源里；Android 放在 assets 里，先解到缓存目录，
 * 再由引擎按同一套命名装进库（`2026-10-28_1400_示例·注册转化复盘`）。
 *
 * 示例会议是新手引导的最后一步：让用户在没有录音、没配模型之前也能看到
 * Tingo 做出来的东西长什么样（纪要、说话人、时间戳、鱼骨图）。
 */
/** 装示例会议的能力；做成接口是为了让引导流程能在 JVM 上测（真实现要读 assets）。 */
interface DemoInstaller {
    fun install(english: Boolean): String?
}

class DemoMeeting(
    private val engine: TingoEnginePort,
    private val context: Context,
    private val cacheDir: File = File(context.cacheDir, "demo"),
) : DemoInstaller {

    /**
     * @param english 界面语言是英文时装英文纪要（与桌面一致）
     * @return 会议 id；素材缺失或安装失败返回 null
     */
    override fun install(english: Boolean): String? {
        val source = runCatching { extract() }.getOrNull() ?: return null
        return runCatching { engine.installDemoMeeting(source.absolutePath, english).id }.getOrNull()
    }

    /** 把 assets/sample 下的素材解到缓存目录（每次覆盖，素材随 App 版本更新）。 */
    private fun extract(): File {
        cacheDir.deleteRecursively()
        cacheDir.mkdirs()
        val assets = context.assets.list(ASSET_DIR).orEmpty()
        require(assets.isNotEmpty()) { "找不到示例会议素材" }
        assets.forEach { name ->
            context.assets.open("$ASSET_DIR/$name").use { input ->
                File(cacheDir, name).outputStream().use { output -> input.copyTo(output) }
            }
        }
        return cacheDir
    }

    private companion object {
        /** 示例素材在 assets 里的目录名（与桌面 `src/tingo/sample` 是同一份内容） */
        const val ASSET_DIR = "sample"
    }
}
