# NanobotKT 当前工作交接

> 最后更新：2026-08-07（同步至 `8a83de0d648489add448f29a39292357957ea17d`）
> 项目：`C:\Users\Administrator\AndroidStudioProjects\nanobotkt`
> 官方上游：`C:\Users\Administrator\AndroidStudioProjects\nanobot-upstream`
> 上游参考提交：`02a002a0e6691cffcfedf7df4a9d298224afea9b`

## 1. 当前结论

目前已经完成以下阶段：

1. Chat 新会话、聊天页、Sidebar 和 Settings 的主要官方 UI 还原。
2. Provider 品牌 Logo、fallback 和开关接入。
3. Assistant 消息 Copy/Fork 官方行为还原。
4. 应用真实后台进程回收后的 Root 状态恢复修复与模拟器验证。

最后完成的阶段是：

> 修复并验证应用被系统真实回收进程后，当前会话、Root destination、Settings section 和 Settings 未保存表单状态的恢复。

该阶段已经完成并停止。不要重复实现，除非出现新的可复现回归。

下一阶段建议以完整 Smoke Test 为主，不要继续凭截图盲目修改 UI。

当前没有进行中的实现任务，仓库处于可直接移交状态。已完成代码已经提交并同步到 `origin/main`；本次按用户要求更新 `HANDOFF.md`，该文档修改尚未提交。

## 2. Git 与工作区状态

当前 Git 状态：

```text
branch: main
HEAD: 8a83de0d648489add448f29a39292357957ea17d
remote: origin/main = 8a83de0d648489add448f29a39292357957ea17d
working tree: M HANDOFF.md（仅本次交接文档更新）
```

在本次更新交接文档之前，`git status --short` 无输出。当前唯一工作区修改应为：`M HANDOFF.md`。源代码相对 HEAD 没有新增未提交修改。

从旧交接基线 `ee1613bacbb48b06240c854a83dd28c88f3e575d` 到当前 HEAD 的提交为：

```text
8a83de0 update
9751ca5 docs: add UI restoration handoff notes
ee094b3 perf(network): move gateway requests to IO dispatchers
ec9e31c fix(chat): add copy feedback and repair corrupted string separators
7f58ddc fix(app): preserve root UI state and session selection across restarts
81abdcb feat(settings): restore official settings UI with provider branding
```

这些提交已经位于 `origin/main`。

后续 Agent 仍需遵守：

1. 开始工作前先运行 `git status --short` 和 `git log -1 --oneline`。
2. 不执行 `git reset`、`git checkout`、`git clean` 或递归清理，除非用户明确要求。
3. 不覆盖、不回退、不批量格式化无关代码。
4. 一次只处理当前阶段涉及的文件，避免扩大修改范围。
5. 未经用户明确要求，不创建新提交、push 或 PR。
6. 不输出 bootstrap secret、Token、Provider API Key 或其他凭据。
7. 如果工作区再次出现未知未跟踪文件，先确认来源，不要擅自删除或提交。

## 3. 必须保留的既有业务行为

### 3.1 首次发送后的会话选择

关键文件：

```text
C:\Users\Administrator\AndroidStudioProjects\nanobotkt\app\src\main\java\com\nanobotkt\NanobotRoot.kt
```

不要重写首次发送后的 `SessionSelection` / `reconcileSessionSelection` 核心规则。

现有逻辑包含 drafting guard，用来防止刚创建的新会话尚未出现在 Sidebar 时，被旧会话抢占选择。

本轮只增加了一个真实恢复竞态保护：

```kotlin
if (visibleKeys.isEmpty() && selectedKey != null) {
    return SessionSelection(selectedKey = selectedKey, draftingNewTopic = false)
}
```

作用：冷启动时 `SavedStateHandle` 已恢复 selected key，但 Sidebar 第一次仍为空；此时暂时保留恢复值，等 Sidebar 加载后再验证，而不是提前回退第一项。

### 3.2 Sidebar

不要重写 Sidebar 的：

- 会话切换
- 置顶
- 取消置顶
- 归档
- 显示已归档
- 恢复
- 删除

除非先得到明确、稳定、可复现的功能 Bug。

### 3.3 官方功能边界

- 不添加假的 Android Restart 按钮。
- 官方参考提交没有 assistant Retry/Regenerate 消息操作按钮。
- 不要为了“对齐官方”新增 Retry UI。
- Android 中已有的底层能力可以保留，但新增 UI 必须先核对上游源码。

