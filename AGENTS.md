# NanobotKT Agent 规范

本文件是 **NanobotKT Android 客户端工程** 的工作约定，适用于在本仓库中工作的所有 Agent。

- macOS 工作区：`/Users/yaotutu/Desktop/code/nanobotkt`
- Windows 同步工作区：`C:\Users\Administrator\AndroidStudioProjects\nanobotkt`
- Nanobot 服务端源码：`/Users/yaotutu/Desktop/code/nanobot`

> 本文件只约束 NanobotKT 客户端，不自动约束同级的 Nanobot 服务端。

---

## 1. 最高优先级规则

以下规则由用户手工维护。除非用户明确要求，不要修改本节内容。

1. 所有新增或修改的代码都必须添加详细的中文注释。注释应重点说明状态转换、竞态保护、接口边界、错误处理和不直观的业务规则；不要只为显而易见的语句添加无意义注释。
2. `/Users/yaotutu/Desktop/code/nanobot` 是服务端源码固定路径。除非用户明确要求，禁止修改、格式化、重置、清理或删除该目录中的内容。
3. NanobotKT 是 Android 手机客户端，唯一允许连接的服务端入口是 `http://192.168.55.147:8765/`。HTTP、Bootstrap 与 WebSocket 都必须使用该主机和 `8765` 端口；WebSocket 只能在此入口上使用服务端下发的路径与令牌。除非用户明确指定新的服务端地址，禁止使用或替换为 `127.0.0.1`、`localhost`、`10.0.2.2`、`18790` 或任何其他主机/端口，禁止通过 `adb reverse` 端口映射绕过此约束，也禁止把服务端内部监听地址当成客户端连接地址。
4. 禁止过度设计。这里的“禁止过度设计”是指禁止没有真实问题支撑的预测式抽象、套层和框架化，不等于禁止重构。
5. 当前项目处于早期阶段，虽然主要产品功能已完成约 90%，但架构尚未冻结。发现明确的职责混乱、并发风险、生命周期漏洞、错误依赖方向或明显维护成本时，应积极重构，不要因为功能已经可用而保留已确认的结构问题。
6. 必要重构可以调整文件结构、模块内部职责、公开接口和跨模块组装方式；但必须有具体问题、清晰收益和匹配的验证，避免只为了形式统一、行数、设计模式或未来假设进行改造。

---

## 2. 开始任务前：先确认背景与实时状态

每次开始任务都必须先执行：

```bash
git status --short
git branch --show-current
git log -1 --oneline --decorate
git rev-parse HEAD
git rev-parse origin/main
```

执行原则：

- 先识别工作区已有修改、未跟踪文件和它们可能的来源，再开始编辑。
- 同时确认当前分支及其用途：`dev` 用于 Dev 预发布，`main` 用于正式 Release；不要把 `origin/main` 误当成当前分支的上游状态。
- 不覆盖、回退、删除或格式化用户、其他 Agent 或上一阶段留下的未知修改。
- 明确本轮任务边界。用户要求完成当前阶段后停止时，不要未经确认扩展到下一阶段。
- 如果已有修改与本任务相关，基于当前文件继续；如果归属不明确，保留原样，并在最终汇总中说明。

---

## 3. 产品参考与实现边界

### 3.1 参考优先级

- 官方 Nanobot WebUI 是 UI、交互和数据语义的主要参考。
- 修改 UI、消息行为、Sidebar、Settings 或会话状态前，优先阅读现有源码、测试和相关文档中的上游路径、参考提交与已验证行为。
- 不要仅凭截图重新设计交互。若上游源码、真实行为和截图不一致，以源码和真实行为为准，并说明差异。
- 不要把局部专项验证描述为“整个应用已经全部测试完成”；最终汇总必须明确验证范围。

### 3.2 真实能力优先

- 新增 UI 前，先确认 Gateway API、HTTP 响应、WebSocket 事件和数据模型确实支持该能力。
- 不伪造后端响应、配对码、Provider 配置、成功状态或不存在的操作。
- 不添加官方参考中不存在的 Android Restart 按钮。
- 不添加官方参考中不存在的 assistant Retry/Regenerate 操作按钮。
- 底层已有能力不等于产品应暴露对应 UI；产品边界必须以官方实现和真实后端能力为准。

### 3.3 已验证行为

以下行为属于敏感关键路径。修改前必须先阅读现有实现和测试，修改后同步更新测试并重新验证：

- 新会话首次发送后的 `SessionSelection` 与 `reconcileSessionSelection`。
- `drafting-new-topic` guard，避免新会话尚未出现在 Sidebar 时被旧会话抢占。
- 恢复 selected session 时，第一次空 Sidebar 的保护。
- Sidebar 会话切换、置顶、取消置顶、归档、显示已归档、恢复和删除。
- Root `SavedStateHandle` 的状态恢复与 logout 清理。
- WebSocket 连接、断开、重连、流式消息和结束事件。
- 应用更新的版本比较、渠道映射、Release 资源选择、APK 下载状态和安装 Intent。

