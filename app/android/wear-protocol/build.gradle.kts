plugins {
    // AGP/Kotlin 已由 app 模块加载；这里不重复声明版本，避免 Gradle 9.6 对同一插件的版本冲突。
    id("com.android.library")
}

android {
    namespace = "io.github.zdfb.tingo.wear.protocol"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation(libs.junit)
}
