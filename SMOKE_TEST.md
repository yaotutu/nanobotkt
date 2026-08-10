# NanobotKT Smoke Test 记录

> 本文档记录当前阶段的可复现 Smoke Test，不代表整个应用已经完成全量测试。
>
> 最近执行时间：2026-08-08

## 测试环境

- Android 设备：`HT7390201404`
- 应用包：`com.nanobotkt.debug`
- 服务端：`http://192.168.55.147:8765/`
- 构建方式：

  ```text
  bash ./gradlew :app:assembleDebug -PNANOBOT_SERVER_URL=http://192.168.55.147:8765 --console=plain
  ```

- 安装方式：`adb install -r app/build/outputs/apk/debug/app-debug.apk`
- 认证：使用用户提供的网关密码完成验证；密码不写入仓库、截图或日志。

## 第一批：本地会话功能

| 编号     | 测试入口                | 操作步骤                                                                                             | 预期结果                                                                   | 实际结果                                                                                                    | 状态     | 外部副作用                         |
| -------- | ----------------------- | ---------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------- | -------- | ---------------------------------- |
| CHAT-001 | 应用启动                | 重新安装 Debug APK，强制停止并重新启动应用，等待首页加载                                             | 能连接网关并进入聊天页；不显示认证失败、网关不可达、Fatal Exception 或 ANR | 远程 `/webui/bootstrap` 返回 HTTP 200；应用启动后进入聊天页并显示已有会话内容；最近启动日志未发现 Fatal/ANR | **PASS** | 无配置写入；仅重启本地应用进程     |
| CHAT-002 | Chat 顶部导航 / Sidebar | 打开会话列表，选择 `新闻频道`                                                                        | Sidebar 关闭，聊天页加载所选会话的消息内容                                 | 会话切换成功，加载了 `新闻频道` 对应的新闻技能讨论内容                                                      | **PASS** | 无消息发送；仅改变本地当前选择     |
| CHAT-003 | 会话操作菜单            | 对 `Codex` 执行“置顶”，确认其进入置顶区域；再次打开菜单执行“取消置顶”                                | 菜单文案从“置顶”变为“取消置顶”；取消后恢复普通会话列表                     | 两次菜单文案和列表位置均符合预期；取消置顶后已恢复普通列表                                                  | **PASS** | 服务端置顶状态短暂改变，随后已恢复 |
| CHAT-004 | 会话操作菜单 / 已归档   | 对 `新闻频道` 执行“归档”；打开“显示已归档”；在“已归档”区域找到该会话并执行“取消归档”；关闭已归档显示 | 归档后从普通列表移除；开启后出现在“已归档”区域；取消归档后恢复普通列表     | 归档后普通列表不再显示；开启显示后在“已归档”区域找到 `新闻频道`，菜单显示“取消归档”；取消后已恢复           | **PASS** | 服务端归档状态短暂改变，随后已恢复 |

## 证据与产物

以下截图和 UI dump 是本次验证过程中的临时文件，未加入仓库：

- `/tmp/nanobotkt-remote.png`：切换到远程服务构建后，应用启动成功的聊天页截图
- `/tmp/nanobotkt-remote-ui.xml`：远程服务构建后的 UI dump
- `/tmp/nanobotkt-pin-top.xml`：置顶后的 Codex 位于置顶列表的 UI dump
- `/tmp/nanobotkt-codex-menu.xml`：取消置顶菜单文案的 UI dump
- `/tmp/nanobotkt-archived-visible.xml`：已归档区域包含新闻频道的 UI dump
- `/tmp/nanobotkt-unarchive-menu.xml`：取消归档菜单文案的 UI dump
- `/tmp/nanobotkt-final-ui.xml`：恢复并关闭 Sidebar 后的 UI dump

## 当前结论

- 第一批“会话切换、置顶/取消置顶、归档/显示已归档/取消归档”已完成一次真实服务端 Smoke Test。
- 本轮没有发送新消息，没有删除会话，没有保存 Settings，没有修改 Provider、Automation、Channel 或其他远程配置。
- 当前仅证明上述路径在本设备、当前服务端数据和当前构建下可用；不代表 Chat、Settings 或整个应用的全量回归已经完成。

## 尚未覆盖

- 新会话首次发送、多种时序下的 session selection
- 删除、重命名、Fork、停止生成、编辑消息、附件上传
- Settings 各 section 的成功/失败/保存中/进程回收
- Apps、Skills、Automations、Channels、Security、Workspace
- 401/403/429/5xx、断网、超时、WebSocket 断线重连
- 横屏、字体放大、完整设备重启、低内存及其他 Android 版本

## 本轮继续验证（2026-08-07）

### 工程验证

| 项目                  | 命令/操作                                                                                                 | 结果                                                |
| --------------------- | --------------------------------------------------------------------------------------------------------- | --------------------------------------------------- |
| Sidebar 并发刷新单测  | `bash ./gradlew :feature:sidebar:testDebugUnitTest :feature:sidebar:compileDebugKotlin --console=plain`   | **PASS**；验证较新的 refresh 响应不会被较旧响应覆盖 |
| App 单测与 Debug 构建 | `bash ./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain`                                | **PASS**                                            |
| Chat 编译与单测       | `bash ./gradlew :feature:chat:compileDebugKotlin :feature:chat:testDebugUnitTest --console=plain`         | **PASS**                                            |
| Settings 编译与单测   | `bash ./gradlew :feature:settings:compileDebugKotlin :feature:settings:testDebugUnitTest --console=plain` | **PASS**                                            |
| Sidebar 编译与单测    | `bash ./gradlew :feature:sidebar:testDebugUnitTest :feature:sidebar:compileDebugKotlin --console=plain`   | **PASS**                                            |
| 空白检查              | `git diff --check`                                                                                        | **PASS**                                            |

### 本轮设备复测

- 使用远程服务地址重新构建并安装 Debug APK：**PASS**。
- 安装命令使用 `adb install -r`，保留了设备上的应用数据。
- 强制停止并启动 `com.nanobotkt.debug` 后进入聊天页，能够显示远程已有会话内容：**PASS**。
- 启动后最近日志未发现应用 Fatal Exception 或 ANR：**PASS**。
- 截图和 UI dump：`/tmp/nanobotkt-after-install.png`、`/tmp/nanobotkt-window.xml`。

### 本轮代码变更对应的验证

- Settings API Service 启动参数会优先保留当前有效配置：已通过 Settings 单测。
- Voice 配置缺失时显示不可用状态，不再伪造可编辑默认表单：已通过 Settings 编译验证；真实页面路径未单独复测。
- Image/Voice provider 列表匹配当前 provider 的 fallback 修复：已通过 Settings 编译验证；真实页面路径未单独复测。
- Sidebar refresh 乱序保护：已通过 MockWebServer 并发刷新单测。

### 外部副作用

- 仅重新安装并启动本地 Debug APK；未清除应用数据。
- 本轮没有发送消息、删除会话、保存 Settings、修改 Provider、Automation、Channel 或其他远程配置。


## 本轮继续检查（2026-08-07，浏览器只读对照与全量工程验证）

### 浏览器只读对照

通过已登录的浏览器会话访问 `http://192.168.55.147:8765/`，未执行安装、保存、删除、运行、审批/拒绝或其他远程写操作：

- **应用**：页面显示 `1 个可用`，MiniMax 为“应用已就绪”；其余应用显示“安装应用”，未点击安装。
- **技能**：页面显示 `16 个可用 · 共 18 个`，可用/不可用状态与后端返回一致；未执行技能安装或写操作。
- **自动任务**：页面显示 `2` 个系统任务，`0` 个运行中、`0` 个已暂停、`0` 个异常；未执行 Run、编辑或删除。
- **设置 → 渠道**：页面显示 `2 个运行中 · 共 16 个渠道`，飞书和 WebSocket 开启；未打开保存表单或修改配置。
- **设置 → 安全**：能够读取 WebUI 安全页、默认权限和“本机服务”状态；保存按钮为禁用状态，未修改权限。
- **设置 → 概览**：能够读取模型、网页搜索、图片生成、语音识别、网关和默认工作区状态；未点击检查更新或修改配置。

该部分只证明网页端当前已登录状态下的只读展示与页面入口可达，不等同于 Android 设备端登录后端到端验证。

### 全量工程验证

执行：

```bash
bash ./gradlew \
  :app:testDebugUnitTest \
  :app:assembleDebug \
  :feature:auth:testDebugUnitTest \
  :feature:auth:compileDebugKotlin \
  :feature:apps:testDebugUnitTest \
  :feature:apps:compileDebugKotlin \
  :feature:skills:testDebugUnitTest \
  :feature:skills:compileDebugKotlin \
  :feature:workspaces:testDebugUnitTest \
  :feature:workspaces:compileDebugKotlin \
  :feature:automations:testDebugUnitTest \
  :feature:automations:compileDebugKotlin \
  :feature:channels:testDebugUnitTest \
  :feature:channels:compileDebugKotlin \
  :feature:security:testDebugUnitTest \
  :feature:security:compileDebugKotlin \
  :feature:chat:testDebugUnitTest \
  :feature:chat:compileDebugKotlin \
  :feature:settings:testDebugUnitTest \
  :feature:settings:compileDebugKotlin \
  :feature:sidebar:testDebugUnitTest \
  :feature:sidebar:compileDebugKotlin \
  --console=plain
```

结果：**BUILD SUCCESSFUL**；随后执行 `git diff --check`，结果：**PASS**。

补充说明：`feature:automations`、`feature:channels`、`feature:security` 已补齐专项测试源文件；本次全量任务均执行成功，不再是 `NO-SOURCE`。

## 当前未完成与边界

- Workspaces Android 页面在当前构建的 Sidebar/Settings UI 中没有可达入口；已完成该模块编译与单测，但没有伪造设备页面 Smoke Test 结果。
- Provider 真实调用、Voice/Image/Web Search 端到端、网络异常、多设备适配，以及 Apps 安装/调用、Skills 写操作、Automation 写操作、Channel 配置写操作、Pairing 审批/拒绝仍未覆盖。
- 当前设备已重新登录并停留在聊天页；没有保存任何新的远程配置。

## 本轮补充验证（2026-08-07，Android 登录后路径与登出回归）

### 设备认证与页面入口

使用已构建的 `com.nanobotkt.debug` 连接到远程服务后，输入用户提供的引导密钥并成功进入聊天页。随后在 Android 设备端执行只读页面检查：

| 页面                | 实际结果                                                                                                   | 状态     | 证据                                      |
| ------------------- | ---------------------------------------------------------------------------------------------------------- | -------- | ----------------------------------------- |
| Apps                | 显示 `CLI apps (1)`，包含 Blender、FreeCAD、MeerK40t 等应用卡片；未点击 Install                            | **PASS** | `/tmp/nanobotkt-ui-apps.xml`              |
| Skills              | 显示 `cli-app-minimax`、`codex-subagent`、`llm-pricing` 等可用技能；未执行安装或写操作                     | **PASS** | `/tmp/nanobotkt-ui-skills.xml`            |
| Automations         | 显示 `heartbeat`、`dream`，状态均为 `every · ok`；未执行 Run/编辑/删除                                     | **PASS** | `/tmp/nanobotkt-ui-automations.xml`       |
| Security & pairing  | 显示 `No pending pairing requests`；未执行 approve/deny                                                    | **PASS** | `/tmp/nanobotkt-ui-security.xml`          |
| Settings → Channels | 显示 DingTalk、Discord、Email、nanobot、Matrix、Mattermost、MoChat、Microsoft Teams 等渠道状态；未修改配置 | **PASS** | `/tmp/nanobotkt-ui-channels.xml`          |
| Settings → Security | 显示 Web safety、Local Service Access、Default Permission 和 Log out；未修改权限                           | **PASS** | `/tmp/nanobotkt-ui-settings-security.xml` |

