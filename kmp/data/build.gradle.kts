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
            // SQLDelight 运行时（生成代码依赖）；`:kmp:domain` 依赖随领域逻辑接入再加
            implementation(libs.sqldelight.runtime)
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
