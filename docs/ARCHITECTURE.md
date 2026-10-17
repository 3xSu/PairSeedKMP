# 架构与项目结构

> 本文说明项目的模块分层、包结构、核心链路与工程约束；README 只保留简介与启动方式。
> 快照日期：2026-10-11（阶段 3 完成时）。

## 1. 项目定位与现状

PairSeed 是"看一对角色过日子"的观察式互动应用：AI 从种子（生活化关键词）生长出事件，经查重与判定后进入清单；打开 app 时按真实时间结算——"到点了"的事件就发生、落入历史；拿不准的事件交给用户确认。

- 单机、离线优先：没有服务器，所有计算只在 app 运行时发生
- Android 优先；iOS 壳保留，暂不构建（没有 Mac 环境）
- 数据全存本地 SQLite（SQLDelight，17 表）

**现状（2026-10-11）**：阶段 1–3 完成（工程骨架与数据库 / 领域核 / AI 全链路与调试面板）；当前 UI 是调试壳，完整观察端（场景与角色渲染、事件播放、历史页）在阶段 4，尚未开工。

## 2. 架构与依赖方向

```text
androidApp ──► kmp:ui ──┐
    │                   ├──► kmp:domain（纯 Kotlin，零框架 / 平台依赖）
    └────────► kmp:data ┘
```

- **业务层 = `:kmp:domain`**：一份代码，两个平台共用；纯 Kotlin，零框架 / 平台依赖
- **框架侧 = `:kmp:data` / `:kmp:ui`**：适配器，在 kmp 内共享
- **平台壳 = `androidApp` / `iosApp`**：入口、装配、平台独占能力
- 依赖方向单向（壳 / ui / data → domain），编译层锁定；业务文件出现框架类型即违规
- 平台差异三档下放（能上一档就不去下一档）：① 参数 / 端口注入（`now` / `utcOffsetSeconds` / 格式化函数）→ ② kmp 内 `expect/actual`（如 `DatabaseDriverFactory`）→ ③ 平台壳（入口 / Keystore / 通知）
- 调试夹具 `:kmp:debug-fixtures` 只连到 `:kmp:domain` 的测试源集与 androidApp 的 debug 变体，不进 release 依赖图

## 3. 模块与包结构

### `:kmp:domain`（业务层）

| 包 | 职责 |
|---|---|
| `common` | 时段与时间：四时段枚举（边界 / 解析）、Unix 毫秒常量、本地时区换算（`minuteOfDay` / `startOfLocalDay`，时区偏移参数化） |
| `event` | 事件核：模型、五状态机、7 天过期、"打开 app 结算"、触发规则、步骤与台词、一级确认 |
| `repository` | 数据端口：Event / UserConfirmation / SemiFixedTemplate / RuntimeProfile |
| `runtime` | 运行期状态：心跳 `last_seen_at` 等 |
| `ai` | Provider 端口（预设 / 端侧 / API Key）、调用编排、结果解析、提示词注册表 |
| `seed` | 种子池：加权挑选、AI 生长、素材采集端口 |
| `dedup` | 查重链路：规范化 / 编辑距离 / 长文"同一件事"指认 |
| `judgment` | 判定链路：结构检查、事实提取、风险分 R、三支裁决、判定提示词（样例数据在 `:kmp:debug-fixtures`） |

`character` / `scene` / `action` 三个包随阶段 4（观察端）落地。

### `:kmp:data`（数据层）

- SQLDelight：17 张表，一表一个 `.sq` 文件（`src/commonMain/sqldelight/`）
- 兄弟包结构：`<业务>/` 放行映射（`*Mappers`），`<业务>_impl/` 放实现（`*Impl`）
- 现有：`event` / `runtime` / `ai` / `seed` / `grow` / `judgment`
- Provider 实现：`PresetLlmClient` / `ApiKeyLlmClient`（Ktor CIO）

### `:kmp:ui`（共享 UI）

- Compose Multiplatform，UI 写在 commonMain
- `debug/` 调试壳 + `debug/panel/` 调试面板五件 + `theme/`
- 阶段 4 补正式壳：`observe/`（主界面）/ `history/` / `settings/`

### `:kmp:debug-fixtures`（调试夹具，debug-only）

- 判定样例集数据（18 条，匿名化）；只被 domain `commonTest` 与 androidApp debug 引用，不进 release 依赖图

### `androidApp`（Android 壳）

- `MainActivity`：入口；`ShellHolder`：整条栈装配（Provider / Keystore / 仓库 / 自动链）
- `KeystoreSecretStore` / `AiSettingsStoreImpl`：密钥与设置
- debug / release source set：`DebugSeedProvider`（ENABLED 开关）、`DebugPromptOverrideStore`（debug 落本地文件 / release 空实现）

### `iosApp`

- 保留、不维护；`:kmp:ui` 与 `:kmp:data` 的 iOS target 已配置，但 `DatabaseDriverFactory` 的 iOS actual 未补

## 4. 核心链路

### 4.1 事件管线

