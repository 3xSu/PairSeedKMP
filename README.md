# PairSeedKMP

PairSeed 的单机版（Android 优先、离线优先），用 Kotlin Multiplatform 共享业务代码。

## 项目简介

PairSeed 是"看一对角色过日子"的观察式互动应用：AI 从**种子**（生活化关键词）生长出**事件**，经查重与判定后进入清单；打开 app 时按真实时间结算——"到点了"的事件就发生、落入历史。拿不准的事件交给用户确认。

- 单机、离线优先：没有服务器，所有计算只在 app 运行时发生
- AI 三模式 Provider：预设 / 端侧小模型 / API Key（OpenAI 兼容）
- 数据全存本地 SQLite（SQLDelight，17 表）

## 怎么跑起来

### 环境要求

- JDK 17+
- Android SDK——根目录 `local.properties` 里 `sdk.dir` 指向本机 SDK 路径（Android Studio 打开时一般会自动生成；命令行构建前确认它存在）
- Gradle 用仓库自带的 wrapper，不用另装；首次构建需联网拉依赖
- 运行设备：Android 7.0（API 24）及以上，真机 / 模拟器均可

### 方式一：Android Studio（推荐）

1. `File → Open`，选本仓库根目录，等 Gradle 同步完成
2. 运行配置选 `androidApp`，选好设备，点 Run

### 方式二：命令行

> Git Bash / macOS / Linux 用 `./gradlew`；Windows cmd / PowerShell 换成 `gradlew.bat`。

```bash
# 构建 debug APK（产物在 androidApp/build/outputs/apk/debug/）
./gradlew :androidApp:assembleDebug

# 设备/模拟器已连上时，一步安装
./gradlew :androidApp:installDebug

# 跑测试（不需要设备）
./gradlew :kmp:domain:allTests :kmp:ui:allTests :kmp:data:testDebugUnitTest
```

## 文档

- [架构与项目结构](docs/ARCHITECTURE.md)——模块分层、包结构、核心链路、数据模型、测试与路线图

## 技术栈

Kotlin 2.4.20 · Compose Multiplatform 1.6.11 · AGP 8.7.2 · SQLDelight 2.0.2 · Ktor 3.0.3 · kotlinx-coroutines 1.9.0 · kotlinx-serialization 1.7.1 · Gradle 8.9（版本统一在 `gradle/libs.versions.toml`）
