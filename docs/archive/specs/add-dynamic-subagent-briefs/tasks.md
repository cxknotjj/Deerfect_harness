# Tasks

- [x] Task 1: LeadOutputParser 扩展（模板 × 任务书解析 + wms 白名单修复）
  - [x] SubTask 1.1: `Subtask` record 扩展为 `(desc, agent, brief)`，新格式 `{"desc","agent","brief"}` 解析，brief 原样透传（不截断，截断在执行侧）
  - [x] SubTask 1.2: 旧格式兼容——对象无 brief / 纯字符串数组解析为 brief=null
  - [x] SubTask 1.3: `EXPERT_WHITELIST` 补入 wms（引用 AgentConstants，如缺常量则新增 `EXPERT_WMS`）
  - [x] SubTask 1.4: 单测——新格式三字段、旧格式两形态、非法 JSON 回退、wms 指派不回退、白名单外回退 null
- [x] Task 2: 编排状态键传递 brief
  - [x] SubTask 2.1: MultiAgentGraphAgent 新增 `K_SUBTASK_BRIEF_PREFIX = "subtaskBrief_"`，KeyStrategy 按 MAX_SUBTASKS 注册 replace
  - [x] SubTask 2.2: 单测——状态键写入/读取、旧 checkpoint 缺键 orElse(null) 安全
- [x] Task 3: 执行侧注入与长度上限
  - [x] SubTask 3.1: ContextBudgetProperties 新增 `subtaskBriefMaxChars`（默认 2000，0=不限制），application.yaml 同名配置项
  - [x] SubTask 3.2: OrchestrationNodes lead 出口：brief 写入 `subtaskBrief_{i}` 状态键（desc/agent 写入逻辑不变）
  - [x] SubTask 3.3: OrchestrationNodes executeSubtask：读 brief 键 → 超限截断（warn 单行）→ user = brief 非空 ? brief : desc；fallbackSystem/工具/预算/记忆零改动
  - [x] SubTask 3.4: 单测——brief 注入执行、无 brief 退化 desc、超限截断、上限 0 不截断
- [x] Task 4: V31 迁移 lead prompt 任务书化（独立可并行）
  - [x] SubTask 4.1: 新建 `V31__lead_prompt_task_brief.sql`：REPLACE 幂等更新 lead 行 prompt——JSON 格式说明加 brief 字段、任务书四要素（目标/背景/约束/交付物）、保留至多 4 条约束与含 wms 的模板清单
- [x] Task 5: 全量验证
  - [x] SubTask 5.1: `mvn -pl server -am test` 0 失败 0 错误

# Task Dependencies

- Task 3 依赖 Task 1、Task 2（Subtask record 与状态键先行）
- Task 4 无依赖，可与 Task 1-3 并行
- Task 5 依赖 Task 1-4 全部完成

# 实施备注

- 截断位置按 spec.md 场景口径落在 lead 出口写入状态前（写入即定稿），executeSubtask 只读取——与 SubTask 3.3 标题的"执行侧截断"表述略有出入，以 spec.md 为准。
- 配置键名修正：spec 初稿的 `app.context-budget.*` 更正为 `app.context.*`（对齐 ContextBudgetProperties 实际前缀）；Java 字段缺省 0（类约定），部署默认 2000 由 application.yaml 显式配置。
