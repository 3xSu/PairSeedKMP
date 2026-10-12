import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.composeMultiplatform)
}

// 共享 UI（:kmp:ui）：Compose Multiplatform，UI 写在 commonMain（2026-10-03 拍：调试壳起就共享）。
// iOS：三个 target 先留着（没有 Mac 构建环境，暂不构建）。
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
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(projects.kmp.domain)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            // 面板调用装饰器测试：suspend 调用走 runTest
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

android {
    namespace = "com.pairseedkmp.ui"
    compileSdk = 34
    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
