import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinSerialization)
}

// 领域层（:kmp:domain）：业务模型与规则；纯 Kotlin 优先，不依赖 Android。
// iOS：三个 target 先留着（没有 Mac 构建环境，暂不构建）；framework 打包与 Xcode 接入留到做 iOS 时再补。
kotlin {
    androidTarget {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_1_8)
                }
            }
        }
    }

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // 领域层依赖从这里加
            // api：EventStep.params 是 JsonObject，属公开模型的一部分
            api(libs.kotlinx.serialization.json)
            // 端口是 suspend 函数（异步口径，2026-10-06：官方 KMP 库、非框架 / 平台依赖；iOS 就绪）
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            // runTest 虚拟时间（LlmCaller 重试 / 超时测试不用真等）
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

android {
    namespace = "com.pairseedkmp.domain"
    compileSdk = 34
    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