## 4. 已完成：Chat Copy/Fork 官方行为

主要文件：

```text
C:\Users\Administrator\AndroidStudioProjects\nanobotkt\feature\chat\src\main\java\com\nanobotkt\feature\chat\ChatScreen.kt
C:\Users\Administrator\AndroidStudioProjects\nanobotkt\feature\chat\src\main\res\values\strings.xml
C:\Users\Administrator\AndroidStudioProjects\nanobotkt\feature\chat\src\main\res\values-*\strings.xml
```

已实现并验证：

- 只有完成态、非空 assistant 消息显示 Copy。
- 流式 assistant 不显示 Copy/Fork。
- Copy 成功后图标变为 Check。
- 成功状态约 1.5 秒后恢复 Copy。
- Fork 只显示在符合官方 final assistant slice 规则的位置。
- Fork 不再使用本地 busy spinner。
- 所有现有 Chat locale 已增加 `Copied` 翻译。
- 未新增官方不存在的 Retry 按钮。

已通过：

```powershell
.\gradlew.bat :feature:chat:compileDebugKotlin :feature:chat:testDebugUnitTest --console=plain
```

模拟器截图：

```text
C:\Users\Administrator\AppData\Local\Temp\nanobot-copy-immediate.png
C:\Users\Administrator\AppData\Local\Temp\nanobot-copy-reset.png
```

## 5. 已完成：真实进程回收状态恢复

### 5.1 原始根因

原 Root 状态位于 `ReadyRoot` 的条件组合子树中：

```kotlin
rememberSaveable(sessionEpoch)
```

冷启动先组合 `AuthState.Booting`，再进入 `AuthState.Ready`。依赖 Ready 条件子树中的 `rememberSaveable` 恢复 Root 导航状态并不可靠。

此外还有第二个竞态：

1. `SavedStateHandle` 已恢复 selected session key。
2. 第一次组合时 Sidebar 数据仍为空。
3. 原 `reconcileSessionSelection(emptyList(), restoredKey, false)` 会清空选择。
4. Sidebar 加载后回退第一项。

### 5.2 当前实现

相关文件：

```text
C:\Users\Administrator\AndroidStudioProjects\nanobotkt\app\src\main\java\com\nanobotkt\AppViewModel.kt
C:\Users\Administrator\AndroidStudioProjects\nanobotkt\app\src\main\java\com\nanobotkt\NanobotRoot.kt
C:\Users\Administrator\AndroidStudioProjects\nanobotkt\feature\settings\src\main\java\com\nanobotkt\feature\settings\SettingsScreen.kt
```

`AppViewModel` 现在注入 `SavedStateHandle`，并维护：

```kotlin
AppDestination
RootUiState
SavedStateHandle.readRootUiState()
```

保存字段：

- selected session key
- Root destination
- drafting-new-topic guard
- Settings section

Root 的导航和选择更新统一通过 `AppViewModel` 写回 `SavedStateHandle`。

`logout()` 会先重置 Root 状态，避免下一次登录继承旧用户页面。

Settings 增加：

```kotlin
onSectionChange: (String) -> Unit = {}
```

section 切换时同步更新 Root SavedState。

### 5.3 单元测试

文件：

```text
C:\Users\Administrator\AndroidStudioProjects\nanobotkt\app\src\test\java\com\nanobotkt\RootUiStateTest.kt
C:\Users\Administrator\AndroidStudioProjects\nanobotkt\app\src\test\java\com\nanobotkt\SessionSelectionTest.kt
```

覆盖：

- 无保存值时默认 Chat。
- 恢复 session、Settings destination、draft guard 和 Image section。
- 无效 destination 回退 Chat。
- 空 Settings section 回退 Overview。
- drafting 中的新会话不会被旧 Sidebar 项抢占。
- 新会话出现后 drafting guard 正常清除。
- 恢复的 selected key 可穿过第一次空 Sidebar。
- 普通已删除 selection 在列表加载后仍回退第一项。

