package io.github.tancheng33.tingo.testing

import io.github.tancheng33.tingo.models.AssetModelBundle
import io.github.tancheng33.tingo.models.ModelCatalog
import io.github.tancheng33.tingo.models.ModelFile
import io.github.tancheng33.tingo.models.ModelInstaller
import io.github.tancheng33.tingo.models.ModelStore
import androidx.test.platform.app.InstrumentationRegistry

/**
 * 设备级测试拿模型的第一选择：APK 里就带着，不需要网络。
 *
 * 只有开发时没跑 `fetch-models-offline.sh`（安装包里没有模型）才会回落去下载，
 * 这样"离线也能跑端侧推理"这件事能在测试里被真实证明。
 */
object ModelBundleInstaller {

    fun installAll(store: ModelStore, files: List<ModelFile>): Boolean {
        val bundle = AssetModelBundle(InstrumentationRegistry.getInstrumentation().targetContext.assets)
        val catalog = ModelCatalog(files)
        val installed = ModelInstaller.install(store, catalog, bundle)
        return installed && catalog.files.all { store.isReady(it) }
    }
}