### Logout → 重新登录

- 在 Settings → Security 点击 `Log out` 后，设备回到“连接 nanobot”认证页；证据：`/tmp/nanobotkt-ui-after-logout.xml`。
- 再次输入引导密钥并连接，设备恢复到原有聊天页和会话内容；证据：`/tmp/nanobotkt-ui-after-relogin.xml`、`/tmp/nanobotkt-after-relogin.png`。
- 重新登录后的最近 logcat 未发现 `FATAL EXCEPTION`、ANR、401/403 或网络解析异常。
- 该回归产生了本地认证状态清理/重建和应用导航状态变化；没有保存远程配置、安装应用、运行/删除 Automation、修改 Channel 或执行 Pairing 审批。

## 本轮最终收尾（2026-08-07）

### 代码修复

Chat Composer 的技能目录请求已从不存在的 `/api/skills` 修正为服务端实际注册的只读路由 `/api/webui/skills`，文件为：

```text
/Users/yaotutu/Desktop/code/nanobotkt/feature/chat/src/main/java/com/nanobotkt/feature/chat/ChatRepository.kt
```

未认证直接 curl 该路由得到 `401`，符合网关需要认证的行为；没有把未认证请求误记为业务成功，也没有在日志中记录任何凭据。

### 工程验证

| 范围                          | 命令/结果                                                                                                                                                                      |
| ----------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Chat                          | `:feature:chat:testDebugUnitTest :feature:chat:compileDebugKotlin` — **PASS**                                                                                                  |
| Sidebar / Skills / Workspaces | 三个模块单测 — **PASS**；新增测试覆盖 refresh/mutation 的失败、取消和状态清理                                                                                                  |
| 全量工程                      | App、Core、Auth、Apps、Skills、Workspaces、Automations、Channels、Security、Chat、Settings、Sidebar 单测及 `:app:assembleDebug` — **BUILD SUCCESSFUL**（576 actionable tasks） |
| 空白检查                      | `git diff --check` — **PASS**                                                                                                                                                  |

### 最新 Android 设备 Smoke Test

设备：`HT7390201404`，包名：`com.nanobotkt.debug`。

1. 使用 `-PNANOBOT_SERVER_URL=http://192.168.55.147:8765` 构建 Debug APK。
2. 使用 `adb install -r` 安装，保留现有应用数据。
3. 冷启动应用，`LaunchState: COLD`，成功进入聊天页。
4. 通过导航菜单只读打开 Apps、Skills、Automations、Security & pairing。
5. 页面均能加载预期数据；最近 logcat 未发现应用 Fatal Exception 或 ANR。

最新证据文件：

```text
/tmp/nanobotkt-ui-apps-fresh.xml
/tmp/nanobotkt-ui-skills-fresh.xml
/tmp/nanobotkt-ui-automations-fresh.xml
/tmp/nanobotkt-ui-security-fresh.xml
/tmp/nanobotkt-after-install.xml
```

本次未执行 Apps Install、Automation Run/Delete、Pairing Approve/Deny、Channel 配置、Settings Save、发送新消息或真实 Provider 调用。

### 交接结论

当前阶段可以停止：已完成最小真实 Bug 修复、测试补齐、全量构建/单测和低风险 Android Smoke Test。后续如继续，应从未覆盖边界中选择一个小范围开始，不要把当前结果描述为整个应用已完成全量回归。工作区仍有既有修改和未跟踪测试/文档文件，本轮没有 stage、commit、push 或 PR。

## 本轮最终补充（2026-08-07，Settings patch 与重装后只读复测）

### Settings API patch 修复

服务端 Web Search、Image Generation、Transcription 更新接口按字段 patch。Android `SettingsRepository` 已改为仅发送显式提供的字段，避免只切换一个设置时用默认值覆盖服务端其他配置。对应回归测试位于：

```text
/Users/yaotutu/Desktop/code/nanobotkt/feature/settings/src/test/java/com/nanobotkt/feature/settings/SettingsRepositoryTest.kt
```

### 工程验证

重新执行：

```text
bash ./gradlew :app:testDebugUnitTest :app:assembleDebug :core:network:testDebugUnitTest \
  :feature:auth:testDebugUnitTest :feature:apps:testDebugUnitTest \
  :feature:skills:testDebugUnitTest :feature:workspaces:testDebugUnitTest \
  :feature:automations:testDebugUnitTest :feature:channels:testDebugUnitTest \
  :feature:security:testDebugUnitTest :feature:chat:testDebugUnitTest \
  :feature:settings:testDebugUnitTest :feature:sidebar:testDebugUnitTest --console=plain

git diff --check
```

结果：`BUILD SUCCESSFUL`（`576 actionable tasks`），`git diff --check` **PASS**。

### Android 低风险复测

设备：`HT7390201404`，包名：`com.nanobotkt.debug`。

- 使用 `adb install -r` 安装本轮 APK，保留应用数据；冷启动后聊天页正常显示。
- Apps：CLI/MCP 标签页可切换，列表和 Refresh 可用；未点击 Install/Enable。
- Skills：列表可加载，Refresh 可用；未执行安装或其他写操作。
- Automations：All/Active 状态筛选可用，`dream` 与 `heartbeat` 显示正常；未执行 Run/编辑/删除。
- 最近 logcat 未发现应用 `FATAL EXCEPTION` 或 ANR。

### 外部副作用与边界

本轮没有保存 Settings、调用真实 Provider、安装应用、启用 MCP、运行/删除 Automation、修改 Channel 或执行 Pairing 审批。Workspaces 仍无普通用户可达入口，因此没有伪造该页面的设备验证结果。没有 stage、commit、push 或 PR。

## 2026-08-08 最终收尾：Chat 修改后的复验

### 验证范围

上一轮 Chat 代码追加了完整会话身份保护（同时校验 `sessionKey` 与 `chatId`）。本次没有继续扩大功能范围，仅重新执行最终工程验证、安装最终 APK 并做低风险冷启动复测。

### 工程验证

```text
bash ./gradlew :app:testDebugUnitTest :app:assembleDebug :core:network:testDebugUnitTest \
  :feature:auth:testDebugUnitTest :feature:apps:testDebugUnitTest \
  :feature:skills:testDebugUnitTest :feature:workspaces:testDebugUnitTest \
  :feature:automations:testDebugUnitTest :feature:channels:testDebugUnitTest \
  :feature:security:testDebugUnitTest :feature:chat:testDebugUnitTest \
  :feature:settings:testDebugUnitTest :feature:sidebar:testDebugUnitTest --console=plain

git diff --check
```

结果：`BUILD SUCCESSFUL`（`576 actionable tasks`），`git diff --check` **PASS**。

### 设备复测

- 设备：`HT7390201404`
- 包名：`com.nanobotkt.debug`
- 使用远程服务地址构建并通过 `adb install -r` 安装最终 APK，保留应用数据。
- 强制停止并冷启动后，当前 Activity 为 `com.nanobotkt.debug/com.nanobotkt.MainActivity`。
- UI dump 能看到聊天输入框 `Type your message...`，说明已进入聊天页。
- 证据文件：`/tmp/nanobotkt-ui-final.xml`、`/tmp/nanobotkt-logcat-final.txt`。
- 最近 500 行 logcat 未发现应用 `FATAL EXCEPTION` 或 `ANR`。

### 副作用与未覆盖范围

本次没有执行 Settings Save、真实 Provider 调用、发送新消息、Apps 安装/启用、Automation Run/Delete、Channel 配置或 Pairing 审批。Workspaces 仍无普通用户可达入口；网络异常、多设备适配和各模块写操作仍未完成全覆盖。本轮未 stage、commit、push 或创建 PR。

## 2026-08-08 Logout/session reset 收尾复验

### 工程与回归验证

- SettingsRepository 编译通过：`:feature:settings:compileDebugKotlin`
- Settings 单测通过：`:feature:settings:testDebugUnitTest`
- 全量单测与 Debug 构建通过：`576 actionable tasks`
- `git diff --check` 通过
- 新增 Settings 回归覆盖：reset 期间 mutation 迟到响应不会恢复 payload、error 或 pending

### 设备验证

- 设备：`HT7390201404`
- 包名：`com.nanobotkt.debug`
- 以 `http://192.168.55.147:8765` 构建并通过 `adb install -r` 安装，保留应用数据
- 强制停止后冷启动成功，前台 Activity 为 `com.nanobotkt.debug/com.nanobotkt.MainActivity`
- UI dump 可见 `Type your message...`
- 证据：`/tmp/nanobotkt-ui-final.xml`、`/tmp/nanobotkt-logcat-final.txt`
- 未发现应用 `FATAL EXCEPTION` 或 `ANR in`

### 未覆盖与副作用

未执行 Settings Save、Provider 真实调用、Apps 安装/启用、Automation Run/Delete、Channel 配置、Pairing 审批或发送真实消息；Workspaces 无普通用户可达入口。未执行 stage、commit、push 或 PR；没有新的远程配置写入。

## 2026-08-08 Channels reset 与最终收尾复测

### 代码与回归验证

本轮完成 Channels Singleton Repository 的 Logout/reset 收尾：

- `ChannelsRepository` 新增 `reset()` 和 session generation，reset 后旧频道请求不能恢复 payload、连接、validation、error、loading 或 pending。
- `inFlight` 按 session 绑定，旧请求 `finally` 不会误清理新 session 的同名请求。
- `AppViewModel.logout()` 现在会调用 `channelsRepository.reset()`。
- 新增 `ChannelsRepositoryTest.resetIgnoresLateMutationResponseAndClearsState`。
- Apps、Skills、Automations、Security、Workspaces 同步补齐 reset 后迟到响应回归测试；测试均使用 MockWebServer。

最终工程验证：

```text
bash ./gradlew :app:testDebugUnitTest :app:assembleDebug :core:network:testDebugUnitTest \
  :feature:auth:testDebugUnitTest :feature:apps:testDebugUnitTest \
  :feature:skills:testDebugUnitTest :feature:workspaces:testDebugUnitTest \
  :feature:automations:testDebugUnitTest :feature:channels:testDebugUnitTest \
  :feature:security:testDebugUnitTest :feature:chat:testDebugUnitTest \
  :feature:settings:testDebugUnitTest :feature:sidebar:testDebugUnitTest --console=plain

git diff --check
```

结果：`BUILD SUCCESSFUL`（`576 actionable tasks`），`git diff --check` **PASS**。Channels、Apps、Skills、Automations、Security、Workspaces 的专项单测也分别通过。

### 设备 Smoke Test

设备：`HT7390201404`；包名：`com.nanobotkt.debug`。

```text
bash ./gradlew -PNANOBOT_SERVER_URL=http://192.168.55.147:8765 :app:assembleDebug --console=plain
adb -s HT7390201404 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s HT7390201404 shell am force-stop com.nanobotkt.debug
adb -s HT7390201404 shell monkey -p com.nanobotkt.debug 1
```

