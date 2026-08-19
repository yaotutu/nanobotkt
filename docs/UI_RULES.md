# NanobotKT UI Rules

> 状态：v0.1（2026-08-18）

## 1. 规范边界

NanobotKT Android 客户端默认遵循 Material Design 3。Material 3 已定义的基础组件行为、交互状态、无障碍要求、触控目标、Elevation、导航和平台返回规则不在本文重复说明。

本文只定义 NanobotKT 在 Material 3 之上的产品级选择、业务语义扩展、组件组合和明确例外。本文未覆盖的内容以 Material 3 官方规范为准；官方 Nanobot WebUI 主要作为能力边界、信息语义和真实交互的参考，不直接复制其桌面布局。

## 2. 产品视觉方向

NanobotKT 采用 **Quiet Technical（安静的技术型 AI 助手）** 方向：

- Chat 以内容阅读为中心，系统活动不得压过正式回答。
- Settings 是清晰的系统面板，正常状态保持低强调，异常状态才提升视觉权重。
- 会话列表是高效切换器，优先保证扫描速度和首屏信息量。
- Apps、Skills、Automations、Channels、Security、Workspaces 使用统一的能力列表与配置语言，避免工程后台感。
- 层级优先通过排版、留白、对齐和 tonal surface 建立，不通过重复 Card、阴影或装饰建立。

## 3. Theme 选择

- 默认使用 NanobotKT 固定品牌 Color Scheme，保证不同设备上的产品辨识度和视觉验证稳定性。
- Dynamic Color 保留为显式可选能力，不作为默认值。
- 品牌强调采用克制的深靛蓝；页面 Canvas 和普通 Surface 使用低彩度冷中性色。
- 品牌色用于主要操作、当前选中和运行态，不承担成功、警告、错误等全部业务语义。
- Shape 采用 Material 3 中等圆角倾向；`extraLarge` 只用于 Bottom Sheet、Dialog 或大型独立容器。
- 正文使用系统无衬线字体；中文正文降低不必要的字距，长回答通过行高和段落间距组织。

## 4. 业务状态映射

| Nanobot 状态 | 产品语义 | 默认表达 |
| --- | --- | --- |
| Gateway 已连接、能力可用 | Success | 小型状态点或状态文字，不使用大面积品牌容器 |
| Agent/Automation 运行中 | Active | `primary` 语义，可使用克制的进度动画 |
| 正在连接、等待配置 | Warning | 警告状态文字或状态标识 |
| 断开、失败、校验错误 | Error | `error` 语义，并提供恢复路径 |
| 当前会话、当前 Workspace | Selected | 轻量 tonal 背景或左侧指示条 |
| 已置顶、已归档、版本等元数据 | Neutral | 次级文字或小图标，不默认使用 Chip |

颜色不能成为状态的唯一表达；必须同时提供文字、图标或形状差异。

## 5. 组件分类

### A. 直接使用 Material 3

Button、IconButton、TextField、Checkbox、Switch、Dialog、Snackbar、TopAppBar、ModalBottomSheet、DropdownMenu、ProgressIndicator 等，在 Material 3 已完整覆盖需求时直接使用，不创建无产品语义的 `NanobotButton`、`NanobotSwitch` 等包装。

### B. Material 3 的固定产品组合

以下重复模式由 `core:designsystem` 提供统一组合：

- Section Header
- Navigation Row
- Status Label
- Summary Surface
- Empty/Error State

这些组件只固定 NanobotKT 的内容层级、状态映射和间距，不重新实现 Material 3 的基础交互。

### C. Feature 专属业务组件

Chat Composer、User Message、Assistant Message、Agent Activity、Conversation Row、Gateway Summary 等保留在所属 Feature。只有跨 Feature 的稳定产品语义才进入 `core:designsystem`，禁止为了统一命名上移所有业务组件。

## 6. 页面组合规则

### Settings

