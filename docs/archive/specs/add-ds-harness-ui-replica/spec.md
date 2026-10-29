# 复刻 DeepSeek Harness 截图 UI Spec

## Why

用户提供 DeepSeek Harness 桌面端 + 移动端截图，要求把 web 端 UI 复刻到与截图一致。当前 web 端已是 DeepSeek 形态骨架，但侧栏结构、顶栏、消息操作栏、Composer 内部布局、底部状态栏与截图仍有明显差异。

## 设计基准（来自截图，brief 优先级最高）

桌面端（截图 1）：
- 侧栏：`鹿logo + deepseek + [HARNESS] 描边徽章` brand 行 → 浅色左对齐「⊕ 新会话」→「工作区」标签行（右侧搜索/筛选/新建三图标，装饰位）→ 会话项（仅名称 + 右侧灰色相对时间，无摘要无状态灯）→ 底部用户头像区
- 主区顶栏：会话名 + 「◈ 标准模式」副标签；右上角「Session log ↓」描边胶囊；下方「对话 / 轨迹」tab（当前项蓝色下划线）
- 消息：assistant 无气泡直接排版（现状已一致）；操作栏为 复制/赞/踩/分享 图标组，灰色常显
- Composer：大圆角白卡片；textarea 左上角浮「⊕」；底部控制行左「🛡 Workspace Write ▾」右「模型名 ▾ + 空心圆 + 蓝色↑」
- 底部状态栏：分段式 `1 轮·3 步 │ LLM 1m36s │ 首 token 0.8s·71 tok/s │ 缓存命中 62% │ 输入/输出 tok`，mono 小字

移动端（截图 2）：
- 左侧固定**图标竖条栏**（约 64px：logo / ⊕新会话 / 历史册 / 搜索 / 底部设置），不随内容滚动
- 内容区顶栏：会话名截断 + 模式标签 + 「…」菜单 + 侧栏开关图标
- 侧栏会话列表仍以抽屉滑出（叠加在图标栏右侧）

配色/字体沿用现有 token（DS 蓝 #4D6BFE、双主题、mono 数据语义），截图为浅色主题，dark 模式下同构映射到现有夜间 token。

## What Changes

- 侧栏重构：brand 行加 HARNESS 徽章；「新会话」改浅底左对齐图标式；新增「工作区」图标装饰行；会话项改「名称 + 相对时间」（去摘要与状态灯）；底部改用户区（头像占位 + 名称）
- 顶栏重构：会话名 + 「◈ 标准模式」标签；右侧 Session log 描边胶囊（下载当前会话文本，功能可用）；下方「对话/轨迹」tab（轨迹 tab 占位，切换显示「轨迹功能开发中」）
- 消息操作栏：统一为常显灰色图标组（复制/赞/踩/分享 + 相对时间），赞/踩/分享为前端占位（点击轻提示）；user 行保留删除
- Composer 重排：textarea 左上「⊕」（附件占位）；控制行改 左「🛡 Workspace Write ▾」（占位菜单）+ 右「**AgentSelect 保持现状（Agent 切换，不换皮不改名）** + 空心圆状态灯 + 发送键」
- 底部状态栏：改分段式 mono 布局（轮数/步数、耗时、token 量取真实可得数据，无数据分段隐藏）
- 移动端（≤768px）：新增左侧图标竖条栏；会话抽屉从图标栏右侧滑出；内容顶栏改移动形态（…菜单占位 + 侧栏钮）
- **BREAKING**: 无（纯 UI 层改动，不涉及 API 与数据结构）

## Impact

- Affected specs: add-self-contained-web-mvp（web UI 能力扩展）
- Affected code:
  - `web/src/components/SessionList.vue`（brand/新会话/工作区行/会话项/用户区）
  - `web/src/components/ChatWindow.vue`（tab 行、Session log）
  - `web/src/components/MessageBubble.vue`（操作栏图标组与常显样式）
  - `web/src/components/Composer.vue`（⊕、shield 菜单、控制行重排）
  - `web/src/App.vue`（移动端图标栏、顶栏结构、tab 状态）
  - `web/src/styles/main.css`（以上全部样式 + 相对时间等）
  - 新增 `web/src/utils/relativeTime.ts`（相对时间格式化）

