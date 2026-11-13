import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// ---- Rust 引擎构建参数 ----
// ABI 默认两个：真机 arm64-v8a、模拟器 x86_64。日常只想快跑模拟器时可 `-PtingoRustAbis=arm64-v8a`。
val rustAbis: List<String> = (providers.gradleProperty("tingoRustAbis").orNull ?: "arm64-v8a,x86_64")
    .split(",")
    .map { it.trim() }
    .filter { it.isNotEmpty() }

val ffiDir = rootProject.layout.projectDirectory.dir("../engine-ffi")
val ndkHome: String = providers.environmentVariable("ANDROID_NDK_HOME").orNull
    ?: run {
        // 没设环境变量时，退回到 SDK 里已安装的最新 NDK
        val sdkDir = rootProject.file("local.properties")
            .takeIf { it.isFile }
            ?.let { file ->
                Properties().apply { file.inputStream().use { load(it) } }.getProperty("sdk.dir")
            }
            ?: error("APP_ANDROID_SDK_MISSING: 缺少 local.properties 的 sdk.dir，无法定位 NDK")
        val ndks = File(sdkDir, "ndk").listFiles()?.sortedBy { it.name } ?: emptyList()
        ndks.lastOrNull()?.absolutePath
            ?: error("APP_NDK_MISSING: $sdkDir/ndk 下没有已安装的 NDK")
    }

/** 定位 cargo：优先环境变量，其次 rustup 的常见安装位置（本机 cargo 由 Homebrew 的 rustup 提供）。 */
fun locateCargo(): String {
    val home = System.getProperty("user.home")
    val candidates = listOfNotNull(
        System.getenv("CARGO"),
        File(home, ".cargo/bin/cargo").takeIf { it.canExecute() }?.absolutePath,
        "/opt/homebrew/opt/rustup/bin/cargo".takeIf { File(it).canExecute() },
        "/usr/local/bin/cargo".takeIf { File(it).canExecute() },
    )
    return candidates.firstOrNull() ?: error("APP_CARGO_MISSING: 找不到 cargo，请设置 CARGO 环境变量")
}

val cargoBin: String = locateCargo()

/** cargo 需要 rustup 的代理在 PATH 上，Gradle 的 Exec 不继承登录 shell 的环境。 */
val cargoEnvPath: String = listOf(
    File(System.getProperty("user.home"), ".cargo/bin").absolutePath,
    "/opt/homebrew/opt/rustup/bin",
    System.getenv("PATH") ?: "",
).joinToString(":")

val uniffiOutDir = layout.buildDirectory.dir("generated/uniffi")
val cargoJniLibsDir = layout.buildDirectory.dir("cargo/jniLibs")

/**
 * 用 cargo-ndk 把 `tingo-ffi` 交叉编译成各 ABI 的 .so。
 * 只带核心 feature（--no-default-features）：Android 上不引 axum、sherpa-onnx、子进程。
 */
val cargoNdkBuild by tasks.registering(Exec::class) {
    group = "rust"
    description = "把 tingo-ffi 交叉编译为 Android 各 ABI 的 .so"

    inputs.dir(ffiDir.dir("src"))
    inputs.file(ffiDir.file("Cargo.toml"))
    inputs.property("abis", rustAbis)
    outputs.dir(cargoJniLibsDir)

    workingDir = ffiDir.asFile
    environment("ANDROID_NDK_HOME", ndkHome)
    environment("PATH", cargoEnvPath)
    commandLine(
        cargoBin, "ndk",
        *rustAbis.flatMap { listOf("-t", it) }.toTypedArray(),
        "-o", cargoJniLibsDir.get().asFile.absolutePath,
        "build", "--release", "--no-default-features",
    )
}

/** 宿主平台构建一次 ffi，仅为了拿到 uniFFI 元数据（绑定生成不依赖 ABI）。 */
val buildRustHost by tasks.registering(Exec::class) {
    group = "rust"
    description = "宿主平台构建 tingo-ffi，供 uniFFI 生成 Kotlin 绑定"

    inputs.dir(ffiDir.dir("src"))
    inputs.file(ffiDir.file("Cargo.toml"))
    outputs.file(ffiDir.file(hostLibPath()))

    workingDir = ffiDir.asFile
    environment("PATH", cargoEnvPath)
    commandLine(cargoBin, "build", "--no-default-features")
}

