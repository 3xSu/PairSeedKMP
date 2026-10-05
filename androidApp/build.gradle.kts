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
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation(projects.kmp.ui)
    implementation(projects.kmp.data)
    implementation(projects.kmp.domain)
    implementation(libs.androidx.activity.compose)
    // 壳层管 driver 生命周期（清空数据重来要关驱动 → 删库文件 → 重建）：直接用 SqlDriver 类型
    implementation(libs.sqldelight.runtime)
}