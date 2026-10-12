import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.pairseedkmp.android"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.pairseedkmp.android"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
    }
}

dependencies {
    implementation(projects.kmp.ui)
    implementation(projects.kmp.data)
    implementation(projects.kmp.domain)
    implementation(libs.androidx.activity.compose)
    // 壳层管 driver 生命周期（清空数据重来要关驱动 → 删库文件 → 重建）：直接用 SqlDriver 类型
    implementation(libs.sqldelight.runtime)
    // 壳层两个 store（01：Keystore / prefs 读写挪 IO 线程）
    implementation(libs.kotlinx.coroutines.core)
    // 壳层装配调试面板（07）要直接建素材 / LLM 的 HttpClient（data 里是 implementation，不外露）
    implementation(libs.ktor.client.core)
    // 节点 02 spike：端侧模型验证页（仅 debug 变体）
    debugImplementation(libs.litertlm.android)
}