结果：

- 安装成功并保留应用数据。
- 冷启动后前台 Activity 为 `com.nanobotkt.debug/com.nanobotkt.MainActivity`。
- UI dump 可见聊天输入框 `Type your message...`。
- `/tmp/nanobotkt-ui-final.xml` 已保存 UI dump；`/tmp/nanobotkt-logcat-final.txt` 已保存本次 logcat。
- 未发现应用 `FATAL EXCEPTION`、`ANR in` 或 `Process: com.nanobotkt.debug`。

### 外部副作用与未覆盖范围

没有执行真实远程写操作：Settings Save、Provider 调用、发送消息、Apps 安装/启用、Skills 写操作、Automation Run/Delete、Channel 配置、Pairing 审批均未执行。Workspaces 没有普通用户可达入口；网络异常、多设备适配和全部写操作仍未完成全覆盖。

没有 stage、commit、push 或 PR。仅替换了本地设备 APK，未清理或删除应用数据。

## 2026-08-08 Settings 与入口页面只读 Smoke Test

### SET-001：Settings section 只读导航

**前置条件**：设备 `HT7390201404` 已安装以当前服务地址构建的 Debug APK，并已进入 Chat。

**步骤**：

1. 从 Sidebar 进入 Settings。
2. 打开 section picker。
3. 依次访问 Appearance、Models、Image、Voice、Web、Channels、System、Security。
4. 从 Security 返回 Chat。

**预期**：每个 Settings section 能加载，不崩溃；返回 Chat 后仍能显示 Composer。

**实际**：8 个 section 均成功显示首屏内容；Models 显示 provider/model，Image、Voice、Web 显示配置摘要，Channels 显示 Open Channels，System 显示 Gateway/Workspace/API service，Security 显示 pairing 状态；返回 Chat 后 `com.nanobotkt.debug/com.nanobotkt.MainActivity` 正常，UI dump 可见 `Type your message...`。

**状态**：**PASS**

**外部副作用**：无。未点击 Save、Open、Start、Stop 或 Log out。

### ENTRY-001：入口页面只读导航

**步骤**：从 Chat Sidebar 依次打开 Apps、Skills、Automations、Security & pairing，等待页面加载后返回 Chat。

**预期**：页面能打开并显示服务端只读数据，不发生崩溃。

**实际**：Apps、Skills、Automations、Security & pairing 均成功加载；未执行 Install、Enable、Run、Delete、Approve、Deny 或 Save。

**状态**：**PASS**

**外部副作用**：无远程写入，仅读取页面数据。

**证据**：

- `/tmp/nanobotkt-settings-ui.xml`
- `/tmp/nanobotkt-settings-menu-ui.xml`
- `/tmp/nanobotkt-settings-Appearance.xml`
- `/tmp/nanobotkt-settings-Models.xml`
- `/tmp/nanobotkt-settings-Image.xml`
- `/tmp/nanobotkt-settings-Voice.xml`
- `/tmp/nanobotkt-settings-Web.xml`
- `/tmp/nanobotkt-settings-Channels.xml`
- `/tmp/nanobotkt-settings-System.xml`
- `/tmp/nanobotkt-settings-Security.xml`
- `/tmp/nanobotkt-settings-back-chat.xml`
- `/tmp/nanobotkt-settings-smoke-logcat.txt`
- `/tmp/nanobotkt-page-Apps.xml`
- `/tmp/nanobotkt-page-Skills.xml`
- `/tmp/nanobotkt-page-Automations.xml`
- `/tmp/nanobotkt-page-Security.xml`

### 当前结论

本次只读验证扩大了 Settings 和入口页面的覆盖范围，但仍不代表所有功能全量验证。Settings 保存、Channels 连接、Apps/Skills/Automations 写操作、真实 Provider 调用、网络错误和设备适配仍未覆盖。

## 2026-08-08 最终收尾复核

### BUILD-001：全量工程验证

**结果**：**PASS**

已通过 `app`、`core:network`、`core:transport` 以及 `feature:auth/apps/skills/workspaces/automations/channels/security/chat/settings/sidebar` 的 Debug 单元测试；同时通过 `:app:assembleDebug`。本轮输出为 `BUILD SUCCESSFUL`。

### DEVICE-002：按目标服务地址构建并冷启动

**前置条件**：设备 `HT7390201404`，包名 `com.nanobotkt.debug`。

**步骤**：

1. 使用 `-PNANOBOT_SERVER_URL=http://192.168.55.147:8765` 构建 Debug APK。
2. 使用 `adb install -r` 安装，保留应用数据。
3. `am force-stop` 后使用 `monkey` 冷启动。
4. 导出 UI dump 和受限 logcat 摘要。

**实际**：前台 Activity 为 `com.nanobotkt.debug/com.nanobotkt.MainActivity`；UI dump 可见 `Type your message...`；未发现应用 `FATAL EXCEPTION`、`ANR in` 或 `Process: com.nanobotkt.debug`。

**状态**：**PASS**

**证据**：

- `/tmp/nanobotkt-ui-final.xml`
- `/tmp/nanobotkt-logcat-final.txt`

**外部副作用**：仅替换本地设备 APK，未清理应用数据；没有执行服务端配置写入、真实 Provider 调用或真实消息发送。


## 2026-08-08 跨会话隔离专项测试与最终复核

### TEST-CHAT-SESSION-GUARD：旧会话 guard 不得污染新会话

**步骤**：在 MockWebServer 上打开会话 A，捕获 A 的 `ChatSessionGuard`；切换到会话 B 并等待其 attach；再使用 A 的 guard 发送。

**预期**：抛出 `IllegalStateException("session_changed")`；B 不出现旧 prompt、sending turn 或 active turn；服务端不收到错误的 `message` frame。

**实际**：断言全部通过。

**状态**：**PASS**

### TEST-SETTINGS-RESET：reset 后忽略迟到 refresh

**步骤**：阻塞 `/api/settings` refresh 请求，在进入 loading 后执行 `repository.reset()`，再释放旧响应。

**预期**：旧响应不能恢复 payload、loading、error 或 pending 状态。

**实际**：断言全部通过。

**状态**：**PASS**

### 回归验证

定向 Settings/Chat 测试以及 app、core、feature 全量 Debug 单元测试和 `:app:assembleDebug` 均通过；`git diff --check` 通过。

**当前边界**：上述测试是本地 MockWebServer/单元测试，不等同于真实 Provider、真实消息发送或所有远程写操作的端到端验证。

## 2026-08-08 目标服务地址 APK 复测

使用 `-PNANOBOT_SERVER_URL=http://192.168.55.147:8765` 重新构建并通过 `adb install -r` 安装到 `HT7390201404`。冷启动后前台 Activity 为 `com.nanobotkt.debug/com.nanobotkt.MainActivity`；首次 UI dump 遇到 Android UI bridge 空根节点，等待后重试成功，UI dump 可见 `Type your message...`。最近 logcat 未发现应用 `FATAL EXCEPTION`、`ANR in` 或进程崩溃。

**状态**：**PASS**

**证据**：

- `/tmp/nanobotkt-apk-build-20260808.txt`
- `/tmp/nanobotkt-ui-20260808-retry.xml`
- `/tmp/nanobotkt-logcat-20260808.txt`

**外部副作用**：仅替换本地设备 APK，保留应用数据；没有对远程服务执行写操作。

## 2026-08-08 Logout 编排与最新 APK 复验

### LOGOUT-001：AppViewModel logout 清理顺序

**验证方式**：运行 `AppViewModelLogoutTest`，使用无 Android 依赖的编排测试记录 Root、9 个 Feature Repository、Transport attachments、Transport close 和认证 logout 的调用顺序。

**预期**：旧账号相关 UI、Repository 状态和 Transport 状态先同步失效，认证仓库的异步 logout 最后开始。

**实际**：所有清理调用均发生且顺序正确；认证 logout 不会早于同步清理。

**状态**：**PASS**

**命令**：

```text
sh ./gradlew :app:testDebugUnitTest --tests com.nanobotkt.AppViewModelLogoutTest --console=plain
```

### BUILD-002：全量工程回归与目标服务地址 APK

**实际**：`app`、`core:network`、`core:transport` 和全部 feature Debug 单元测试通过，`:app:assembleDebug` 通过；随后使用 `-PNANOBOT_SERVER_URL=http://192.168.55.147:8765` 重新构建 APK，并以 `adb install -r` 安装到 `HT7390201404`。

**状态**：**PASS**

### DEVICE-003：最新 APK 冷启动

**步骤**：强制停止 `com.nanobotkt.debug`，通过 `monkey` 冷启动，等待后导出 Activity、UI dump 和最近 1500 行 logcat。

**实际**：前台 Activity 为 `com.nanobotkt.debug/com.nanobotkt.MainActivity`；UI dump 可见 `Type your message...`；未发现应用 `FATAL EXCEPTION`、`ANR in`、进程崩溃或 `Fatal signal`。首次 UI dump 因 Android UI bridge 未生成文件，等待 5 秒后重试成功。

**状态**：**PASS**

**证据**：

- `/tmp/nanobotkt-activities-logout-final.txt`
- `/tmp/nanobotkt-ui-logout-final.xml`
- `/tmp/nanobotkt-uiautomator-logout-final-retry.txt`
- `/tmp/nanobotkt-logcat-logout-final.txt`

**外部副作用**：仅替换本地设备 APK，未清理应用数据；没有执行远程服务写操作、真实 Provider 调用或真实消息发送。

### 当前结论

本轮收尾任务已完成：专项 logout 测试、全量工程验证、目标服务器 APK 构建、设备安装及冷启动 Smoke Test 均为 PASS。剩余未覆盖项仍包括 Settings Save、真实 Provider/消息调用、Apps/Skills/Automations/Channels/Pairing 写操作、网络异常、WebSocket 断线重连、Voice/Image/Web Search 真实端到端和多设备适配；这些不能标记为已完成。

## 2026-08-08 本轮收尾复核（当前实时结果）

### BUILD-003：网络/功能专项与全量回归

**结果**：**PASS**

已通过 `core:network`、`core:transport`、`feature:auth/apps/automations/channels/security` 专项单元测试，以及 `app`、全部已接入 feature 的 Debug 单元测试和 `:app:assembleDebug`；`git diff --check` 通过。全量命令报告 `593 actionable tasks`。

本轮新增覆盖包括 Transport 重连任务清理、HTTP 403/429/5xx、Apps refresh 部分失败、Automations action 编码与取消清理、Channels poll/cancel 并发与 malformed payload、Security 轮询生命周期。

### DEVICE-004：设备状态

**结果**：**NOT RUN**

本轮复核时执行 `adb devices`，没有在线设备，因此未重新安装 APK、未冷启动、未导出新的 UI dump 或 logcat。此前 `HT7390201404` 的 APK 冷启动结果保留在本文件更早的 `DEVICE-002` / `DEVICE-003` 记录中，属于历史验证。

### 外部副作用与边界

本轮没有对远程 Nanobot 服务执行写操作；没有保存 Settings、发送消息、调用真实 Provider、执行 Apps/Automation/Channels/Pairing 写操作。Skills 写操作和 Workspaces 文件操作因当前后端未发现对应 API，未新增猜测性 UI。

## 2026-08-08 当前实时复核（最新）

### SERVER-002：目标服务只读检查

