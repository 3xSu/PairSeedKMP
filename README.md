# PairSeedKMP

PairSeed 的单机版（Android 优先、离线优先），用 Kotlin Multiplatform 共享业务代码。

## 项目简介

PairSeed 是"看一对角色过日子"的应用：AI 从**种子**（生活化关键词）生长出**事件**，经判定确认符合角色设定后进入清单；打开 app 时按真实时间结算——"到点了"的事件就发生、落入历史。用户看观察端与历史，拿不准的事件由用户来答（确认交互）。

- 单机、离线优先：没有服务器，一切计算只在 app 运行时发生
- AI 三模式（预设 / 端侧小模型 / API Key）随阶段推进接入
- 数据全存本地 SQLite（SQLDelight）

**现状（2026-10-04）**：工程骨架 + 数据库已落地——17 表 SQLDelight schema、建库驱动（`pairseedkmp.db`）、JVM 冒烟 3 用例全绿；领域逻辑（事件五状态机、"打开 app 结算"等）尚未接入，现在跑起来是空壳页。

## 怎么跑起来

### 环境要求

- JDK 17+（本机 21）
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

# 跑测试（不需要设备）：领域层 + 数据层冒烟
./gradlew :kmp:domain:allTests :kmp:data:testDebugUnitTest
```

### 模拟器（本机备忘）

本机 `ANDROID_SDK_HOME` 指向了 SDK 目录本身，emulator 会因此找不到 AVD（报 `Unknown AVD name`）；启动时显式指定 AVD 目录：

```bash
ANDROID_AVD_HOME='C:\Users\huang\.android\avd' \
  /d/AndroidTools/AndroidSDK/emulator/emulator.exe -avd Medium_Phone_API_34
```

## 项目结构

- `kmp/` —— 跨平台共享代码容器：
  - `:kmp:domain` 领域层：业务模型与规则（按链路分包：事件核、判定、确认……）
  - `:kmp:data` 数据层：SQLDelight（17 表已建；建库入口 `DatabaseDriverFactory`）
  - `:kmp:ui` 共享 UI：Compose Multiplatform，`App()` 写在 commonMain
- `androidApp/` —— Android 壳：只放入口与仅安卓代码，显示名 PairSeed
- `iosApp/` —— iOS 壳：保留、暂不维护（没有 Mac 构建环境）

## 技术栈

Kotlin 2.0.10 · Compose Multiplatform 1.6.11 · AGP 8.7.2 · SQLDelight 2.0.2 · Gradle 8.9（版本统一在 `gradle/libs.versions.toml`）