- **五状态机 + 7 天过期**：待确认 → 在清单 → 进行中 → 已发生 / 已拒；过期逻辑删除、记录保留
- **打开 app 结算**：按真实时间补算事件是否结束、当前跑到第几步；关 app 时间照走
- **触发四优先级**：过最晚的半固定事件（睡觉 / 吃饭）→ 接续 → 窗内半固定 → 空闲超阈值（10 分钟）挑清单；整段离线窗口翻篇不补发
- **一级确认**：只问"是 / 否"；答否摘掉当前时段、摘完即拒；改答一步原子回滚；可答门 = 状态 × 未过期 × 当前时段

### 4.2 AI 管线

- **三模式 Provider**：预设 / API Key（OpenAI 兼容：base_url + model + key）已接入；端侧小模型（LiteRT-LM + Qwen3）完成可行性 spike，未接入产品
- **种子池生长**：反比加权挑选（1 / (1 + 被挑次数)）、素材采集（一言 / 知乎日报）、AI 产词、短词查重、重复反馈重试、工具轮转
- **两段式事件生成**：高温生成段（概要 / 步骤 / 台词 / 接续指认）→ 程序整理稿 → 低温打标段（两维档位 + 适合时段）；严格 schema 校验 + 按「说」步固化可空性（事实固化）
- **查重**：规范化（全角 / 空白 / 标点 / 简繁）→ 编辑距离相似度 → 长文 AI「同一件事」指认；靶集含近期已发生 + 清单 + 在流程内（待确认 / 进行中）
- **判定**：硬规则码直通拒 → 缺料 fail-safe 待确认 → 风险分 R 三段带（阈值按代价比 12 : 6 : 2 推导：≤ 15 通过 / ≥ 65 拦截）；API 路线 AI 冷读可推翻 R，调用失败降级系统裁决
- **接续**：A 生成时从清单指认"接着想做"的一件（不新生成）；A 结束立即接上，离线跨过结束则链断
- **自动补货**：打开 app 清单未满 → 串行补至满仓（上限 6）；清单满 → 长种子；空手冷却、不重入
- **调试面板**（debug 通道）：跑链路（种子 / 事件四阶段报告）、五任务提示词查看与覆盖、Provider 配置与连接测试、判定样例集一键校准

### 4.3 数据模型（17 表）

| 组 | 表 |
|---|---|
| 事件管线（4） | `events` / `user_confirmations` / `semi_fixed_event_templates` / `runtime_profiles` |
| 动作与场景（4） | `behavior_templates` / `scenes` / `scene_object_defs` / `scene_object_instances` |
| 角色（6） | `character_packs` / `characters` / `character_specs` / `character_hard_rules` / `character_target_relations` / `pair_shared_specs` |
| 配置与内容（3） | `idea_seeds` / `document_snippets` / `ooc_judgment_experience` |

约定：不建物理外键；只有需要"不参与业务但记录保留"的表才有 `is_deleted`；确认记录只插不删。

## 5. 工程约束

- **iOS 边界纪律**：端口统一 interface + `suspend` + 数据类；不跨边界用函数类型参数 / `Flow`，为 Swift 互操作预留
- **密钥安全**：API Key 只进 Android Keystore，不落库 / 日志 / git；库表只留 `api_key_configured` 镜像位
- **调试通道隔离**：调试种子 / 清空重来 / 提示词覆盖只在 debug source set；release 不携带任何预置数据
- **时间全参数化**：domain 不读系统时钟，`now` / `utcOffsetSeconds` 一律由壳层注入
- **无 DI 框架**：壳层 `ShellHolder` 手动装配整条栈

## 6. 测试与验证

- 单测 300+ 用例（截至 2026-10-11）：domain 224 / data 73 / ui 6；`debug` / `release` 双变体全绿
- API 冒烟走 `-Ppairseed.smoke=true` 门（无本地 Key 自动 skip）
- 每节点在模拟器装机验收（截图 + 缺陷复盘）；装机发现的真缺陷均已修复并复核

## 7. 路线图

| 阶段 | 内容 | 状态 |
|---|---|---|
| 1 | KMP 工程骨架 + 17 表 SQLDelight schema | ✅ |
| 2 | 领域核（事件五状态机 / 结算 / 触发 / 一级确认）+ 最小可视壳 | ✅ |
| 3 | AI 接入（三模式 Provider / 生成 / 查重 / 判定 / 调试面板 / 自动补货） | ✅ |
| 4 | 完整观察端（场景与角色渲染、事件播放、确认交互、历史页、物件互动） | 计划已出，待开工 |
| 5 | 加角色链路（捏人、两块画布）、本地通知、离线补算 | 未开工 |

## 8. 本地开发备忘

- **模拟器**：若 `ANDROID_SDK_HOME` 指向 SDK 目录本身，emulator 可能找不到 AVD（报 `Unknown AVD name`）；启动时显式指定 AVD 目录：

  ```bash
  ANDROID_AVD_HOME='<你的 AVD 目录>' \
    <Android SDK>/emulator/emulator.exe -avd <AVD 名>
  ```

- **本地配置**：仓库根目录 `.pairseed.local.json`（形状 `{baseUrl, model, apiKey, jsonFormat?, logFile?}`）仅用于本地 API 冒烟 / 调试，已在 `.gitignore` 中；测试默认不跑，`-Ppairseed.smoke=true` 放行