- `GET /health`：HTTP `200`。
- `GET /webui/bootstrap`：HTTP `401`，该入口需要认证；本次没有猜测、输出或记录凭据，也没有执行远程写操作。

**状态**：**PASS（健康检查）/ AUTH REQUIRED（bootstrap）**

### DEVICE-005：目标地址 APK 冷启动

使用 `-PNANOBOT_SERVER_URL=http://192.168.55.147:8765` 执行 `:app:assembleDebug`，结果为 `BUILD SUCCESSFUL`。实际 APK 输出为 `app/build/outputs/apk/debug/app-universal-debug.apk`，随后对在线设备 `HT7390201404` 执行 `adb install -r`，未清理应用数据。

冷启动 `com.nanobotkt.debug` 后：

- 前台 Activity 为 `com.nanobotkt.debug/com.nanobotkt.MainActivity`；
- UI dump 成功，当前显示“连接 nanobot”连接页；
- 最近 1800 行 logcat 未发现 `FATAL EXCEPTION`、`ANR in` 或应用进程崩溃；
- 未输入凭据，未点击连接、保存、发送或其他会触发远程写操作的控件。

**状态**：**PASS（安装与冷启动）**

**边界**：本次没有进入登录后的聊天页；文档中更早的聊天页证据属于历史验证，不能与本次连接页状态混称。

**外部副作用**：仅替换本地设备 APK，保留应用数据；没有执行远程服务写操作。

## 2026-08-08 本轮最终收尾复核

### TEST-CHAT-ATTACH-004：附件并发编码与会话隔离

**验证方式**：运行 `ChatViewModelTest`，覆盖附件编码并发计数、切换会话/创建新主题后的迟到成功结果和迟到错误结果。

**实际**：`ChatViewModelTest` 共 38 个测试通过；旧会话附件结果不会写入新 Composer，并发批次会持续占用附件名额直到对应编码任务结束。

**状态**：**PASS**

### TEST-CHAT-WS-005：ChatRepository WebSocket 事件隔离与 TurnEnd 收敛

**验证方式**：使用本地 MockWebServer WebSocket，发送其他 chat 的 Delta、TurnEnd、SessionUpdated 和 Error，以及当前 chat 的 Delta/TurnEnd。

**实际**：其他 chat 事件不会污染当前会话；当前 chat 的 optimistic turn 能收到 Delta，并在 TurnEnd 后清理 active/sending 状态；迟到旧 chat 事件不会重新打开 turn 或写入错误。该测试是本地 Mock 验证，不是真实服务端 E2E。

**状态**：**PASS**

### TEST-SIDEBAR-RESET-006：reset 后隔离迟到 mutation/delete

**验证方式**：使用 MockWebServer 阻塞 mutation/delete 响应，在请求发出后执行 `SidebarRepository.reset()`，再释放迟到响应。

**实际**：迟到 success/error 不会恢复旧状态、写入错误、清除新 pending 状态，也不会触发后续 refresh；Sidebar 定向测试 6 个通过。

**状态**：**PASS**

### BUILD-004：最终工程回归

已执行并通过：

```text
bash ./gradlew :feature:chat:compileDebugKotlin :feature:chat:testDebugUnitTest :feature:sidebar:compileDebugKotlin :feature:sidebar:testDebugUnitTest --console=plain
bash ./gradlew testDebugUnitTest --console=plain
bash ./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain
git diff --check
```

全工程 `testDebugUnitTest` 报告 `BUILD SUCCESSFUL`，执行图为 `583 actionable tasks`；App 单测与 Debug APK 构建报告 `BUILD SUCCESSFUL`，执行图为 `383 actionable tasks`。

**状态**：**PASS**

### DEVICE-005：目标服务器地址 APK 冷启动

使用以下命令构建并安装：

```text
bash ./gradlew :app:assembleDebug -PNANOBOT_SERVER_URL=http://192.168.55.147:8765 --console=plain
adb -s HT7390201404 install -r app/build/outputs/apk/debug/app-universal-debug.apk
```

**实际**：目标地址 APK 构建成功，`adb install -r` 成功；强制停止并通过 `monkey` 冷启动后，前台 Activity 为 `com.nanobotkt.debug/com.nanobotkt.MainActivity`，UI dump 成功并可见聊天输入框 `Type your message...`。最近 800 行 logcat 未发现应用 `FATAL EXCEPTION`、`ANR in` 或崩溃。

**状态**：**PASS**

**外部副作用**：仅在本地设备上增量替换 APK、强制停止并启动应用；没有清理数据，没有输入认证信息，没有发送消息，没有保存设置，也没有对远程服务执行写操作。

### 最终边界

本轮可以确认：附件并发/跨会话隔离、Chat WebSocket 事件隔离、Sidebar reset 迟到响应隔离、全工程单元测试、App Debug 构建、目标地址 APK 安装和冷启动均已验证。仍不能宣称整个应用已经完成真实登录后的全功能端到端测试；Settings Save、真实 Provider/模型调用、真实消息发送、Apps/Automation/Channels/Pairing 写操作、完整 WebSocket 自动重连、异常网络、多设备适配等仍未覆盖或仅有本地 Mock 验证。

## 2026-08-08 剩余边界测试与只读 Settings 导航复核

### TEST-NETWORK-007：Gateway / Bootstrap 异常语义

新增并通过 `core:network` 测试，覆盖：

- 普通 `IOException` 映射为 `GatewayException.Network`；
- 协程取消保持取消语义，不误判为 Timeout/Network；
- Bootstrap 缺少必需字段和 malformed JSON 映射为 `InvalidPayload`；
- 空 Bootstrap 凭据映射为 `AuthenticationRequired`；
- 缺失 `ws_url` 时使用 `baseUrl + ws_path` fallback。

命令：

```text
bash ./gradlew :core:network:testDebugUnitTest --console=plain
```

结果：`BUILD SUCCESSFUL`，25 个测试通过，0 failures，0 errors。

### TEST-TRANSPORT-008：WebSocket 边界与转写

新增并通过 `core:transport` 测试，覆盖：

- 已发送消息断线后的 `DeliveryUnknown`；
- close code `1009` 的 `MessageTooBig`；
- 网络断开期间取消重连；
- `TurnRejected` / `WorkspaceScopeRejected`；
- transcription 成功、wire format、服务端失败、取消、超时和迟到响应隔离。

命令：

```text
bash ./gradlew :core:transport:testDebugUnitTest --console=plain
```

结果：`BUILD SUCCESSFUL`，25 个测试通过，0 failures，0 errors；专项 AcceptanceTest 连续 3 次均通过。

### TEST-SETTINGS-009：Settings 高价值接口合同

新增并通过 SettingsRepository 测试，覆盖：

- OAuth logout 后状态清理和 refresh；
- version check 成功字段映射；
- Image / Transcription 全字段 query 映射；
- API service start/stop 失败的 error 和 pending 清理；
- mutation 后 reset 对迟到 refresh 的隔离。

命令：

```text
bash ./gradlew :feature:settings:testDebugUnitTest --console=plain
```

结果：`BUILD SUCCESSFUL`，120 个 actionable tasks，测试通过。

### BUILD-005：新增边界测试后的全工程回归

实际执行并通过：

```text
bash ./gradlew :core:network:testDebugUnitTest :core:transport:testDebugUnitTest :feature:settings:testDebugUnitTest --console=plain
bash ./gradlew testDebugUnitTest --console=plain
bash ./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain
bash ./gradlew :app:assembleDebug -PNANOBOT_SERVER_URL=http://192.168.55.147:8765 --console=plain
bash ./gradlew :core:network:testDebugUnitTest --console=plain
bash ./gradlew :core:transport:testDebugUnitTest --console=plain
bash ./gradlew :feature:settings:testDebugUnitTest --console=plain
```

结果：全部 `BUILD SUCCESSFUL`；全工程 Debug 单测执行图为 `583 actionable tasks`，App 单测加 APK 构建执行图为 `383 actionable tasks`，目标地址 APK 构建执行图为 `366 actionable tasks`；`git diff --check` 通过。

### SERVER-003：目标服务只读健康检查

```text
GET http://192.168.55.147:8765/
```

结果：HTTP `200`，`text/html; charset=utf-8`。只读检查未使用或记录凭据。

### DEVICE-006：目标地址 APK 安装、冷启动和 Settings 只读导航

前置条件：设备 `HT7390201404` 在线，包名 `com.nanobotkt.debug`。

步骤：

1. 使用目标服务地址构建并确认 `BuildConfig.NANOBOT_SERVER_URL` 为目标地址。
2. 执行 `adb install -r app/build/outputs/apk/debug/app-universal-debug.apk`，保留应用数据。
3. 强制停止并通过 `monkey` 冷启动。
4. 导航打开 Sidebar，进入 Settings Overview。
5. 只读打开 Settings section 菜单，依次进入 Appearance、Models、Image、Voice、Web、Channels、System、Security；不点击 Save、开关或任何写操作控件。

实际结果：

- 前台 Activity 为 `com.nanobotkt.debug/com.nanobotkt.MainActivity`；
- 聊天页 UI dump 可见消息输入框和 `Send message`；
- Settings Overview 可见 `Token Usage`、`Current model`、`Web search`、`Image generation`、`Voice input`；
- 9 个 Settings section 均可打开并显示对应标题和内容；
- 最近 logcat 未发现应用 `FATAL EXCEPTION`、`ANR in` 或进程崩溃，应用 PID 仍存在；
- 未输入认证信息，未发送消息，未保存 Settings，未修改 Provider、Apps、Automations、Channels、Pairing 或其他远程配置。

状态：**PASS（只读导航）**

证据文件均保留在设备 `/sdcard` 或临时目录，未加入仓库：

- `/sdcard/nanobotkt-ui.xml`
- `/sdcard/nanobotkt-nav.xml`
- `/sdcard/nanobotkt-settings.xml`
- `/sdcard/nanobotkt-current.xml`

外部副作用：仅本地设备 APK 增量安装、强制停止、冷启动和页面导航；没有远程写操作。

### 当前边界

上述补充测试和只读导航完成了本阶段可在本地 Mock、单元测试及当前设备上安全验证的范围，但仍不等同于完整真实 E2E。Settings Save、真实 Provider/模型调用、真实消息发送、Apps/Automation/Channels/Pairing 写操作、异常网络下的真实服务重连、多设备和横竖屏适配仍未覆盖。

## 2026-08-08 真实登录 E2E 与副作用收尾（本轮新增）

### E2E-REAL-001：真实登录、Chat 消息发送与接收

前置条件：目标服务 `http://192.168.55.147:8765`，设备 `HT7390201404`，Debug 包 `com.nanobotkt.debug`。

步骤与结果：

1. 在应用登录页完成 bootstrap 认证：**PASS**。
2. 创建新主题：**PASS**。
3. 发送测试消息并等待 assistant 完成响应：**PASS**。
4. 复核消息中出现的 `%20/%5C`：确认来自 `adb shell input text` 的 shell 转义污染，不是产品 Bug；本轮不修改生产代码。
5. 删除本轮测试会话并重新 GET `/api/sessions`：目标测试会话不存在，**PASS**。

### E2E-REAL-002：Settings Web Save、恢复与服务端确认

步骤与结果：

