# Tasks

- [ ] Task 1: 基础工具与样式 token
  - [ ] 1.1 新增 `web/src/utils/relativeTime.ts`（相对时间格式化：刚刚/N分钟/N小时/N天，含单测口径注释）
  - [ ] 1.2 `main.css` 调整/新增区块样式前先核对双主题 token（不新增硬编码色值）
- [ ] Task 2: 侧栏重构（SessionList.vue + main.css）
  - [ ] 2.1 brand 行：logo + 「deepseek」+ 描边 mono「HARNESS」徽章
  - [ ] 2.2 「新会话」改浅底左对齐图标式（⊕ + 文字，无描边）
  - [ ] 2.3 新增「工作区」标签行（右侧搜索/筛选/新建三图标，装饰占位）
  - [ ] 2.4 会话项改「名称 + 右侧相对时间」（去 lastQuestion 摘要与状态灯）
  - [ ] 2.5 底部改用户区（圆形头像占位 + 「我」名称占位）；删除入口保留
- [ ] Task 3: 顶栏与 tab（ChatWindow.vue + App.vue + main.css）
  - [ ] 3.1 顶栏：会话名 + 「◈ 标准模式」标签；右侧「Session log ↓」描边胶囊（下载当前会话纯文本；空会话置灰）
  - [ ] 3.2 顶栏下方「对话/轨迹」tab 行：当前项 accent 下划线；轨迹 tab 显示占位
- [ ] Task 4: 消息操作栏（MessageBubble.vue + main.css）
  - [ ] 4.1 assistant 操作栏改常显灰色图标组：复制/赞/踩/分享 + 相对时间；赞踩分享点击轻提示占位
  - [ ] 4.2 user 消息保留复制/删除（样式对齐常显）
- [ ] Task 5: Composer 重排（Composer.vue + main.css）
  - [ ] 5.1 textarea 左上「⊕」附件占位钮
  - [ ] 5.2 控制行左侧「🛡 Workspace Write ▾」占位（点击轻提示）
  - [ ] 5.3 AgentSelect 保持现状（不换皮不改名），仅在其右侧补空心圆状态灯（生成中呼吸/空闲静默，沿用现有 status-dot token）
- [ ] Task 6: 分段式状态栏（ChatWindow.vue + main.css）
  - [ ] 6.1 状态栏改 mono 分段式：消息轮数、最近一次流式耗时、token 估算量；无数据分段隐藏
- [ ] Task 7: 移动端图标竖条栏（App.vue + main.css）
  - [ ] 7.1 ≤768px 新增左侧固定图标栏（logo/⊕新会话/历史/搜索/设置，均接现有行为或占位）
  - [ ] 7.2 抽屉改从图标栏右侧滑出（left 偏移调整）；内容顶栏移动形态（…菜单占位 + 侧栏钮）
  - [ ] 7.3 reduced-motion 与键盘 focus 态不回退
- [ ] Task 8: 验证
  - [ ] 8.1 `npm run typecheck` + `npm run build` 通过
  - [ ] 8.2 浏览器桌面端对照截图逐区块检查（brand/新会话/工作区/会话项/顶栏 tab/Session log/操作栏/Composer/状态栏）
  - [ ] 8.3 浏览器窄屏(375px)检查图标栏 + 抽屉 + 移动顶栏；双主题切换无硬编码色残留

# Task Dependencies

- Task 1 为基础先行；Task 2/3/4/5 可并行（各自独立组件）；Task 6 依赖 Task 3（状态栏在 ChatWindow）；Task 7 依赖 Task 2（抽屉联动）；Task 8 收尾依赖全部。
