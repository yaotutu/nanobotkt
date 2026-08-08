# NanobotKT Agent 工作规范

本文件适用于在以下 Android 项目中工作的所有 Agent：

```text
/Users/yaotutu/Desktop/code/nanobotkt
```

Windows 同步工作区：

```text
C:\Users\Administrator\AndroidStudioProjects\nanobotkt
```

> 本文件只约束 NanobotKT 客户端工程，不自动约束同级的 Nanobot 服务端工程。

## 用户手工维护规则（优先级最高，禁止 Agent 修改）

- 在 JavaScript/TypeScript 中，优先使用函数式编程。
- 添加详细的中文注释。
- Nanobot 服务端源码固定在 `/Users/yaotutu/Desktop/code/nanobot`。

---

## 1. 项目定位与参考资料

### 1.1 产品和交互参考

- 官方 Nanobot WebUI 是 UI、交互和数据语义的主要参考标准。
- 修改 UI、消息行为、Sidebar、Settings 或会话状态前，优先阅读 `HANDOFF.md` 中记录的上游路径、参考提交和已验证行为。
- 不要仅凭截图重新设计交互；如果上游源码和截图不一致，优先以源码和真实行为为准。
- 不要把局部专项验证描述成“整个应用已经全部测试完成”。最终汇总必须明确验证范围。

### 1.2 当前工程模块

当前模块大致分为三层：

```text
app/                    应用组合根、导航、Root 状态和 Hilt 组装
core/model/             共享数据模型和序列化模型
core/network/           Gateway HTTP/API 客户端
core/transport/         Gateway WebSocket/实时传输
core/persistence/       本地持久化
core/designsystem/      共享 Compose 设计系统
core/testing/           测试辅助能力
core/*-contract/        跨 feature 的最小能力契约
feature/*/              独立业务功能及其 UI、状态、数据访问和 DI
```

依赖方向原则：

```text
app -> feature/* -> core/*
app -> core/*
feature/* -> core/*-contract
```

- `core` 不得依赖 `feature` 或 `app`。
- 一个 feature 不应为了读取另一个 feature 的 UI 状态而直接依赖另一个 feature。
- 跨 feature 共享能力应优先抽取到 `core:<capability>-contract`，只暴露稳定的最小接口。
- `app` 是组合根，可以依赖多个 feature，并负责把页面和全局状态组装起来。
- 当前已经存在的 `core:workspace-contract` 是上述契约拆分的示例。

### 1.3 服务端源码

Nanobot 服务端源码位置由用户手工维护，禁止修改本条路径：

```text
/Users/yaotutu/Desktop/code/nanobot
```

除非用户明确要求，否则不要修改、格式化、重置或清理该目录。

---

## 2. 每次任务开始前

先读取交接信息并确认 Git 实时状态：

```bash
cat HANDOFF.md
git status --short
git log -1 --oneline --decorate
git rev-parse HEAD
git rev-parse origin/main
```

必须遵守：

1. `HANDOFF.md` 是背景和历史验证的参考，不是实时 Git 状态；Git 命令输出优先。
2. 先识别工作区已有修改的来源和范围，再开始编辑。
3. 不覆盖、回退或删除用户、其他 Agent 或上一阶段留下的未知修改。
4. 明确当前任务边界；用户要求“完成当前阶段后停止”时，完成验证和汇总后停止，不顺手扩展下一阶段。
5. 如果已有未提交修改与本任务相关，可以在确认归属后继续；如果归属不明确，先保留并在最终汇总中说明。

---

## 3. 架构与代码规范

### 3.1 Kotlin / Compose

- Kotlin/Java 生产代码优先使用不可变数据、纯函数和表达式式写法。
- 优先使用 `val`、不可变集合、`data class`、`StateFlow` 和单向数据流；避免可变全局状态。
- 网络、磁盘、WebSocket 等副作用必须放在 Repository、DataSource、Transport 或明确的 Use Case 中，不要直接写入 Composable。
- Composable 负责渲染和事件转发；不要在 Composable 中堆积业务规则、网络请求或复杂状态同步。
- ViewModel 负责 UI 状态编排和事件入口；Repository 负责数据访问、缓存和会话生命周期。
- 对外暴露状态时优先暴露只读 `StateFlow`，不要暴露 `MutableStateFlow`。
- 新增或修改 Kotlin/TypeScript 代码时，添加足够详细的中文注释，重点解释状态转换、竞态保护、接口边界和非直观的业务规则；不要为显而易见的代码添加噪音注释。

### 3.2 Feature 内部目录

Feature 需要拆分职责时，优先采用以下结构：

```text
feature/<name>/src/main/java/com/nanobotkt/feature/<name>/
├── data/       Repository、DataSource、远程/本地数据访问实现
├── domain/     仅在存在独立业务规则时使用，避免为简单功能过度抽象
├── ui/         Screen、ViewModel、UI state、UI event
└── di/         Hilt Module 和绑定
```

- 小型 feature 可以保留扁平目录，不为了形式强行创建空的 `domain/`。
- 文件包名必须与目录职责一致。
- Hilt Module 应放在所属 feature 的 `di/` 中；不要把 feature 专属绑定塞进 `app`。
- UI 模型不要泄漏到 `core` 或其他 feature；跨 feature 只共享能力契约和必要的数据模型。
- 不保留死代码、无效参数、临时兼容分支或“以后可能用到”的抽象。

### 3.3 状态和会话规则

开发阶段允许直接重构，不要求保留旧实现、旧数据格式、旧接口或旧 UI 语义的兼容层。

但以下行为已有专项验证，修改时必须先阅读现有实现和测试，并同步更新测试：