1. 进入 Settings → Web，读取 Max results 原值 `10`：**PASS**。
2. 改为 `9` 并点击 Save，页面显示成功状态：**PASS**。
3. 将 Max results 恢复为 `10`；同时将恢复过程中误触变为 `61 s` 的 Timeout 校正回 `60 s`，再次点击 Save：**PASS**。
4. 认证 API 最终确认 `web_search.max_results = 10`、`web_search.timeout = 60`：**PASS**。

### SERVER-REAL-002：认证只读接口复核

以下接口均返回 HTTP `200`：`/api/sessions`、`/api/workspaces`、`/api/commands`、`/api/webui/skills`、`/api/webui/automations`、`/api/settings`、`/api/settings/api-service`、`/api/settings/nanobot-features`、`/api/settings/cli-apps`、`/api/settings/mcp-presets`、`/api/settings/pairing`。

状态：**PASS**

### BUILD-REAL-001：目标地址构建、全工程测试与设备冷启动

实际命令：

```text
bash ./gradlew :app:testDebugUnitTest :app:assembleDebug -PNANOBOT_SERVER_URL=http://192.168.55.147:8765 --console=plain
bash ./gradlew test --console=plain
git diff --check
adb -s HT7390201404 install -r app/build/outputs/apk/debug/app-universal-debug.apk
```

结果：

- `:app:testDebugUnitTest :app:assembleDebug`：**BUILD SUCCESSFUL**。
- 全工程 `test`：**BUILD SUCCESSFUL**。
- `git diff --check`：通过。
- `adb install -r`：成功，保留应用数据。
- 冷启动前台 Activity：`com.nanobotkt.debug/com.nanobotkt.MainActivity`。
- UI dump 可见聊天输入框与 `Send message`。
- 最近 logcat 未发现 `FATAL EXCEPTION`、`ANR in` 或应用进程崩溃。

### 当前结论与未覆盖范围

本轮已经完成真实登录、真实 Chat 消息收发、真实 Settings Save/恢复、测试会话清理、目标地址构建安装和冷启动验证；但仍不是所有功能的全量验收。真实 Provider/Voice/Image/Web Search 业务调用、Apps/Automations/Channels/Pairing 写操作、异常网络与完整 WebSocket 自动重连、横竖屏/多设备/低内存适配仍未全部覆盖。未执行 stage、commit、push 或 PR。

## 2026-08-08 当前轮复核（最新）

### BUILD-REAL-002：目标地址构建与全工程回归复测

```text
bash ./gradlew :app:testDebugUnitTest :app:assembleDebug -PNANOBOT_SERVER_URL=http://192.168.55.147:8765 --console=plain
bash ./gradlew test --console=plain
git diff --check
```

结果：全部通过；目标地址 App 单测与 Debug APK 构建 `BUILD SUCCESSFUL`；全工程 `test` 为 `BUILD SUCCESSFUL`（583 actionable tasks）；`git diff --check` 通过。

### DEVICE-REAL-002：通用 APK 增量安装与冷启动

```text
adb -s HT7390201404 install -r app/build/outputs/apk/debug/app-universal-debug.apk
adb -s HT7390201404 shell am force-stop com.nanobotkt.debug
adb -s HT7390201404 shell monkey -p com.nanobotkt.debug 1
```

结果：安装成功且保留应用数据；前台 Activity 为 `com.nanobotkt.debug/com.nanobotkt.MainActivity`；应用进程存活；最近 logcat 未发现 `FATAL EXCEPTION`、`ANR in` 或应用崩溃。

### 当前结论

本轮没有新增远程写操作。Automation 创建受限于服务端要求必须在真实 Chat session 中调用内置 `cron` tool，HTTP action 路径没有安全的普通 create 接口；Apps、Channels、Pairing 需要真实凭据/请求/外部资源；Skills 与 Workspaces 未发现普通用户可达写 API。上述项均记录为未覆盖或受条件阻塞，不能伪造为 PASS。

## 2026-08-08 Transport 修复后的最终回归

### 修复范围

- WebSocket `ready` 只作为握手事件，不再被当作 `new_chat` / `fork_chat` 响应。
- 使用 `attached` 完成新会话与分叉会话请求。
- `goal_status` 优先按 `turn_id` 匹配；无 `turn_id` 时拒绝在多个 pending message 之间任意猜测。
- CI Debug / Release 流程新增全工程 JVM 单测。

### 验证

```text
bash ./gradlew :feature:chat:testDebugUnitTest --tests 'com.nanobotkt.feature.chat.DefaultChatRepositoryTest' --console=plain
bash ./gradlew test --console=plain
bash ./gradlew :app:testDebugUnitTest :app:assembleDebug -PNANOBOT_SERVER_URL=http://192.168.55.147:8765 --console=plain
git diff --check
adb -s HT7390201404 install -r app/build/outputs/apk/debug/app-universal-debug.apk
adb -s HT7390201404 shell am force-stop com.nanobotkt.debug
adb -s HT7390201404 shell am start -n com.nanobotkt.debug/com.nanobotkt.MainActivity
adb -s HT7390201404 shell uiautomator dump /sdcard/window.xml
```

结果：以上命令均通过；APK 已保留数据增量安装，冷启动前台 Activity 为 `com.nanobotkt.debug/com.nanobotkt.MainActivity`，UI dump 成功，最近 logcat 未发现应用 Fatal Exception 或 ANR。

### 结论与限制

本阶段验证通过的是协议修复、全工程 JVM 回归、目标地址构建、设备冷启动和此前已完成的低风险真实 E2E；不代表所有远程功能已经全量验收。Provider/Voice/Image/Web Search 真实业务调用、Apps/Automations/Channels/Pairing 写操作、完整异常网络重连及多设备/横竖屏/低内存适配仍未覆盖。

## 2026-08-08 当前接手轮复核（最新）

### 模块回归

以下 Android 模块编译和 JVM 单测均通过：Apps、Automations、Channels、Security、Settings。

### 全工程回归

目标地址 App 单测与 Debug APK 构建通过；服务端高价值回归 `228 passed`；`git diff --check` 通过。

全工程 `bash ./gradlew test --console=plain` 当前仍有 1 个失败：

- `feature/chat` 的 `DefaultChatRepositoryTest.websocket events are isolated by chat and current turn converges after turn end`
- 失败断言位于 `DefaultChatRepositoryTest.kt:460`
- 断言发生在 `turn_end` 后立即检查消息列表，乐观用户消息尚未等到延迟 canonical refresh 清理

因此本轮结论为：**模块级回归通过，全工程回归未通过，不能标记为全部完成。**

## 2026-08-08 当前接手轮收尾复核（最新）

### TEST-REGRESSION-003：Chat 与全工程回归

修复了 Chat 测试对异步 canonical refresh 和 MockWebServer 请求顺序的脆弱依赖，并补齐测试 fake 的 `WorkspacesRepository.updateDefaultAccessMode` 接口；同时修正 Automations `duration_ms` 的 `Long` 类型断言。

验证命令：

```text
bash ./gradlew :feature:chat:testDebugUnitTest --tests 'com.nanobotkt.feature.chat.DefaultChatRepositoryTest' --console=plain
bash ./gradlew test --console=plain
uv run pytest -q
git diff --check
```

结果：

- Chat 定向测试：**PASS**。
- Android 全工程测试：**PASS**，`583 actionable tasks`。
- Nanobot 服务端测试：**PASS**，`5461 passed, 17 skipped`。
- `git diff --check`：**PASS**。

本轮未执行新的真实远程写操作，也未重新安装 APK；此前记录的目标服务地址 APK 安装、设备冷启动、真实 Chat 收发和 Settings Save/恢复验证仍作为历史证据保留。

## 2026-08-08 当前轮最终设备复测（最新）

### BUILD-REAL-003：目标地址构建与回归

```text
bash ./gradlew :app:testDebugUnitTest :app:assembleDebug -PNANOBOT_SERVER_URL=http://192.168.55.147:8765 --console=plain
bash ./gradlew test --console=plain
uv run pytest -q                         # /Users/yaotutu/Desktop/code/nanobot
 git diff --check
```

结果：目标地址 App 单测与 Debug APK 构建通过；Android 全工程测试通过（583 actionable tasks）；Nanobot 服务端测试通过（5461 passed, 17 skipped）；`git diff --check` 通过。

### DEVICE-REAL-003：增量安装、冷启动与只读检查

```text
adb -s HT7390201404 install -r app/build/outputs/apk/debug/app-universal-debug.apk
adb -s HT7390201404 shell am force-stop com.nanobotkt.debug
adb -s HT7390201404 shell am start -n com.nanobotkt.debug/com.nanobotkt.MainActivity
adb -s HT7390201404 shell uiautomator dump /sdcard/window.xml
```

结果：安装成功且保留应用数据；前台 Activity 正常；UI dump 成功，可见 Automations 页面和现有任务；最近 logcat 未发现应用 Fatal Exception、ANR 或崩溃。未点击保存、配置、运行、删除等远程写操作。

### 本轮边界

本轮完成的是代码级回归、服务端回归、目标服务器构建和设备冷启动/只读 Smoke Test。整个应用仍不能宣称全量 E2E 完成；Provider/模型真实调用、Voice/Image/Web Search、Apps、Automations 完整生命周期、Channels、Pairing、异常网络重连以及多设备/横竖屏/低内存适配仍未全部覆盖。

## 2026-08-08 Settings 剩余 section 只读 Smoke Test（本轮）

### DEVICE-READONLY-004：Settings section 导航

设备：`HT7390201404`；包名：`com.nanobotkt.debug`；使用已安装的目标服务器地址 Debug APK。

依次打开并读取以下 Settings section，未点击 Save、Delete、Start、Configure、Enable 或其他远程写操作：

- Appearance：页面正常加载，显示主题、语言、Density、Activity details、Code wrapping 等选项。
- Models：页面正常加载，显示 Default、Primary、当前模型 preset、Add model configuration 和 Model call order。
- Image：页面正常加载，显示 Image generation、provider 状态、base、model 和默认尺寸。
- Voice：页面正常加载，显示 Transcription、provider 状态、model、language 和限制项。
- Web：页面正常加载，显示 provider、credentials、max results、timeout、Jina reader。
- System：页面正常加载，显示 Runtime、Gateway、Workspace、API service 和 Log out。
- Security：页面正常加载，显示 Web safety、Local Service Access、Default access、Default Permission 和 Save。

UI dump 已保存至：

```text
/tmp/nanobotkt-settings-appearance.xml
/tmp/nanobotkt-settings-models.xml
/tmp/nanobotkt-settings-image.xml
/tmp/nanobotkt-settings-voice.xml
/tmp/nanobotkt-settings-web.xml
/tmp/nanobotkt-settings-system.xml
/tmp/nanobotkt-settings-security.xml
```

最近 2000 行 logcat 未发现 `FATAL EXCEPTION`、`ANR in com.nanobotkt.debug` 或应用进程崩溃标记。

### 结论

Settings 剩余 section 的登录后只读导航通过；这只证明页面加载、服务端数据展示和导航没有明显错误，不等同于各 section 的真实保存、Provider 调用、录音上传、图片生成或网络搜索业务 E2E 已完成。

## 2026-08-08 当前轮：Chat File Preview 与 Automations/Channels 回归

### 本轮实现

- Chat 文件编辑消息新增 `Preview`，通过 `/api/sessions/{sessionKey}/file-preview?path=...` 读取预览内容。
- 预览状态按会话和请求 generation 隔离：切换会话、重开同一会话、连续预览不同文件或退出登录时，迟到响应不会覆盖当前预览。
- Automations 补齐详情/操作状态展示和请求竞态清理；Channels 补齐配置、验证、连接轮询/取消和 stale 状态处理。

