package io.github.tancheng33.tingo.testing

import android.content.Context
import io.github.tancheng33.tingo.models.ModelCatalog
import io.github.tancheng33.tingo.models.ModelFile
import io.github.tancheng33.tingo.models.ModelStore
import io.github.tancheng33.tingo.models.UrlFetcher
import java.io.File

/**
 * 设备级测试里下载模型的公共入口。
 *
 * 这些用例要拖 300～400 MB，跑在真实网络上：偶发一次超时不该让整条测试线变红，
 * 所以这里重试一次（下载本身支持续传，重试成本不高）。
 */
object ModelDownloads {

    fun store(context: Context, name: String): ModelStore =
        ModelStore(File(context.cacheDir, name).apply { mkdirs() }, UrlFetcher())

    fun catalog(context: Context, asset: String = "models.json"): ModelCatalog =
        ModelCatalog.parse(context.assets.open(asset).bufferedReader().readText())

    /** 确保这些文件都下好；失败会退避重试（下载支持续传，重试很便宜）。 */
    fun ensure(store: ModelStore, files: List<ModelFile>, attempts: Int = 3) {
        files.forEach { file ->
            if (store.isReady(file)) return@forEach
            var lastError: Throwable? = null
            repeat(attempts) { attempt ->
                val result = store.download(file)
                if (result.isSuccess) return@forEach
                lastError = result.exceptionOrNull()
                // 慢镜像上偶尔会卡一下；续传重试前稍等，别连着撞同一个坑
                if (attempt < attempts - 1) Thread.sleep(1_000L * (attempt + 1))
            }
            throw AssertionError("下载 ${file.id} 失败：${lastError?.message}", lastError)
        }
    }
}