## ADDED Requirements

### Requirement: 侧栏对齐截图
侧栏 SHALL 呈现：brand 行（logo + deepseek + HARNESS 徽章）、左对齐「⊕ 新会话」、「工作区」标签行（右侧三图标）、会话项（名称 + 右侧相对时间）、底部用户区（头像占位）。

#### Scenario: 会话项显示相对时间
- **WHEN** 会话列表渲染
- **THEN** 每项右侧显示灰色相对时间（如「1分钟」「3小时」「2天」）；无时间数据时不显示

#### Scenario: HARNESS 徽章
- **WHEN** brand 行渲染
- **THEN** logo 右侧依次为「deepseek」字样与描边 mono 小字「HARNESS」徽章

### Requirement: 顶栏对话/轨迹 tab 与 Session log
主区顶栏 SHALL 含：会话名 + 「◈ 标准模式」标签、右侧「Session log ↓」描边胶囊、下方「对话/轨迹」tab（当前项 accent 下划线）。

#### Scenario: Session log 下载
- **WHEN** 点击 Session log
- **THEN** 下载当前会话的纯文本记录（user/assistant 逐行），无会话时置灰

#### Scenario: 轨迹 tab 占位
- **WHEN** 切到「轨迹」tab
- **THEN** 显示占位内容（「轨迹功能开发中」），对话内容隐藏

### Requirement: 消息操作栏常显图标组
assistant 消息操作栏 SHALL 为常显灰色图标组：复制、赞、踩、分享 + 右侧相对时间；user 消息保留复制/删除。

#### Scenario: 占位交互
- **WHEN** 点击赞/踩/分享
- **THEN** 顶栏轻提示「功能开发中」，不发起请求

### Requirement: Composer 对齐截图
Composer SHALL 呈现：textarea 左上「⊕」按钮（附件占位）；控制行左侧「🛡 Workspace Write ▾」占位菜单；右侧保持现有 **Agent 下拉（AgentSelect，不做换皮改名）**、空心圆状态灯、蓝色圆形发送键。

#### Scenario: Agent 切换保持现状
- **WHEN** 点击右侧 Agent 下拉并选择
- **THEN** 行为与现有 AgentSelect 完全一致（按 agent 真实主键绑定会话），不改为模型切换、不改触发器文案与样式语义

#### Scenario: 占位菜单
- **WHEN** 点击 ⊕ 或 Workspace Write
- **THEN** 顶栏轻提示「功能开发中」

### Requirement: 分段式底部状态栏
状态栏 SHALL 以 mono 分段（│ 分隔）呈现：轮数/步数、LLM 耗时、token 量等；**仅显示有真实数据的分段**，无数据的分段整段隐藏。

#### Scenario: 空会话
- **WHEN** 会话无消息
- **THEN** 状态栏不渲染（与现状一致）

### Requirement: 移动端图标竖条栏
≤768px SHALL 呈现左侧固定图标竖条栏（logo/新会话/历史/搜索/设置占位），会话列表以抽屉从其右侧滑出；内容区顶栏为移动形态。

#### Scenario: 抽屉联动
- **WHEN** 窄屏点击图标栏「历史」
- **THEN** 会话抽屉滑出；选中会话后自动收回（现有行为保留）

### Requirement: 双主题与可达性不回退
全部新样式 SHALL 适配现有双主题 token；交互控件 SHALL 保留键盘可达（focus 态）与 prefers-reduced-motion 降级。

#### Scenario: 主题切换
- **WHEN** 切换日间/夜间
- **THEN** 新增区块（徽章/tab/状态栏/图标栏）配色随之切换，无硬编码色值