### 验证命令与结果

```text
bash ./gradlew :feature:automations:testDebugUnitTest :feature:automations:compileDebugKotlin --console=plain
bash ./gradlew :feature:channels:testDebugUnitTest :feature:channels:compileDebugKotlin --console=plain
bash ./gradlew :feature:chat:testDebugUnitTest :core:model:testDebugUnitTest --console=plain
bash ./gradlew test --console=plain
bash ./gradlew :app:testDebugUnitTest :app:assembleDebug -PNANOBOT_SERVER_URL=http://192.168.55.147:8765 --console=plain
uv run pytest -q                                      # /Users/yaotutu/Desktop/code/nanobot
git diff --check
```

结果：Automations、Channels、Chat/Core Model 模块测试与编译通过；Android 全工程测试通过（`583 actionable tasks`）；目标服务器地址 App 单测与 Debug APK 构建通过；Nanobot 服务端 `5461 passed, 17 skipped`；`git diff --check` 通过。Chat 预览新增“同一会话连续请求的旧响应不能覆盖新文件”回归测试通过。

### 设备复测

```text
adb -s HT7390201404 install -r app/build/outputs/apk/debug/app-universal-debug.apk
adb -s HT7390201404 reverse tcp:8765 tcp:8765
adb -s HT7390201404 shell am force-stop com.nanobotkt.debug
adb -s HT7390201404 shell monkey -p com.nanobotkt.debug 1
adb -s HT7390201404 shell uiautomator dump /sdcard/window.xml
```

结果：APK 增量安装成功且保留应用数据；前台 Activity 为 `com.nanobotkt.debug/com.nanobotkt.MainActivity`；应用进程存活；首次 Compose 页面约 5–8 秒完成加载，随后可见现有 Chat 内容和输入框；UI dump 成功；最近应用日志未发现 `FATAL EXCEPTION`、`ANR in` 或崩溃标记。截图保存于 `/tmp/nanobotkt-smoke-8s.png`。

本轮没有新增远程写操作。由于当前可见会话没有稳定的文件编辑消息，设备上未能完成“点击 Preview 并验证真实文件内容”的 UI 点击步骤；该行为已通过 Repository 单测和服务端只读路由契约验证，不能把它标为完整真实 E2E PASS。


## 2026-08-08 当前接手轮补充

### 本轮代码修复与回归

- WebSocket pending 会话请求的 `attached` 匹配与 re-auth 失败重连行为已修复并有回归测试。
- Chat canonical refresh 仅在成功后清 dirty flag。
- Settings Provider 的结构化 Map 回显改为合法 JSON。
- Security Pairing 页面离开后停止轮询。
- `bash ./gradlew test --console=plain`：通过。
- `bash ./gradlew :app:testDebugUnitTest :app:assembleDebug -PNANOBOT_SERVER_URL=http://192.168.55.147:8765 --console=plain`：通过。
- `/Users/yaotutu/Desktop/code/nanobot` 执行 `uv run pytest -q`：`5461 passed, 17 skipped`。
- 目标设备 `HT7390201404` 增量安装并冷启动成功，UI dump 成功，未发现应用 Fatal/ANR。

### 真实 Provider / 工具 Smoke Test

| 场景 | 实际结果 | 状态 | 备注 |
| --- | --- | --- | --- |
| 文本 Provider | WebSocket 临时会话收到 reasoning/delta/stream_end，随后清理会话 | **PASS** | 未保留测试会话 |
| Web Search | 真实 Chat 搜索 Nanobot 官方仓库并正常结束 | **PASS** | 未保留测试会话 |
| Image Generation | 未收到 `generate_image` 工具调用，也没有 media artifact | **未通过** | 不能以 Settings 已配置替代真实 E2E |
| Transcription | 多种 WAV 输入均返回 `transcription_error`，detail 为空 | **未通过** | 需继续定位 Provider/adapter/外部依赖 |

### 本轮未执行的真实写操作

Apps/MCP 安装或变更、Channels 配置/连接、Pairing approve/deny、Automations 创建/修改/运行/删除、Settings Save、OAuth 登录/退出均未执行；因此这些范围不能标记为已完成。

## 2026-08-08 当前轮补充：真实服务端链路与全量回归复核

### SERVER-E2E-005：Image Generation

- 通过真实服务器 WebSocket 发起临时会话并使用 `generate_image`。
- MiniMax 图片模型已通过 Settings API 修正为 `image-01`；之前的 `openai/gpt-5.4-image-2` 不属于 MiniMax 支持模型。
- 真实调用收到工具事件 `phase=end` 和图片 artifact，Image Generation 链路通过。
- 远程副作用：仅修改了 Image Generation 的 model；未改变 enabled、Provider API Key 或其他 Provider 配置。

### SERVER-E2E-006：Transcription

- 通过真实服务器 WebSocket 发送静音 WAV 和 macOS 生成的语音 WAV。
- 两次请求均返回 `transcription_error`，当前远程运行进程返回 `detail=empty`，不能标记为成功。
- 本地源码已增加稳定的 `provider_error/provider/code` 错误契约及空结果回归测试，但该改动尚未部署到远程服务器；远程 Provider 的 API key、额度、模型权限和实际 HTTP 响应仍未确认。

### SERVER-READONLY-007：Apps/MCP/Channels/Pairing/Automations

使用 fresh bootstrap token 进行只读检查，没有执行安装、启用、删除、配置、运行或 approve/deny：

- `/api/settings`：HTTP 200；Image model 为 `image-01`，Transcription 为 siliconflow / `FunAudioLLM/SenseVoiceSmall`，已配置状态正常。
- `/api/settings/cli-apps`：HTTP 200，返回 102 个目录项。
- `/api/settings/mcp-presets`：HTTP 200，返回 13 个 preset。
- `/api/settings/pairing`：HTTP 200，当前 pending requests 为 0。
- `/api/settings/nanobot-features`：HTTP 200。
- `/api/settings/channels/validate?name=feishu`：HTTP 200。
- `/api/settings/channels/validate?name=websocket`：HTTP 200。
- `/api/webui/automations`：HTTP 200，保留用户现有任务 `dream` 和 `heartbeat`，未做任何变更。
- `/api/sessions`：HTTP 200。

### SERVER-CLEANUP-008：临时会话清理

删除了本轮之前遗留的临时会话 `websocket:b95bc275-074c-4ab3-abf9-50cc839f7fd7`；接口返回 `deleted=true`。随后重新读取 `/api/sessions`，当前返回 72 个会话，未发现本轮已知临时会话残留。没有批量删除用户会话。

### 回归结果

```text
uv run pytest -q                                      # /Users/yaotutu/Desktop/code/nanobot
```

结果：`5464 passed, 17 skipped`。

本轮新增/同步的服务端测试契约覆盖：

- Image Generation Provider 切换和模型校验；
- Transcription Provider 空结果错误契约；
- WebSocket Settings API 使用当前 OpenRouter 默认图片模型 `openai/gpt-5.4-image-2`。

两个仓库的 `git diff --check` 均通过。设备 `HT7390201404` 仍在线，已安装 `com.nanobotkt.debug`，当前进程存在；最近 logcat 未发现应用 Fatal Exception、ANR 或崩溃标记。

### 当前边界

本轮不能宣称所有业务均已完成：Transcription 真实 Provider 调用仍失败；Apps/MCP、Channels、Pairing、Automations 只完成只读/契约检查，没有执行真实写操作和完整生命周期；异常网络重连、横竖屏、多设备、低内存适配也未完成全量验证。

远程产生的外部副作用只有：修正 Image Generation model 和删除本轮遗留的一个临时测试会话。没有保存其他 Settings、修改 Automation、安装 App、启用/删除 MCP、配置 Channel、处理 Pairing、stage、commit、push 或 PR。

## 2026-08-08：隔离 Gateway 写操作生命周期收尾

为避免修改用户正在使用的真实 Gateway，以下写操作全部在独立临时 Gateway 中完成：临时配置、临时 workspace、临时 pairing store、动态端口和本地 MCP stdio fixture。临时进程在测试结束后已停止，临时目录由测试 harness 清理。

| 范围 | 生命周期 | 实际结果 | 状态 | 外部副作用 |
| --- | --- | --- | --- | --- |
| Apps / MCP | `list → custom → test → tools → test → remove → list` | 自定义 stdio MCP 成功保存；fixture 工具 `fixture_ping` 完成握手并被发现；工具白名单更新后再次测试成功；删除后列表确认不存在 | **PASS** | 仅修改临时配置，未修改真实 Gateway |
| Automations | `list → update → disable → enable → delete → list` | 临时绑定任务成功改名、更新消息和周期；禁用/启用状态正确；删除后列表确认不存在 | **PASS** | 仅修改临时 cron store，未修改真实 `dream` / `heartbeat` |
| Pairing | `generate_code → list → deny → list` | 临时 Telegram pairing request 可在 API 列表中看到；deny 成功；再次列表确认消失 | **PASS** | 仅修改临时 pairing store，未审批或拒绝真实请求 |

隔离测试只记录 PASS/FAIL 和结构化断言，不在仓库或最终汇总中记录 bootstrap secret、API token 或 pairing code。

## 2026-08-08：Android 最终只读复核

设备 `HT7390201404` 上的 `com.nanobotkt.debug` 仍在前台运行，`MainActivity` 正常显示 Chat 页面。UI dump 成功，共 63 个节点，输入框和发送按钮可见；最近 2500 条 logcat 未发现 `FATAL EXCEPTION`、`ANR`、应用 Crash 或 Force Finish。

证据文件：

- `/tmp/nanobotkt-ui-20260808-174823.xml`
- `/tmp/nanobotkt-screen-20260808-174823.png`

本次只读复核没有点击 UI、发送消息、修改远程配置或重新安装 APK。

## 当前收尾结论

- 本轮交接要求的隔离 Apps/MCP、Automations、Pairing 生命周期已完成并通过。
- 真实 Gateway 仅做了只读检查；现有 `dream`、`heartbeat` 未修改、未运行、未删除。
- 这不等于整个应用所有功能都完成全量验收。真实 Transcription 仍未通过；Channels、Provider/Voice/Image 的完整真实场景、异常网络重连、横竖屏、多设备和低内存适配仍有边界未覆盖。

## 2026-08-08 当前接手轮：最新回归与设备复核

### BUILD-006：服务端与 Android 最新验证

- `/Users/yaotutu/Desktop/code/nanobot`：`uv run pytest -q` → `5467 passed, 17 skipped`。
- `/Users/yaotutu/Desktop/code/nanobot`：`git diff --check` → 通过。
- `/Users/yaotutu/Desktop/code/nanobotkt`：`bash ./gradlew :app:testDebugUnitTest :app:assembleDebug -PNANOBOT_SERVER_URL=http://192.168.55.147:8765 --console=plain` → `BUILD SUCCESSFUL`，`383 actionable tasks`。

### DEVICE-007：目标地址 APK 安装与冷启动

- 设备：`HT7390201404`。
- 安装：`adb install -r app/build/outputs/apk/debug/app-universal-debug.apk` → 成功。
- 验证：`MainActivity` 正常前台显示 Chat 页面，输入框和发送入口可见；最近采样 logcat 未发现应用 Fatal Exception、ANR 或崩溃标记。
- 副作用：仅替换 APK 并重启应用，未清除应用数据；未向真实 Gateway 执行写操作。