修改这些路径时，应遵循：先稳定复现或写清行为契约，再用单元测试锁定状态转换，最后修改实现并重新运行相关模块测试。编译成功不能替代 UI 或状态行为验证。

---

## 4. 工程结构与依赖方向

主要模块职责：

```text
app/                    应用组合根、导航、Root 状态和 Hilt 组装
core/model/             共享数据模型与序列化模型
core/network/           Gateway HTTP/API 客户端
core/transport/         Gateway WebSocket/实时传输
core/persistence/       本地持久化
core/designsystem/      共享 Compose 设计系统
core/workspace-contract/ 跨 feature 的 Workspace 最小能力契约
feature/auth/           登录与认证
feature/chat/           会话、消息时间线、发送与媒体预览
feature/sidebar/        会话列表及其管理入口
feature/workspaces/     Workspace 管理
feature/settings/       设置、运行状态与应用更新
feature/apps/           Apps 管理
feature/skills/         Skills 管理
feature/automations/    Automations 管理
feature/channels/       Channels 管理
feature/security/       Security 管理
```

依赖方向：

```text
app -> feature/* -> core/*
app -> core/*
feature/* -> core/*-contract
```

必须遵守：

- `core` 不得依赖 `feature` 或 `app`。
- 一个 feature 不应直接读取另一个 feature 的 UI 状态。
- 跨 feature 共享能力时，优先抽取到 `core:<capability>-contract`，只暴露稳定且最小的接口。
- `app` 是组合根，负责把多个 feature 和全局状态组装起来。
- `core:workspace-contract` 是现有契约拆分的参考示例。
- 不为简单功能过度创建空的 `domain/` 或无实际用途的抽象。

需要拆分 feature 职责时，优先采用：

```text
feature/<name>/src/main/java/com/nanobotkt/feature/<name>/
├── data/       Repository、DataSource、远程/本地数据访问实现
├── domain/     独立业务规则；没有业务规则时可以省略
├── ui/         Screen、ViewModel、UI state、UI event
└── di/         Hilt Module 和绑定
```

---

## 5. Kotlin、Compose 与状态管理

- Kotlin/Java 生产代码优先使用不可变数据、纯函数和表达式式写法。
- 若任务涉及 JavaScript/TypeScript，优先采用纯函数、不可变数据和显式数据转换，避免依赖可变共享状态。
- 优先使用 `val`、不可变集合、`data class`、`StateFlow` 和单向数据流；避免可变全局状态。
- Composable 负责渲染和事件转发，不直接承载网络请求、磁盘写入或复杂业务规则。
- 网络、磁盘和 WebSocket 等副作用必须放在 Repository、DataSource、Transport 或明确的 Use Case 中。
- ViewModel 负责 UI 状态编排和事件入口；Repository 负责数据访问、缓存和会话生命周期。
- 对外暴露只读 `StateFlow`，不要暴露 `MutableStateFlow`。
- Hilt Module 放在所属 feature 的 `di/` 中，不要把 feature 专属绑定塞进 `app`。
- UI 模型不要泄漏到 `core` 或其他 feature；跨 feature 只共享能力契约和必要的数据模型。
- 文件包名必须与目录职责一致。
- 修改代码时优先保持范围可解释、过程可验证；若已确认问题跨越多个文件或模块，可以进行必要的系统性重构，但不要夹带与目标无关的重构或全仓库格式化。
- 不以“项目功能已基本完成”“当前代码还能运行”或“避免改动过大”为理由推迟已确认的架构问题；也不以“项目处于早期”为理由进行没有明确收益的重写。

---

## 6. 修改范围、协作与 Agent 委派

- 只修改当前任务必需的文件。
- 修改前后检查 `git diff`，确认没有混入无关变化。
- 发现其他 Agent 或用户的修改时，基于当前内容调整，不要用旧版本覆盖。
- Subagent 只处理范围小、边界明确、容易验收的简单任务；不要为了并行而并行。
- 架构设计、复杂调查、模糊需求、跨模块改造、关键路径集成和最终验证由主 Agent 负责。
- 委派写入任务时必须明确文件或模块所有权，保证不同 Agent 的写入范围不重叠。
- Subagent 返回后必须审查其实际改动和验证结果，不能直接假设正确。
- 最终汇总必须说明是否使用 Subagent、其任务范围以及实际影响。

---

## 7. Git 与敏感信息安全

除非用户明确要求，否则禁止：

- `git reset`
- 用 `git checkout` 覆盖文件
- `git clean`
- 递归删除或移动未知目录
- 改写历史或 force-push
- 自动 push、创建 PR 或修改远程分支

用户明确要求提交时，按以下流程执行：

1. 检查 `git status --short` 和 `git diff`。
2. 只暂存本任务产生的文件。
3. 暂存后检查 `git diff --cached --stat` 与 `git diff --cached --check`。
4. 确认没有凭据、日志、截图、APK、构建缓存或临时文件。
5. 提交后确认工作区状态、提交哈希和提交信息。
6. 未经额外明确要求，不 push。

