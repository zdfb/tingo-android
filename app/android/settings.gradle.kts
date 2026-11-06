// 仓库顺序按本机实测可用性排：dl.google.com 直连会被中间设备中断 TLS 握手，
// 因此只放在最后兜底；日常命中阿里云 google 镜像与腾讯 Maven Central 镜像。
pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public")
        maven("https://maven.aliyun.com/repository/public")
        mavenCentral()
        gradlePluginPortal()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public")
        maven("https://maven.aliyun.com/repository/public")
        mavenCentral()
        google()
    }
}

rootProject.name = "tingo-android"
include(":app")