### 当前边界

上述结果证明最新代码已完成服务端回归和 Android 构建/冷启动验证，但不证明所有业务全量完成。File Preview 真实点击 E2E、Channels 完整真实链路、Transcription 真实 Provider 成功调用、异常 WebSocket 自动重连、横竖屏/多设备/低内存适配仍未覆盖或未通过。远程 Gateway 尚未部署本地 transcription 修复。

## 2026-08-08 当前接手轮：Channels 设备验证与真实环境恢复

### CHANNEL-008：隔离 Gateway 的 Channels 只读/低副作用验证

为避免改变真实 Gateway，本次先使用独立临时 Gateway：

- Gateway 控制端口：`18791`
- WebSocket/WebUI 端口：`18792`
- 临时 workspace：`/tmp/nanobotkt-isolated.FgJfEx/workspace`
- 设备：`HT7390201404`
- 包名：`com.nanobotkt.debug`

操作步骤：

1. 从 Sidebar 进入 `Settings`。
2. 打开 `Channels` section，再点击 `Open Channels`。
3. 确认频道列表能加载并显示 DingTalk、Discord、Email、nanobot、Matrix、Mattermost、MoChat、Microsoft Teams 等条目。
4. 打开 DingTalk 配置对话框，确认 `clientId`、`clientSecret`、`allowFrom` 字段和 `Validate` / `Save & enable` 按钮出现。
5. 不填写凭据，点击 `Validate`。

实际结果：

- 频道列表成功加载；隔离 Gateway 中频道状态显示为 `stopped`。
- DingTalk 配置对话框成功打开。
- 空必填字段校验显示 `Please fill all required fields.`。
- 没有点击 `Save & enable`，没有向真实 Gateway 或任何真实 Provider 写入配置。

状态：**PASS（隔离环境只读/校验路径）**。

证据文件：

- `/tmp/nanobotkt-open-channels.xml`
- `/tmp/nanobotkt-channel-dialog.xml`
- `/tmp/nanobotkt-channel-validate.xml`
- `/tmp/nanobotkt-open-channels.png`
- `/tmp/nanobotkt-channel-dialog.png`

### REAL-RESTORE-008：真实服务器环境恢复

操作步骤：

1. 停止隔离 Gateway，并确认 `18791` / `18792` 无监听进程。
2. 执行目标地址构建：

   ```text
   bash ./gradlew :app:assembleDebug -PNANOBOT_SERVER_URL=http://192.168.55.147:8765 --console=plain
   ```

3. 使用 `adb install -r` 安装 `app-universal-debug.apk`。
4. 清除本轮隔离测试产生的本地应用数据，并从 `/tmp/nanobotkt-debug-data-backup.tar` 恢复原数据。
5. 移除 `tcp:18792 -> tcp:18792`，保留 `tcp:8765 -> tcp:8765`。
6. 使用用户提供的网关凭据重新认证。

实际结果：

- 目标地址 APK 构建成功：`BUILD SUCCESSFUL`，`366 actionable tasks`。
- APK 增量安装成功。
- 原有聊天会话和历史内容恢复，重新认证后进入 Chat 页面。
- 当前设备仅保留 `tcp:8765 -> tcp:8765` reverse；隔离端口已移除。
- 应用进程存活，前台 Activity 为 `com.nanobotkt.debug/com.nanobotkt.MainActivity`。

状态：**PASS**。

证据文件：

- `/tmp/nanobotkt-real-authenticated.xml`
- `/tmp/nanobotkt-real-restored.xml`
- `/tmp/nanobotkt-real-final-after-channels.xml`
- `/tmp/nanobotkt-real-settings-channels.xml`
- `/tmp/nanobotkt-real-open-channels.xml`
- `/tmp/nanobotkt-real-nanobot-channel-dialog.xml`
- `/tmp/nanobotkt-real-authenticated.png`
- `/tmp/nanobotkt-real-settings-channels.png`
- `/tmp/nanobotkt-real-open-channels.png`
- `/tmp/nanobotkt-real-final-after-channels.png`

### REAL-CHANNEL-009：真实服务器 Channels 只读复核

在恢复后的真实服务器 APK 中，从 `Settings → Channels → Open Channels` 加载列表并打开已存在的 `nanobot` 条目；实际看到 `nanobot` 为 `enabled · running`，其余已展示条目为 `stopped · stopped`。配置对话框显示了当前已配置字段，但本次没有 Validate、Save、Enable、Connect 或其他远程写操作，随后关闭对话框并返回 Chat。

状态：**PASS（真实服务器只读页面验证）**。

### 副作用与边界

本轮产生的副作用仅包括：停止本轮创建的隔离 Gateway、在测试设备上增量安装 Debug APK、清除并恢复测试设备上的应用数据、重新建立认证会话，以及设置 `tcp:8765 -> tcp:8765` ADB reverse。真实 Gateway 只执行了健康检查、认证和 Channels 只读加载；没有保存频道配置、连接真实聊天平台、发送真实频道消息或修改真实用户会话。

本轮仍不能宣称所有功能全量完成。真实 Transcription Provider 成功链路、Channels 的真实配置/连接/消息收发、Settings 写操作、异常网络下完整 WebSocket 自动重连、横竖屏/多设备/低内存适配仍未完成全量验证；Apps、Automations、Pairing 的真实 Gateway 写操作仅在隔离 Gateway 中验证过。

### NETWORK-010：真实服务器 ADB reverse 断开/恢复

在已恢复的真实服务器 APK Chat 页面执行低风险网络异常复核：

1. 临时移除 `tcp:8765 -> tcp:8765` reverse，等待 4 秒。
2. 检查应用进程、前台 Activity、UI dump 和截图。
3. 恢复 `tcp:8765 -> tcp:8765` reverse，等待 8 秒。
4. 再次检查应用进程、前台 Activity、UI dump、reverse 状态和 logcat。

结果：断开期间 PID `20676` 保持存活，`MainActivity` 保持前台，已有 Chat UI 没有崩溃；恢复后 reverse 正常建立，PID 和前台 Activity 保持不变，最近 4000 行 logcat 未发现 `FATAL EXCEPTION`、`ANR in com.nanobotkt.debug`、Force Finish 或进程死亡标记。

状态：**PASS（进程/UI 稳定性与基础恢复）**。这不是完整的网络错误 UI/长时间重连测试，断线期间没有可稳定提取的专用“连接中”文案，因此仍不把它描述为完整异常网络场景全覆盖。

证据文件：

- `/tmp/nanobotkt-real-disconnected.xml`
- `/tmp/nanobotkt-real-reconnected.xml`
- `/tmp/nanobotkt-real-disconnected.png`
- `/tmp/nanobotkt-real-reconnected.png`

### FINAL-011：回归命令后的目标地址产物复核

由于不带 `NANOBOT_SERVER_URL` 的单测命令会重新生成默认 BuildConfig，本轮在最终交付前再次执行目标地址 `:app:assembleDebug`，确认并安装最终产物：

- `app/build/generated/source/buildConfig/debug/com/nanobotkt/BuildConfig.java` 的 `NANOBOT_SERVER_URL` 为 `http://192.168.55.147:8765`。
- `adb install -r app/build/outputs/apk/debug/app-universal-debug.apk` 成功。
- 强制停止并重新启动后，PID `22110` 存活，`MainActivity` 前台显示 Chat 页面，UI dump 可见 `Type your message...`。
- 当前只保留 `tcp:8765 -> tcp:8765` reverse；最近 3500 行 logcat 未发现应用 Fatal/ANR/Force Finish/进程死亡标记。

状态：**PASS**。

证据文件：`/tmp/nanobotkt-final-target-ui.xml`、`/tmp/nanobotkt-final-target.png`。

## 2026-08-08：真实 Gateway 写操作、WebSocket 收发与长断网重连

### REAL-009：真实 Gateway Settings / Provider / Apps / Automations

在用户提供的真实 Gateway 上完成了低风险、可恢复的写操作验证：

- Settings：`tool_hint_max_length` 临时改动 `40 → 41 → 40`，两次 HTTP 更新成功，最终 GET 确认恢复为 `40`。
- Provider：对 `siliconflow` 提交现有配置值，HTTP 更新成功；没有输出、读取或修改 API key。
- Apps：现有 `minimax` 完成 `install → update → test`，HTTP 均成功；`test` 实际执行 CLI 帮助命令并返回退出码 0。未卸载现有 App。
- Automations：创建 2099 年执行的临时任务，完成 `create → update → disable → enable → delete`；最终已删除，未触发消息发送、文件写入或外部服务调用；现有 `dream`、`heartbeat` 未修改。

证据：

- `/tmp/nanobotkt-settings-before-writes.json`
- `/tmp/nanobotkt-settings-update-41.json`
- `/tmp/nanobotkt-settings-update-40.json`
- `/tmp/nanobotkt-settings-after-writes.json`
- `/tmp/nanobotkt-provider-update-siliconflow.json`
- `/tmp/nanobotkt-app-install.json`
- `/tmp/nanobotkt-app-update.json`
- `/tmp/nanobotkt-app-test.json`
- `/tmp/nanobotkt-automation-ws-result.json`
- `/tmp/nanobotkt-automation-update2.json`
- `/tmp/nanobotkt-automation-disable.json`
- `/tmp/nanobotkt-automation-enable.json`
- `/tmp/nanobotkt-automation-delete.json`

### REAL-010：真实 WebSocket 频道消息收发

通过真实 Gateway WebSocket 建立连接并完成：

1. `ready`
2. `new_chat`
3. `attached`
4. 发送真实消息
5. 收到 `reasoning_delta`、`delta=OK`、`stream_end`、`turn_end` 和 `goal_status=idle`
6. 删除临时 chat，列表确认不存在

证据：`/tmp/nanobotkt-real-ws-smoke.mjs`、`/tmp/nanobotkt-real-ws-smoke.log`。

### REAL-011：Android 长时间断网与完整自动重连

目标设备 `HT7390201404` 上执行了真实断链：

- 移除 `tcp:8765 → tcp:8765` reverse 约 90 秒，超过当前重连退避上限周期。
- 断开期间 PID `22110` 保持，`MainActivity` 保持前台，UI dump 成功。
- 恢复 reverse 后等待 45 秒，应用未崩溃；随后通过 Android 输入框发送唯一低风险测试消息。
- Android 输入法将部分 ASCII 空格转换为输入法候选字符，但消息仍成功提交；截图中可见真实助手回复，耗时约 `13.8s`。
- 恢复后 PID、前台 Activity 和 reverse 均正常；最近 logcat 未发现 `FATAL EXCEPTION`、`ANR`、Force Finish 或应用进程死亡标记。

证据：

- `/tmp/nanobotkt-reconnect-long-before.png`
- `/tmp/nanobotkt-reconnect-long-disconnected.png`
- `/tmp/nanobotkt-reconnect-long-after.png`
- `/tmp/nanobotkt-reconnect-long-message.png`
- `/tmp/nanobotkt-reconnect-long-before.xml`
- `/tmp/nanobotkt-reconnect-long-disconnected.xml`
- `/tmp/nanobotkt-reconnect-long-after.xml`
- `/tmp/nanobotkt-reconnect-long-message.xml`
- `/tmp/nanobotkt-reconnect-long-20260808-200747.log`