最终执行：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain
```

结果：

```text
BUILD SUCCESSFUL
383 actionable tasks: 4 executed, 379 up-to-date
```

`git diff --check` 无 whitespace error，只有 Windows 工作区正常的 LF → CRLF warning。

### 5.4 模拟器真实回收验证

设备：

```text
emulator-5554
320x640
font scale 1.0
package com.nanobotkt.debug
ADB reverse tcp:8765 -> tcp:8765
```

测试使用真实后台回收，而不是 `force-stop` 伪造：

```powershell
adb shell input keyevent KEYCODE_HOME
Start-Sleep -Seconds 3
adb shell am kill --user 0 com.nanobotkt.debug
adb shell am start -W -n com.nanobotkt.debug/com.nanobotkt.MainActivity
```

#### Selected session：通过

测试前选择 `UI_smoke_test`，并确认对应聊天回复已加载。

```text
PID before:     24004
PID after kill: empty
PID after start: 24151
LaunchState: COLD
```

恢复后：

- 仍为 `UI_smoke_test`。
- 仍显示其对应回复。
- 没有回退 `UI_first_send_20260807`。

证据：

```text
C:\Users\Administrator\AppData\Local\Temp\session-selected.xml
C:\Users\Administrator\AppData\Local\Temp\session-after-kill.xml
```

#### Settings Image 未保存状态：通过

测试状态：

- destination：Settings
- section：Image
- `Max images per turn` 从服务端值 `4` 临时改成未保存的 `5`
- dirty 文案：`Save changes, then restart when ready.`

```text
PID before:      24151
PID after kill:  empty
PID after start: 24390
LaunchState: COLD
```

恢复后：

- 仍是 Settings。
- section 仍是 Image。
- 滚动到底部后仍为 `5`。
- dirty 文案仍存在。

没有点击 Save，因此服务端配置仍为 `4`，测试没有写入远程配置。

证据：

```text
C:\Users\Administrator\AppData\Local\Temp\settings-image-dirty.xml
C:\Users\Administrator\AppData\Local\Temp\settings-after-kill.xml
C:\Users\Administrator\AppData\Local\Temp\settings-after-kill-bottom.xml
```

logcat 检查结果：

```text
NO_APP_CRASH_OR_ANR_FOUND
```

## 6. 已完成的其他主要 UI 范围

已经实现并做过专项验证：

- 新建会话空白页和首次发送。
- Chat 输入区、附件入口、Provider/Preset、语音和发送按钮布局。
- Sidebar 官方视觉结构。
- Settings：Overview、Appearance、Models、Image、Voice、Web、Security、System。
- REST Settings 数据加载、保存和 IO Dispatcher 调整。
- Provider 品牌映射、别名、品牌色、initials fallback。
- 官方 SVG URL 和 favicon fallback 顺序。
- `Brand logos` 偏好接入 Overview、Models、Image、Voice、Web。

Provider 主要文件：

```text
C:\Users\Administrator\AndroidStudioProjects\nanobotkt\feature\settings\src\main\java\com\nanobotkt\feature\settings\ProviderBrand.kt
C:\Users\Administrator\AndroidStudioProjects\nanobotkt\feature\settings\src\test\java\com\nanobotkt\feature\settings\ProviderBrandTest.kt
```

不要把这些“已做过专项验证”误写成“整个应用所有功能已完成测试”。

## 7. 尚未完成的任务

### 7.1 建议下一阶段：建立 Smoke Test 清单

目前没有创建 `SMOKE_TEST.md`。

建议下一位 Agent 先创建测试表，至少包含：

- 模块/页面
- 测试入口
- 前置条件
- 操作步骤
- 预期结果
- 实际结果
- Pass/Fail/Blocked
- 截图或日志路径
- 是否有外部副作用

先测试不会产生外部副作用的本地功能，每完成一个小阶段就停下汇总，不要一次扩展全部范围。

### 7.2 Chat 与会话

尚未完整端到端验证：

- 会话切换的完整数据回归
- 置顶和取消置顶
- 归档、显示已归档、恢复
- 删除
- 多种时序下的新会话首次发送
- 停止生成
- 消息编辑
- 现有重新生成底层路径（不要新增 Retry UI）
- Fork 后新会话内容和索引
- 附件上传、图片发送和失败重试

### 7.3 Settings 全 section 回归

目前进程恢复专项只验证了 Settings → Image。

其余每个 section 仍需覆盖：

- 初始加载
- dirty 状态
- Save 成功
- Save 失败
- 保存中
- section 切换
- 页面切换
- 进程回收
- Logout 后清理
- 服务端异常或无效数据

### 7.4 其他模块

尚未完整验证：

- Apps：安装、启用、禁用、卸载、真实调用
- Skills：新增、编辑、删除、运行
- Automations：创建、修改、触发、删除
- Channels：连接、消息收发、断线重连
- Security：策略保存和实际拦截
- Workspace/文件选择和文件操作

### 7.5 真实服务与凭据

尚未完整验证：

- 多 Provider 鉴权和真实模型调用
- Web Search 请求与结果
- Image Generation 请求与图片展示
- Voice 录音、上传、转写、发送
- Provider 限流和异常

测试这些功能时不得输出凭据。

### 7.6 网络和错误场景

尚未系统覆盖：

- 断网
- 超时
- WebSocket 断线和重连
- HTTP 401 / 403 / 429 / 5xx
- Gateway 不可达
- 不完整响应
- 上传或请求中途回收进程
- 重复点击和并发操作

### 7.7 设备适配

尚未验证：

- 完整设备重启后的恢复
- 横屏
- 其他分辨率和平板
- 系统字体放大
- 系统语言切换
- 后台长时间停留
- 低内存场景
- 多 Android API 版本

## 8. 推荐下一位 Agent 的执行顺序

建议严格按以下顺序：

1. 阅读本文件和仓库 `AGENTS.md`。
2. 运行 `git status --short`，确认不要覆盖现有修改。
3. 不改代码，先建立 Smoke Test 清单。
4. 第一批只测试本地会话功能：切换、置顶、归档、恢复、删除。
5. 发现问题时先记录稳定复现步骤。
6. 对照官方 WebUI 源码确认预期。
7. 做最小范围修复。
8. 运行相关模块测试和 `git diff --check`。
9. 安装 APK，在 `emulator-5554` 复测。
10. 完成一个阶段后停下并向用户汇总，再决定是否继续。

不要同时展开 Apps、Skills、Channels、Provider、Voice 和 Image 等多个外部依赖任务。

## 9. 常用命令

构建并运行 app 单测：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain
```

