# NanobotKT Agent 工作规则

本文件适用于在 `C:\Users\Administrator\AndroidStudioProjects\nanobotkt` 中工作的所有 Agent。

## 1. 开始任务前

每次接手任务时，先执行：

```powershell
Get-Content -LiteralPath .\HANDOFF.md -Encoding UTF8 -Raw
git status --short
git log -1 --oneline --decorate
git rev-parse HEAD
git rev-parse origin/main
```

规则：

- `HANDOFF.md` 提供项目背景、已完成范围、验证证据和建议下一步。
- Git 分支、HEAD、remote 和工作区状态必须以实时命令输出为准；交接文档中的 Git 快照可能已经过期。
- 开始修改前先确认当前用户要求的阶段边界，不要自动扩展到下一阶段。
- 如果用户要求“完成当前阶段后停止”，完成该阶段、验证、汇总，然后停止，不继续顺手开发。

## 2. 当前项目方向

- 官方 Nanobot WebUI 是 UI、交互和数据语义的主要标准。
- 官方上游路径和当前参考提交记录在 `HANDOFF.md`。
- 修改 UI 或消息行为前，优先阅读上游对应组件，不要仅凭截图重新设计。
- 当前建议工作方向是系统化 Smoke Test、记录稳定复现步骤和最小修复，而不是扩大 UI 重写范围。
- 不要把已完成的专项验证描述为“整个应用已经全部测试完成”。

## 3. 必须保留的业务行为

除非已有明确、稳定、可复现的 Bug，否则不要重写：

- 新会话首次发送后的 `SessionSelection` / `reconcileSessionSelection` 规则。
- drafting-new-topic guard。
- 恢复 selected session 时穿过第一次空 Sidebar 的保护。
- Sidebar 的会话切换、置顶、取消置顶、归档、显示已归档、恢复和删除逻辑。
- Root `SavedStateHandle` 状态恢复和 logout 清理逻辑。

官方功能边界：

- 不添加假的 Android Restart 按钮。
- 不为了“官方对齐”添加上游不存在的 assistant Retry/Regenerate 操作按钮。
- Android 已有的底层能力可以保留，但新增 UI 前必须核对上游源码和现有后端能力。

## 4. 修改范围

- 优先做最小、局部、可验证的改动。
- 不批量格式化无关文件。
- 不在修复一个问题时顺便重构相邻模块。
- 不覆盖用户或其他 Agent 的修改。
- 如果发现与当前任务无关的问题，记录在汇总中，不立即扩大范围。
- 创建测试文档、截图或临时调试产物时，先确认它们是否应进入仓库。

## 5. Git 安全

未经用户明确要求：

- 不执行 `git reset`。
- 不执行 `git checkout` 来覆盖文件。
- 不执行 `git clean`。
- 不递归删除或移动未知目录。
- 不 stage、commit、push 或创建 PR。
- 不改写历史，不 force-push。

如果工作区不是干净的：

- 先识别已有修改的来源和范围。
- 不回退、不清理、不覆盖未知修改。
- 只编辑当前任务拥有的文件。
- 最终汇总中明确说明哪些修改是本轮产生的。

## 6. 验证要求

根据改动范围执行最小充分验证。

### App / Root 状态

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain
```

### Chat

```powershell
.\gradlew.bat :feature:chat:compileDebugKotlin :feature:chat:testDebugUnitTest --console=plain
```

### Settings

```powershell
.\gradlew.bat :feature:settings:compileDebugKotlin :feature:settings:testDebugUnitTest --console=plain
```

### 通用检查

```powershell
git diff --check
```

要求：

- 生产代码改动必须至少编译对应模块。
- 业务规则改动应增加或更新单元测试。
- UI 行为不能只依赖编译成功；条件允许时安装 APK 并在 `emulator-5554` 验证。
- 需要验证真实进程恢复时，使用 HOME 后的 `am kill`，并确认 PID 消失、PID 改变和 `LaunchState: COLD`。
- 检查 logcat 是否有应用 Fatal Exception 或 ANR。
- Windows 工作区的 LF → CRLF warning 可以记录，但不能忽略真正的 whitespace error。
- 不得伪造测试结果；未运行的项目明确标记为未验证。

## 7. 模拟器与外部副作用

默认调试目标记录在 `HANDOFF.md`，通常为：

```text
emulator-5554
package com.nanobotkt.debug
ADB reverse tcp:8765 -> tcp:8765
```

规则：

- 安装 APK 优先使用 `adb install -r`，避免无意清除数据。
- 执行清数据、删除会话、保存远程配置、安装 App、触发 Automation 或调用真实 Provider 前，先评估外部副作用。
- 测试 Settings dirty 状态时，除非测试目标就是保存，否则不要点击 Save。
- 不输出或记录 bootstrap secret、Token、Provider API Key、渠道凭据等敏感数据。
- 不在截图、UI dump、logcat 摘要或最终回复中泄露凭据。

## 8. Subagent 规则

用户明确授权主 Agent 在本仓库中使用 Subagent，无需每次单独等待授权。

### 何时使用

当存在两个以上可并行的独立子任务，或审查/测试可与实现并行时，使用 Subagent。

适合委派：

- 只读仓库扫描
- 上游参考定位
- 配置摘要
- 测试缺口清单
- 日志分类
- 独立风险审查
- 范围清晰、写入文件不重叠的局部代码修改

不适合委派：

- 主 Agent 下一步立即依赖的阻塞任务
- 紧密耦合的架构决策
- 最终集成
- 最终验证
- 与主 Agent 重复的同一工作

### 角色选择

- `explorer`：只读代码库探索和明确问题定位。
- `worker` / `code_worker`：边界清晰的代码修改；必须指定文件所有权，多个 Worker 的写入范围不得重叠。
- `review_test_worker`：代码审查、测试覆盖、失败分析、日志和回归风险检查；默认不修改生产代码。
- `minimax_visual`：需要理解截图、图片或含图 PDF 的简单视觉检查。
- `default`：其他独立分析任务。

不要引用当前工具列表中不存在的角色名称。

### 委派要求

- 启动前简短说明使用哪个 Agent、具体做什么。
- 任务必须范围窄、输入明确、交付物具体。
- 告知代码修改 Worker：它不是仓库中唯一工作的 Agent，不得回退他人修改。
- Subagent 运行期间，主 Agent 继续处理不重叠的工作，不要无意义等待。
- 收到结果后由主 Agent 审查、整合并关闭 Subagent。
- 最终回复中说明使用了哪些 Subagent、任务是什么、结果如何影响最终工作。
- 小型、线性、紧密耦合任务不必启动 Subagent。

## 9. Smoke Test 工作方式

进行 Smoke Test 时：

1. 先记录入口、前置条件、步骤和预期结果。
2. 优先测试无外部副作用的本地功能。
3. 每次只处理一个小范围，例如会话归档/恢复或某个 Settings section。
4. 发现问题后先得到稳定复现，再对照官方上游。
5. 做最小修复并运行相关测试。
6. 安装 APK，在模拟器复测并保留截图或 UI dump 路径。
7. 完成当前小阶段后停止并汇总，再由用户决定是否继续。

## 10. 最终汇总

最终回复至少说明：

- 本轮完成了什么。
- 修改了哪些文件。
- 运行了哪些构建、单测和模拟器验证。
- 哪些项目没有验证或仍有风险。
- 是否产生外部副作用。
- 是否 stage、commit、push。
- Subagent 的使用情况和实际影响。

如果当前阶段已经完成，不要在汇总后继续下一阶段。