结论：Android 进程/UI 稳定性、reverse 恢复、WebSocket 自动重连及恢复后的真实发送/接收链路 **PASS**。该结果针对当前设备和当前 Gateway，不代表所有设备、横竖屏或低内存场景均已覆盖。

### 当前仍未通过或被外部条件阻塞

- Transcription Provider：WebSocket `transcribe_audio` 请求链路已到达真实 Gateway，但 `siliconflow` 返回 `transcription_error`，`provider-models` 状态为 `not_configured`，远端拒绝现有 credential；没有有效 credential 时不能宣称成功。
- Feishu：真实配置已保存，Gateway runtime 显示 running；`connect/start` 返回 pending，轮询仍为 `Waiting for authorization`，需要用户在 Feishu/Lark 扫码。尚未执行真实 Feishu 消息发送。
- Pairing：真实 Gateway 当前 pending requests 为空，不能伪造 code 或对未知 code 执行 approve/deny；真实 Pairing 生命周期仅在隔离 Gateway 完成，等待真实未授权频道用户产生 pending request。

### 本轮工程回归

- `bash ./gradlew :core:transport:testDebugUnitTest :feature:channels:testDebugUnitTest :feature:settings:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug --console=plain` → `BUILD SUCCESSFUL`。
- `uv run pytest -q`（`/Users/yaotutu/Desktop/code/nanobot`）→ `5467 passed, 17 skipped`。
- 目标地址 APK 重新构建并安装：`bash ./gradlew :app:assembleDebug -PNANOBOT_SERVER_URL=http://192.168.55.147:8765 --console=plain` → `BUILD SUCCESSFUL`；设备增量安装成功，应用数据未清除。
- `git diff --check` 待最终收尾时再次执行。

本轮没有 stage、commit、push 或 PR；除了 APK 增量安装、真实 Gateway 上上述可恢复写操作和临时自动化/会话创建后删除外，没有其他外部副作用。

## 2026-08-09：本轮收尾设备 Smoke Test

### DEVICE-RECOVERY-012：真实 Android 设备进程恢复

环境与前置条件：

- 设备：真实 Android 设备 `Pixel_XL`，序列号 `HT7390201404`。
- APK：当前工作区构建的 `com.nanobotkt.debug` Debug APK；设备保留既有应用数据和认证会话。
- Gateway：沿用已认证的真实 Gateway 会话；本轮没有清除数据、注销账号或读取任何凭据。
- 当前环境没有可用 AVD，`emulator -list-avds` 无输出，因此本项不是模拟器验证。

操作步骤：

1. 记录进程、前台 Activity 和 ADB reverse 状态。
2. 返回 HOME。
3. 执行 `adb shell am kill com.nanobotkt.debug`，模拟系统回收应用进程；不执行 `pm clear`。
4. 确认旧 PID 消失后重新启动应用。
5. 等待页面、会话和 Transport 恢复，检查 UI dump、截图和最近 logcat。

实际结果：

- 旧 PID `27497` 在 `am kill` 后消失，新 PID 为 `27682`。
- 重新启动后的前台 Activity 为 `com.nanobotkt.debug/com.nanobotkt.MainActivity`。
- 当前会话标题和历史 Markdown 内容恢复，输入框正常显示，Transport 状态显示为“就绪”。
- 最近 5000 行 logcat 未发现 `FATAL EXCEPTION`、`ANR in com.nanobotkt.debug`、Force Finish 或应用进程死亡标记。
- ADB reverse 与真实 Gateway 会话保持可用；本轮没有产生新的 Gateway 写操作。

状态：**PASS（真实 Android 设备进程恢复）**。

证据文件：

- `/tmp/nanobotkt-process-recovery-20260809-224912/pid-before.txt`
- `/tmp/nanobotkt-process-recovery-20260809-224912/pid-after-kill.txt`
- `/tmp/nanobotkt-process-recovery-20260809-224912/pid-after-relaunch.txt`
- `/tmp/nanobotkt-process-recovery-20260809-224912/activities-after.txt`
- `/tmp/nanobotkt-process-recovery-20260809-224912/ui.xml`
- `/tmp/nanobotkt-process-recovery-20260809-224912/screenshot.png`
- `/tmp/nanobotkt-process-recovery-20260809-224912/logcat.txt`

### 本轮设备验证边界

- 已完成真实设备上的认证会话恢复、真实 Gateway Chat 加载、WebSocket 建连/收发/断链恢复和进程恢复；相关历史证据见 `REAL-RESTORE-008`、`REAL-009`、`REAL-010`、`REAL-011`。
- 本轮没有可用 Android Emulator/AVD，不能将真实设备结果描述为模拟器结果。
- 本轮没有再次执行真实 Gateway 写操作、真实登录注销、Provider 调用、频道消息收发或 Feishu 扫码流程。

### FINAL-013：最终目标地址 APK 与 Gateway 只读可达性复核

- 使用当前工作区代码执行 `:app:assembleDebug -PNANOBOT_SERVER_URL=http://192.168.55.147:8765`，构建成功。
- `BuildConfig.NANOBOT_SERVER_URL` 确认为 `http://192.168.55.147:8765`。
- `app-universal-debug.apk` 增量安装到 `HT7390201404` 成功；重新启动后 PID 为 `27945`，前台 Activity 为 `com.nanobotkt.debug/com.nanobotkt.MainActivity`。
- 最终 UI 仍恢复到既有会话，输入框和 Transport“就绪”可见；最近 5000 行 logcat 未发现应用崩溃、ANR 或 Force Finish。
- 主机对真实 Gateway 执行只读探测：`/health` 和 `/` 返回 HTTP 200；未携带认证信息访问 `/webui/bootstrap` 返回 HTTP 401，符合认证边界预期；没有输出响应体中的潜在敏感内容。

状态：**PASS（最终产物/真实 Gateway 只读可达性）**。

证据文件：

- `/tmp/nanobotkt-final-ui-20260809.xml`
- `/tmp/nanobotkt-final-20260809.png`
- `/tmp/nanobotkt-final-launch.txt`（若存在，仅包含启动命令输出）

## 2026-08-10：架构精简重构后的真机专项 Smoke

### ARCH-001：构建目标与测试环境

- 目标设备：Google Pixel XL，设备序列号 `HT7390201404`，Android 10 / API 29。
- 当前没有可用的 `emulator-5554`；本轮没有伪造模拟器结果，而是在已连接真机上执行等价专项验证。
- 安装产物：`app/build/outputs/apk/debug/app-universal-debug.apk`。
- Debug BuildConfig 指向 `http://localhost:8765`；测试期间临时配置 `adb reverse tcp:8765 tcp:8765`，本机 Gateway 根路径返回 HTTP 200。
- 使用 `adb install -r` 保留现有应用数据，安装成功；冷启动结果为 `LaunchState: COLD`，`MainActivity` 启动成功。
- Smoke 完成后已移除本轮创建的 `tcp:8765` reverse。

状态：**PASS**。

### ARCH-002：Chat 与 Composer 拆分回归

只读验证以下路径：

1. 已有会话消息列表正常渲染，Composer 显示“就绪”。
2. Composer “更多选项” BottomSheet 正常打开，图片、文件、模型和工作区入口均可见。
3. “选择模型”子页正常打开并可返回；未切换模型。
4. “工作区访问权限”子页正常显示默认访问与完全访问选项；未改变现有权限。
5. 页面返回后 Chat 输入框、会话列表入口和更多选项仍可见。

本轮未发送消息、未新建会话、未切换模型、未修改工作区范围，因此没有真实 Gateway 写副作用。

状态：**PASS**。

### ARCH-003：Skills 状态边界回归

只读验证以下路径：

1. Skills 列表从真实 Gateway 成功加载。
2. 打开 `cli-app-minimax` 详情，详情内容、来源与关闭入口正常显示。
3. 关闭详情后列表仍保持可用。

详情快速切换、关闭后迟到响应和 logout/reset 代次保护由 `SkillsViewModelTest` 与 `SkillsRepositoryTest` 覆盖；本轮真机 Smoke 未人为注入网络延迟。

状态：**PASS**。

### ARCH-004：Settings 文件拆分回归

逐页只读进入并截图以下分区：

- Overview
- Appearance
- Models
- Image
- Voice
- Web
- System
- Security

所有页面均正常渲染并可通过顶部选择器切换，最终可返回 Chat。测试期间未切换主题或语言，未保存 Provider、模型、能力或 Security 设置，也未执行 Channels 写操作。

状态：**PASS**。

### ARCH-005：进程与崩溃检查

- 完成全部页面切换后应用 PID `7139` 仍存活。
- 最终 UI dump 确认 Chat 的导航、输入框、会话列表入口、更多选项和“就绪”状态均存在。
- 本轮清空 logcat 后执行专项路径；结束时未匹配到应用 `FATAL EXCEPTION`、ANR、Force Finish 或 `Process: com.nanobotkt` 崩溃标记。

状态：**PASS**。

证据目录：

- `/tmp/nanobotkt-architecture-smoke-20260810-230924/`

该目录包含启动结果、PID、前台 Activity、各页面截图、UI dump 和崩溃检查结果。证据保留在 `/tmp`，不纳入 Git。该专项 Smoke 只覆盖本轮重构涉及的 Chat、Composer、Skills 和 Settings 只读路径，不代表登录、真实消息发送、外部设备、Provider 成功链路或所有应用功能均已重新完整验证。

### ARCH-006：最终 APK 真实 Gateway 直连复核

- 使用当前工作区代码执行 `:app:assembleDebug -PNANOBOT_SERVER_URL=http://192.168.55.147:8765`，构建结果为 `BUILD SUCCESSFUL`；生成的 `BuildConfig.NANOBOT_SERVER_URL` 与目标地址一致，主机对 Gateway 根路径只读探测返回 HTTP 200。
- 在 Google Pixel XL（`HT7390201404`，Android 10 / API 29）上先清除全部 ADB reverse 映射，再通过 `adb install -r` 安装最终 APK；安装前后 `adb reverse --list` 均为空，因此本轮连接不依赖本机端口转发。
- 保留既有应用数据后执行强制停止与冷启动，结果为 `LaunchState: COLD`；既有认证会话仍有效，无需重新登录，应用 PID `9121` 在专项结束时仍存活。
- Sidebar 显示 `Connected`；已有 Chat 页面和消息内容可读，Skills 列表可加载并成功打开/关闭 `cli-app-minimax` 详情，Settings Overview 可正常渲染。
- 本轮只执行页面导航和读取，没有发送消息、新建会话、修改模型/工作区权限/Settings、注销登录、调用 Provider 或触发外部设备交互，因此没有新增真实 Gateway 写副作用。
- 清空 logcat 后执行上述路径，结束时未匹配到应用 `FATAL EXCEPTION`、ANR、Force Finish 或进程死亡标记。

状态：**PASS（最终 APK / Pixel XL / 真实 Gateway 无 reverse 直连专项）**。

证据目录：

- `/tmp/nanobotkt-architecture-direct-smoke-20260810/`

该目录包含 Chat、Sidebar、Skills 列表与详情、Settings Overview 的截图和 UI dump，以及崩溃扫描结果。证据仅保留在 `/tmp`，不纳入 Git。本项是针对最终 APK 网络目标和本轮重构涉及页面的只读专项复核，不代表重新完成了登录、消息发送、Provider、频道或外部设备的全量端到端测试。
