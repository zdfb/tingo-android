package io.github.tancheng33.tingo

import android.app.Application

/** 进程级入口：只持有依赖容器。 */
class TingoApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.ensureDataRoot()
    }
}
