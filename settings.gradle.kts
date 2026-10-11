enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
pluginManagement {
    repositories {
        google()
        // mavenCentral 提到 gradlePluginPortal 之前：Kotlin 插件与 marker 在 Central 同步发布；
        // 经 gradlePluginPortal（Cloudflare）下载在本机网络下会长时间停滞（2026-10-07 实测）
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "PairSeedKMP"
include(":androidApp")
include(":kmp:domain")
include(":kmp:data")
include(":kmp:ui")