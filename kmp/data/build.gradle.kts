import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.sqldelight)
}

// 数据层（:kmp:data）：SQLDelight schema 与数据访问。
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
            // SQLDelight 运行时（生成代码依赖）
            implementation(libs.sqldelight.runtime)
            // 领域端口与模型（依赖方向：data → domain）
            implementation(projects.kmp.domain)
            // LLM 调用（01）：Ktor + CIO 引擎（跨平台，iOS 就绪）；协程随端口 suspend 口径
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.cio)
        }
        androidMain.dependencies {
            // Android 建库驱动（步骤 4）；iOS 驱动留到做 iOS 时补
            implementation(libs.sqldelight.android.driver)
        }
        getByName("androidUnitTest").dependencies {
            // 冒烟测试（步骤 5）：JVM 版 SQLite 驱动，不用设备；JUnit4 跑 kotlin.test
            implementation(libs.sqldelight.sqlite.driver)
            implementation(libs.junit)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            // runTest 虚拟时间（预设 / API client 单测都在 suspend 面上）
            implementation(libs.kotlinx.coroutines.test)
            // MockEngine（01：API client 请求形状 / 错误映射测试）
            implementation(libs.ktor.client.mock)
        }
    }
}

android {
    namespace = "com.pairseedkmp.data"
    compileSdk = 34
    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

sqldelight {
    databases {
        // 生成 PairSeedDatabase；.sq 放 src/commonMain/sqldelight/（目录结构与 packageName 一致）
        create("PairSeedDatabase") {
            packageName.set("com.pairseedkmp.data")
        }
    }
}

// 冒烟（06 步骤 5：真实 API）的实时输出直达控制台——默认被 Gradle 收进报告、console 看不到
tasks.withType<Test>().configureEach {
    testLogging { showStandardStreams = true }
    // API 冒烟默认不跑（防全量测试误触真实网络/计费）：显式 -Ppairseed.smoke=true 才放行
    systemProperty("pairseed.smoke", providers.gradleProperty("pairseed.smoke").getOrElse("false"))
}