Chat 专项：

```powershell
.\gradlew.bat :feature:chat:compileDebugKotlin :feature:chat:testDebugUnitTest --console=plain
```

Settings 专项：

```powershell
.\gradlew.bat :feature:settings:compileDebugKotlin :feature:settings:testDebugUnitTest --console=plain
```

检查空白错误：

```powershell
git diff --check
```

安装 APK：

```powershell
adb -s emulator-5554 install -r C:\Users\Administrator\AndroidStudioProjects\nanobotkt\app\build\outputs\apk\debug\app-debug.apk
adb -s emulator-5554 reverse tcp:8765 tcp:8765
```

UI dump：

```powershell
adb -s emulator-5554 shell uiautomator dump /sdcard/window.xml
adb -s emulator-5554 pull /sdcard/window.xml C:\Users\Administrator\AppData\Local\Temp\window.xml
```

截图：

```powershell
adb -s emulator-5554 shell screencap -p /sdcard/screen.png
adb -s emulator-5554 pull /sdcard/screen.png C:\Users\Administrator\AppData\Local\Temp\screen.png
```

## 10. 当前模拟器注意事项

最后一次验证后，应用处于 Settings → Image，底部可见未保存的：

```text
Max images per turn = 5
Save changes, then restart when ready.
```

该值没有 Save，远程服务端仍是 `4`。

如果下一位 Agent 要测试其他内容，可以离开该页面；但不要误认为 `5` 已写入配置。

重新安装 APK 时使用 `adb install -r` 保留应用数据。不要在未记录状态前执行清数据操作。

## 11. Subagent 记录

本轮及前序使用的只读 Subagent：

- `Lovelace`：审计官方 Copy/Fork 行为。没有修改文件，结论已用于 Chat 实现。
- `Goodall`：被分配进程恢复根因调查；为遵守阶段结束后停止的要求，在返回结论前被关闭。没有修改文件，其结果不是当前修复的依赖。
- 更早的 `Arendt`：审计 Provider 品牌映射、Logo fallback 和接入位置。没有修改文件，结论已整合到 Provider 实现。

## 12. 最终边界说明

可以确认：

- Copy/Fork 官方行为专项完成。
- Root 进程回收恢复专项完成。
- 相关单测、构建、真实 COLD start 和 logcat 检查通过。

不能确认：

- 整个应用所有功能已经完成测试。
- 所有真实 Provider、Channels、Apps、Skills、Automations、Voice、Image、Web Search 均可正常运行。
- 所有设备尺寸、系统版本和错误场景均已覆盖。

后续工作的重点应是系统化验证、记录和最小修复，而不是扩大 UI 重写范围。