- 新会话首次发送后的 `SessionSelection` 和 `reconcileSessionSelection`。
- `drafting-new-topic` guard，避免新会话尚未出现在 Sidebar 时被旧会话抢占。
- 恢复 selected session 时穿过第一次空 Sidebar 的保护。
- Sidebar 会话切换、置顶、取消置顶、归档、显示已归档、恢复和删除。
- Root `SavedStateHandle` 的状态恢复和 logout 清理。
- WebSocket 连接、断开、重连、流式消息和结束事件。

重构这些行为时：

1. 先建立稳定复现或明确行为契约。
2. 用单元测试锁定关键状态转换。
3. 再修改实现并重新运行相关模块测试。
4. 不把“编译成功”当作 UI 或状态行为验证。

### 3.4 产品边界

除非用户明确要求并且已经核对上游源码和后端能力，否则：

- 不添加假的 Android Restart 按钮。
- 不添加上游不存在的 assistant Retry/Regenerate 操作按钮。
- 不为了视觉“看起来完整”而伪造后端能力或写死成功状态。
- 新增 UI 前先确认真实 Gateway API、Transport 事件和数据模型是否支持。

---

## 4. 修改范围与协作

- 只修改当前任务需要的文件；不要顺手进行全仓库格式化。
- 发现其他 Agent 或用户的修改时，基于当前文件内容调整，不要用旧版本覆盖。
- 修改文件前后都要关注 `git diff`，确保没有混入无关变化。
- 需要并行工作时，只委派边界明确、写入范围不重叠、可以独立验收的任务。
- 架构设计、跨模块重构、关键路径集成和最终验证由主 Agent 负责。
- 子任务返回后，主 Agent 必须审查实际改动和验证结果，不能直接假设正确。

---

## 5. Git 安全规则

除非用户明确要求，否则：

- 不执行 `git reset`。
- 不执行 `git checkout` 覆盖文件。
- 不执行 `git clean`。
- 不递归删除或移动未知目录。
- 不改写历史，不 force-push。
- 不自动 push、创建 PR 或修改远程分支。

当用户明确要求提交时：

1. 先检查 `git status --short` 和 `git diff`。
2. 只暂存当前任务明确产生的文件。
3. 暂存后检查 `git diff --cached --stat` 和 `git diff --cached --check`。
4. 确认没有凭据、日志、截图、APK、构建缓存或临时文件后再提交。
5. 提交后确认 `git status --short`、提交哈希和提交信息。
6. 未经额外明确要求，不 push。

敏感信息禁止进入代码、日志、截图和提交：

- bootstrap secret
- Token、Cookie、Session 信息
- Provider API Key
- 渠道凭据、私钥和真实用户数据

---

## 6. 验证要求

验证范围必须与改动范围匹配。未运行的命令不得声称已通过。

### 6.1 通用检查

```bash
git diff --check
```

### 6.2 App / Root / 全局状态

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain
```

### 6.3 Chat

```bash
./gradlew :feature:chat:compileDebugKotlin :feature:chat:testDebugUnitTest --console=plain
```

### 6.4 Workspaces

```bash
./gradlew :feature:workspaces:compileDebugKotlin :feature:workspaces:testDebugUnitTest --console=plain
```

### 6.5 Settings

```bash
./gradlew :feature:settings:compileDebugKotlin :feature:settings:testDebugUnitTest --console=plain
```

### 6.6 其他模块

修改其他 feature 或 core 模块时，至少运行对应模块的编译和单测任务。例如：

```bash
./gradlew :core:network:testDebugUnitTest --console=plain
./gradlew :feature:channels:compileDebugKotlin :feature:channels:testDebugUnitTest --console=plain
```

### 6.7 模拟器验证

涉及 UI、导航、登录、会话、进程恢复、WebSocket 或真实交互时，条件允许应在 `emulator-5554` 验证：

1. 记录入口、前置条件、操作步骤和预期结果。
2. 安装最新 Debug APK，确认包名和目标服务地址正确。
3. 每次只验证一个小范围，优先无外部副作用的本地功能。
4. 检查截图、UI dump 和 logcat，不泄露凭据。
5. 检查是否有 Fatal Exception、ANR 或 Force Finish。
6. 验证真实进程恢复时，使用 HOME 后的 `am kill`，确认 PID 消失、PID 改变以及 `LaunchState: COLD`。
7. 记录截图、日志和 UI dump 的绝对路径；临时产物是否入库必须单独判断。

---

## 7. Smoke Test 工作方式

进行 Smoke Test 时：

1. 先写清楚入口、前置条件、步骤和预期结果。
2. 优先验证无外部副作用的本地功能。
3. 每次只处理一个小范围，例如会话归档/恢复或某个 Settings section。
4. 发现问题后先得到稳定复现，再对照官方上游实现。
5. 修复后运行相关单测和编译。
6. 安装 APK，在模拟器复测并保留必要证据。
7. 涉及真实 Gateway 写操作、Provider、渠道或 Automation 时，执行前后记录状态；完成当前小阶段后停止并汇总。
8. 不伪造不存在的后端响应、配对码、Provider 配置或测试结果。

`SMOKE_TEST.md` 用于记录已执行的专项验证。除非当前任务涉及测试记录，否则不要为了“更新日期”而重写历史内容。

---

## 8. 交接与最终汇总

完成任务后，最终回复必须说明：

- 本轮完成了什么，以及没有做什么。
- 修改了哪些文件或模块。
- 运行了哪些构建、单测、静态检查和模拟器验证。
- 哪些内容未验证、被外部条件阻塞或仍有风险。
- 是否产生真实 Gateway、设备或其他外部副作用。
- 是否 stage、commit、push，以及提交哈希。
- 是否使用了 Subagent；说明其任务和实际影响。

如果当前阶段已经完成，不要在汇总后继续下一阶段。

如有下一步建议，只列出建议，不要未经用户确认自动执行。