- Gateway 使用紧凑状态摘要，不作为 Hero Banner。
- 普通入口使用平面 Navigation Row；Section 默认依赖留白和 Divider，不为每组内容建立大圆角 Card。
- “管理”是主要入口；“重新连接”是低频恢复操作，正常连接时保持较低强调。
- 普通入口图标默认不带彩色方形容器。

### Conversation Sheet

- 搜索和标题区域保持紧凑，列表承担主要高度。
- 会话摘要默认一行；选中态使用 inset tonal surface/指示语义，不使用满宽高强调填充。
- 置顶、运行、未读和更多操作使用轻量元数据，不挤压标题。

### Chat

- Assistant 内容保持平面阅读流；用户内容允许使用 tonal bubble。
- Markdown 标题属于消息内容，不得强于页面标题。
- Agent Activity 完成后降低视觉权重，与正式回答保持清晰区分。

### Capability Pages

- Apps、Skills、Automations、Channels 等默认使用平面列表，不为每个条目套 `ElevatedCard`。
- 状态优先使用统一 Status Label；只有需要筛选或切换的状态才使用 Chip。
- Loading、Empty、Error 必须是不同状态；错误状态提供恢复入口。

## 7. 禁止事项与例外

### MUST

- Feature 不得定义新的品牌色、全局 Shape 等级或全局排版等级。
- Nanobot 业务状态必须映射到统一产品语义。
- 新页面优先使用已有 Material 组件、产品组合和页面模式。
- 新增跨 Feature 组件时必须说明 Material 3 或已有组合为什么不足。

### SHOULD NOT

- 不为 Material 3 基础组件增加无语义包装。
- 不使用多层带色 Card 嵌套。
- 不把品牌色用于全部状态和全部图标背景。
- 不为了“AI 感”默认增加渐变、发光或高 Elevation。
- 不创建带大量 Boolean 参数的万能组件。

真实产品需求允许例外，但应有明确理由；稳定的跨页面例外需要回写本文，单一 Feature 的业务例外保留在所属模块。

## 8. 当前实现映射

| 产品模式 | 当前落地 |
| --- | --- |
| Settings Section | `SettingsGroup` 使用 Section Header + 平面内容组 |
| Settings Entry | `SettingsRow` 复用 Navigation Row；Provider 标识保留 Feature 扩展 |
| Gateway Summary | 保留为 Settings 专属紧凑摘要，不再使用 Hero Card |
| Conversation Row | 保留为 Chat 专属紧凑 Selectable Row |
| Capability List | Apps、Skills、Automations、Channels 使用平面列表或 Navigation Row |
| Status | Success、Warning、Active、Error、Neutral 使用统一业务语义 |
| Page State | Apps、Skills、Automations、Channels、Security、Workspaces 复用 Empty/Error State |

## 9. 规范落地顺序

1. 先盘点页面调用，把需求分为 Material 3 基础组件、跨 Feature 产品组合和 Feature 专属组件。
2. 先统一调用方式：重复产品模式必须收敛到已有组合，禁止在各页面分别调颜色和圆角“修好看”。
3. 再统一 Theme：颜色、Typography、Shape 和状态扩展只在 `core:designsystem` 调整，由统一组件调用自然传播。
4. 最后进行 Light/Dark、关键页面和真实状态视觉验证；发现问题优先修正规则或组合，而不是添加页面私有补丁。
5. 新增稳定的非 Material 规则时先更新本文，再修改组件；单一业务例外只留在所属 Feature，并写明原因。

## 10. 新页面检查清单

1. 真实 Gateway/API 是否支持页面展示的能力？
2. 页面是否可以使用现有 Material 3 组件直接完成？
3. 页面属于 Chat、Settings、Conversation、Capability List 或 Configuration Detail 中哪一种模式？
4. 是否复用了已有状态语义与产品组合？
5. 是否新增了 Feature 私有颜色、Shape、排版或任意间距？
6. Loading、Empty、Error 和正常内容是否明确区分？
7. 如果新增组件，它是跨 Feature 稳定语义还是 Feature 专属业务组件？
8. Light、Dark、大字体和紧凑密度下是否仍然可读？