以下内容禁止进入代码、日志、截图、测试产物或提交：

- bootstrap secret
- Token、Cookie、Session 信息
- Provider API Key
- 渠道凭据、私钥和真实用户数据

发布相关任务必须遵守：

- `docs/RELEASE.md` 是版本递增、Dev 预发布和正式 Release 的唯一操作入口；执行前先完整阅读，不根据 Actions 日志自行猜测流程。
- 只有用户明确要求发布时，才允许运行 `scripts/release.sh`；脚本要求显式传入 `dev` 或 `release`，并校验当前分支分别为 `dev` 或 `main`；通过后才会修改版本文件、更新日志并创建本地提交。
- 不手工制造发布版本提交，不在 GitHub Actions 中修改版本号，也不把 Debug APK 当作可发布升级包。
- 未经用户额外明确要求，不 push、不创建或删除 tag、不创建或删除 GitHub Release，也不覆盖 `dev-latest`。
- 用户显式调用 `$nanobotkt-dev-release`，或明确要求“执行 Dev 收尾发布闭环”时，必须使用项目级 `.codex/skills/nanobotkt-dev-release/SKILL.md`。该请求视为对当前任务范围内代码整理、验证、业务提交、执行 `scripts/release.sh dev`、脚本自动 push、Actions 监控以及 CI 失败修复/提交/push/重触发的明确授权；不授权正式 Release、服务端修改、无关重构、历史改写或手工操作 `dev-latest`。
- Dev 版本准备提交创建后，CI 失败时禁止再次执行 `scripts/release.sh dev`：临时故障重跑原 run 的失败 jobs；代码或 workflow 故障修复并提交后，使用 `workflow_dispatch` 在当前 `dev` HEAD 上沿用同一版本重新构建。

---

## 8. 验证要求

验证范围必须与改动范围匹配。未运行的命令不得声称已通过。

### 8.1 通用检查

```bash
git diff --check
```

### 8.2 App、Root 与全局状态

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain
```

### 8.3 Chat

```bash
./gradlew :feature:chat:compileDebugKotlin :feature:chat:testDebugUnitTest --console=plain
```

### 8.4 Workspaces

```bash
./gradlew :feature:workspaces:compileDebugKotlin :feature:workspaces:testDebugUnitTest --console=plain
```

### 8.5 Settings

```bash
./gradlew :feature:settings:compileDebugKotlin :feature:settings:testDebugUnitTest --console=plain
```

### 8.6 其他模块

修改其他 feature 或 core 模块时，至少运行对应模块的编译和单元测试。例如：

```bash
./gradlew :core:network:testDebugUnitTest --console=plain
./gradlew :feature:channels:compileDebugKotlin :feature:channels:testDebugUnitTest --console=plain
```

### 8.7 模拟器验证

涉及 UI、导航、登录、会话、进程恢复、WebSocket 或真实交互时，条件允许应在 `emulator-5554` 验证：

1. 记录入口、前置条件、操作步骤和预期结果。
2. 安装最新 Debug APK，确认包名和目标服务地址正确。
3. 每次只验证一个小范围，优先无外部副作用的本地功能。
4. 检查截图、UI dump 和 logcat，确认没有泄露凭据。
5. 检查 `FATAL EXCEPTION`、ANR、Force Finish 和进程死亡迹象。
6. 验证真实进程恢复时，先回到 HOME，再使用 `am kill`；确认旧 PID 消失、新 PID 产生，并检查 `LaunchState: COLD`。
7. 记录截图、日志和 UI dump 的绝对路径；是否入库必须单独判断。

---

## 9. Smoke Test 工作方式

进行 Smoke Test 时：

1. 先写清入口、前置条件、步骤和预期结果。
2. 优先验证无外部副作用的本地功能。
3. 每次只处理一个小范围，例如会话归档/恢复或某个 Settings section。
4. 发现问题后先建立稳定复现，再对照官方上游实现。
5. 修复后运行相关单元测试和编译任务。
6. 安装 APK，在模拟器复测并保留必要证据。
7. 涉及真实 Gateway 写操作、Provider、渠道或 Automation 时，执行前后记录状态；完成当前小阶段后停止并汇总。
8. 不伪造不存在的后端响应、配对码、Provider 配置或测试结果。

`docs/SMOKE_TEST.md` 用于记录已执行的专项验证。除非当前任务涉及测试记录，否则不要为了更新日期而重写历史内容。

---

## 10. 最终汇总要求

完成任务后，最终回复必须说明：

- 本轮完成了什么，以及明确没有做什么。
- 修改了哪些文件或模块。
- 运行了哪些构建、单元测试、静态检查和模拟器验证。
- 哪些内容未验证、被外部条件阻塞或仍有风险。
- 是否产生真实 Gateway、设备或其他外部副作用。
- 是否 stage、commit、push，以及提交哈希。
- 是否使用 Subagent，以及其任务和实际影响。

如果当前阶段已经完成，不要在汇总后继续下一阶段。可以提出下一步建议，但不得未经用户确认自动执行。