/** 生成 Kotlin 绑定；输出目录被加入 Kotlin 源集，不手写也不入库。 */
val generateUniffiBindings by tasks.registering(Exec::class) {
    group = "rust"
    description = "由 tingo-ffi 生成 Kotlin 绑定"

    dependsOn(buildRustHost)
    inputs.file(ffiDir.file(hostLibPath()))
    inputs.file(ffiDir.file("uniffi.toml"))
    outputs.dir(uniffiOutDir)

    workingDir = ffiDir.asFile
    environment("PATH", cargoEnvPath)
    commandLine(
        cargoBin, "run", "--bin", "uniffi-bindgen", "--",
        "generate", "--library", ffiDir.file(hostLibPath()).asFile.absolutePath,
        "--language", "kotlin",
        "--out-dir", uniffiOutDir.get().asFile.absolutePath,
        "--no-format",
    )
}

/** 宿主平台动态库文件名：macOS 是 .dylib，Linux 是 .so。 */
fun hostLibPath(): String {
    val os = System.getProperty("os.name").lowercase()
    val suffix = when {
        os.contains("mac") -> "dylib"
        os.contains("win") -> "dll"
        else -> "so"
    }
    return "target/debug/libtingo_ffi.$suffix"
}

/**
 * 设备级测试用的会议夹具直接取自引擎的测试夹具，避免仓库里存两份。
 * 输出到 `fixtures/` 子目录，测试侧照旧按 `assets("fixtures/<会议>")` 读取。
 */
val syncTestFixtures by tasks.registering(Sync::class) {
    from(rootProject.layout.projectDirectory.dir("../engine/tests/fixtures"))
    into(layout.buildDirectory.dir("generated/fixtures/assets/fixtures"))
}

android {
    namespace = "io.github.tancheng33.tingo"
    compileSdk = 37

    // 与 Rust 交叉编译用同一个 NDK，避免两套工具链打架
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "io.github.zdfb.tingo"
        minSdk = 29
        targetSdk = 37
        versionCode = 504
        versionName = "0.5.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // 与 cargo-ndk 的产物保持一致
            abiFilters += rustAbis
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // 模型是二进制大文件：压缩只会让 APK 更大、首次解包更慢
        noCompress += listOf("onnx", "mvn", "txt")
    }

    defaultConfig {
        // 把打包的 ABI 清单注入代码：AbiCheck 因此不需要自己再写一份
        buildConfigField("String", "SUPPORTED_ABIS", "\"${rustAbis.joinToString(",")}\"")
    }

    sourceSets {
        getByName("main") {
            kotlin.srcDir("src/main/kotlin")
            // Android SourceSet API 不接受 Provider，这里在配置期解析为具体目录
            kotlin.srcDir(uniffiOutDir.get().asFile)
            jniLibs.srcDir(cargoJniLibsDir.get().asFile)
        }
        getByName("test") {
            kotlin.srcDir("src/test/kotlin")
        }
        getByName("androidTest") {
            kotlin.srcDir("src/androidTest/kotlin")
            assets.srcDir(layout.buildDirectory.dir("generated/fixtures/assets").get().asFile)
        }
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// 任何构建都要先把原生库与绑定准备好，否则 :app 里的 Kotlin 找不到生成的类型。
tasks.named("preBuild") {
    dependsOn(generateUniffiBindings, cargoNdkBuild)
}

// androidTest 的 assets 在合并测试资源之前要就位
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("AndroidTestAssets") }.configureEach {
    dependsOn(syncTestFixtures)
}

dependencies {
    implementation(project(":wear-protocol"))
    implementation(libs.play.services.wearable)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.material3.window.size)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)

    // uniFFI 生成的 Kotlin 绑定通过 JNA 调用原生库；Android 上要 aar 变体
    implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")
    // 端侧识别：sherpa-onnx 的 Android AAR，含 arm64-v8a / x86_64 的 .so
    implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:${libs.versions.sherpaOnnx.get()}")

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.tooling.preview)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // JVM 单测里跑 org.json（Android 运行时自带，单测环境没有）
    testImplementation(libs.json)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
