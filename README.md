<div align="center">

# NanobotKT

**把 Nanobot 带到 Android 手机上。**

使用 Kotlin 与 Jetpack Compose 构建的 [Nanobot](https://github.com/nanobot-ai/nanobot) Android 原生客户端。<br>
连接真实 Nanobot Gateway，在移动端提供实时对话、Agent 执行过程、会话管理和能力配置。

[![Android 7.0+](https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white)](#运行要求)
[![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white)](#技术架构)
[![Dev Release](https://img.shields.io/github/v/release/yaotutu/nanobotkt?include_prereleases&label=Current%20Dev)](https://github.com/yaotutu/nanobotkt/releases/tag/dev-latest)
[![Android Dev Build](https://github.com/yaotutu/nanobotkt/actions/workflows/android-build.yml/badge.svg?branch=dev)](https://github.com/yaotutu/nanobotkt/actions/workflows/android-build.yml?query=branch%3Adev)

[下载 Dev 预览版](https://github.com/yaotutu/nanobotkt/releases/tag/dev-latest) · [直接下载 Universal APK](https://github.com/yaotutu/nanobotkt/releases/download/dev-latest/app-universal-dev.apk) · [提交问题](https://github.com/yaotutu/nanobotkt/issues) · [变更记录](docs/CHANGELOG.md)

</div>

> [!IMPORTANT]
> NanobotKT 目前处于 **Dev 开发阶段**，仅发布 `dev-latest` Dev APK。项目尚无 Stable、正式 Release 或应用商店版本，功能、接口和数据行为仍可能调整，请勿将 Dev 包用于稳定生产环境。

## 界面预览

<p align="center">
  <img src="docs/images/readme/chat-agent-activity.png" width="23%" alt="聊天页面与 Agent Activity" />
  <img src="docs/images/readme/chat-product-overview.png" width="23%" alt="富文本与结构化回答" />
  <img src="docs/images/readme/conversations.png" width="23%" alt="会话列表" />
  <img src="docs/images/readme/settings-capabilities.png" width="23%" alt="设置与能力管理" />
</p>

<p align="center">
  实时聊天与 Agent Activity · 富文本回答 · 会话管理 · 设置与能力入口
</p>

截图来自连接真实 Nanobot Gateway 的 Android 构建，不使用 Mock 数据。界面仍在持续迭代，最新版本可能与截图略有不同。

## 主要功能

- **实时聊天**：通过 Gateway HTTP API 与 WebSocket 接收流式回答、结束事件和连接状态。
- **Agent Activity**：统一展示 reasoning、工具调用、CLI/MCP 和文件修改，已完成步骤默认折叠。
- **回答控制**：支持停止响应、消息排队以及明确的发送、运行和失败状态反馈。
- **会话管理**：支持搜索、切换、置顶、归档、恢复和删除会话。
- **移动端状态恢复**：处理旋转、后台切换、进程恢复与 WebSocket 重连，尽量保留当前会话和未发送草稿。
- **能力配置**：提供 Workspaces、Apps、Skills、Automations、Channels、Security、模型与 Provider 等原生入口。
- **富文本与媒体**：支持 Markdown、代码块、图片、音频、视频、文件和文件变更预览。
- **应用内更新**：从 GitHub Release 检查并下载新 APK，安装操作仍由 Android 系统确认。
- **多语言界面**：提供英语、简体中文、繁体中文、西班牙语、法语、印尼语、日语、韩语、葡萄牙语和越南语资源。

NanobotKT 不在本地重新实现 Agent 或会话业务逻辑。Gateway 始终是业务状态的真实来源，Android 客户端只负责认证、实时传输、状态编排和原生呈现。

## 下载与安装

### 1. 准备 Gateway

NanobotKT 只是 Android 客户端，不包含 Nanobot 服务端。使用前需要：

- 一套正在运行的 Nanobot Gateway；
- Android 设备能够访问该 Gateway；
- Gateway 地址与有效的 Bootstrap Secret。

项目维护环境的默认 Gateway 入口为：

```text
http://192.168.55.201:8765/
```

这是局域网地址，并非公开服务。为其他部署环境构建客户端时，需要显式配置设备可访问的 Gateway 地址；不要把 `localhost`、`127.0.0.1`、`10.0.2.2` 或临时 `adb reverse` 映射当作真实设备的正式入口。

### 2. 选择 APK

前往 [`dev-latest`](https://github.com/yaotutu/nanobotkt/releases/tag/dev-latest) 下载文件名带有 `-dev` 的 APK：

| APK | 适用设备 |
| --- | --- |
| `app-universal-dev.apk` | 不确定设备架构时使用，推荐大多数用户下载 |
| `app-arm64-v8a-dev.apk` | 绝大多数现代 Android 手机和平板 |
| `app-armeabi-v7a-dev.apk` | 较老的 32 位 ARM 设备 |
| `app-x86_64-dev.apk` / `app-x86-dev.apk` | Android 模拟器或少数 x86 设备 |

公开 Dev APK 使用固定分发签名并保持相同应用 ID，版本升级后可以直接覆盖安装。请勿使用本地 Debug APK 覆盖公开 Dev 版，否则 Android 会因签名不同拒绝安装。

### 3. 安装并连接

1. 在 Android 系统设置中允许当前浏览器或文件管理器“安装未知应用”。
2. 安装下载的 APK。
3. 打开 NanobotKT，填写完整 Gateway 地址和 Bootstrap Secret。
4. 点击“验证并连接”。只有验证成功后，客户端才会替换当前连接配置。

> [!WARNING]
> Bootstrap Secret、Token、Cookie、Provider API Key 和会话内容均属于敏感信息。请勿将它们放入截图、Issue、日志或公开讨论。跨公网部署时，应在可信网络边界内提供 Gateway，并使用可靠的传输保护和访问控制。

## 运行要求

- Android 7.0（API 24）或更高版本；
- 能够访问 Nanobot Gateway 的网络；
- 安装 APK 的系统权限；
- 录音权限仅在使用语音输入时请求；
- “安装未知应用”权限仅用于用户主动确认的应用内更新。

当前唯一公开分发渠道是 GitHub `dev-latest`。请只从本仓库 Release 页面下载安装包，并确认文件名包含 `-dev`。

## 反馈与参与

欢迎通过 [Issues](https://github.com/yaotutu/nanobotkt/issues) 报告问题、提交兼容性结果或提出改进建议。反馈问题时，请尽量包含：

1. NanobotKT 版本、Android 版本和设备型号；
2. 问题入口、前置条件与稳定复现步骤；
3. 预期结果和实际结果；
4. 已脱敏的截图或日志。

也欢迎参与翻译、无障碍、文档和 Material 3 交互改进。修改 Gateway 协议或关键状态机前，请先明确行为契约并补充对应测试。

**请勿提交任何 Secret、Token、Cookie、Provider Key、真实聊天内容或其他个人数据。**

## 本地开发

### 环境要求

- Android Studio 最新稳定版；
- JDK 17；
- Android SDK Platform 37；
- 仓库自带的 Gradle Wrapper。

### 构建与测试

```bash
# 构建 Debug APK
sh ./gradlew :app:assembleDebug --console=plain

# App/Root 单元测试与 Debug 构建
sh ./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain

# 全模块 JVM 测试
sh ./gradlew test --no-parallel --console=plain

# Dev Lint 与构建
sh ./gradlew :app:lintDev :app:assembleDev --console=plain
```

Debug APK 输出目录：

```text
app/build/outputs/apk/debug/
```

为明确授权的部署环境构建时，可通过 Gradle 属性或环境变量传入 `NANOBOT_SERVER_URL`。不要为了模拟器便利修改仓库默认地址，也不要使用 `adb reverse` 隐藏真实网络问题。

## 技术架构

**主要技术栈**

- Kotlin、Coroutines、StateFlow
- Jetpack Compose、Material 3、Navigation Compose
- Hilt、KSP
- OkHttp HTTP/WebSocket
- Kotlinx Serialization
- AndroidX Lifecycle、SavedStateHandle
- 本地持久化与启动缓存

<details>
<summary><strong>查看模块结构与依赖方向</strong></summary>

```text
app/                     应用组合根、导航、Root 状态和 Hilt 组装
core/model/              共享数据模型与序列化模型
core/network/            Gateway HTTP/API 客户端
core/transport/          Gateway WebSocket/实时传输
core/persistence/        本地持久化
core/designsystem/       共享 Compose 设计系统
core/workspace-contract/ 跨 Feature 的 Workspace 最小能力契约
feature/auth/            登录与认证
feature/chat/            会话、消息时间线、发送与媒体预览
feature/sidebar/         会话列表及其管理入口
feature/workspaces/      Workspace 管理
feature/settings/        设置、运行状态与应用更新
feature/apps/            Apps 管理
feature/skills/          Skills 管理
feature/automations/     Automations 管理
feature/channels/        Channels 管理
feature/security/        Security 管理
```

```text
app -> feature/* -> core/*
app -> core/*
feature/* -> core/*-contract
```

</details>

## 项目文档

- [工程协作规范](AGENTS.md)
- [UI 规则](docs/UI_RULES.md)
- [专项验证记录](docs/SMOKE_TEST.md)
- [发布流程](docs/RELEASE.md)
- [变更记录](docs/CHANGELOG.md)

NanobotKT 是面向 Nanobot Gateway 的独立 Android 客户端。Nanobot 的服务端能力、协议和 WebUI 仍由对应 Nanobot 项目定义；本项目只专注 Android 端体验与集成。

<details>
<summary><strong>为什么不用 WebUI 或 PWA，而是重新开发 Android App？</strong></summary>

官方 Nanobot WebUI 是本项目的产品、交互和数据语义基线，桌面端体验也已经十分完整。NanobotKT 选择原生 Android，并不是要分叉 Nanobot 或重复实现服务端能力，而是为了解决手机端长期存在的几个实际问题：

- 桌面信息架构直接压缩到竖屏后，侧栏、会话列表和设置面板很难同时兼顾信息密度、触控尺寸与浏览效率；
- 聊天页面对输入法、滚动位置、流式消息跟随和前后台切换十分敏感，WebView/PWA 难以稳定达到原生体验；
- Android 对 PWA 的后台运行、通知、文件与媒体权限、独立窗口和厂商 ROM 兼容性支持并不一致；
- 长连接在浏览器进程被系统回收后缺少可控的恢复路径，而原生客户端可以围绕 Android 生命周期明确管理连接、重连和状态恢复。

因此，NanobotKT 使用 Compose 为竖屏与触控重新组织界面，使用 Android 生命周期与 `SavedStateHandle` 管理恢复路径，并通过原生网络层维护 HTTP、WebSocket、取消和重连状态。Gateway 仍然是唯一真实来源，客户端不会复制 Nanobot 的 Agent 逻辑。

目标很简单：让同一套 Nanobot 能力在 Android 手机上更自然、更可靠。

</details>
