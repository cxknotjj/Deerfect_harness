# Session 上下文构成快照清单

- 范围：`server`（prompt / agent / observability）与 `web` 监控页
- 设计规格：原稿随 2026-10-30 文档压平移除；实现口径见 [上下文构成监控说明](../docs/dev.md#会话上下文构成快照) 与 [上下文优化指南](../docs/guides/context-optimization.md)
- 创建：2026-11-01
- 状态：共 11 项，已完成 11 项，待办 0 项

## 进度概览

| 分类 | 已完成 | 待办 | 小计 |
|---|---|---|---|
| 设计与规格 | 2 | 0 | 2 |
| 核心计算与存储 | 3 | 0 | 3 |
| 请求链路接线 | 2 | 0 | 2 |
| API 与 Web | 3 | 0 | 3 |
| 联调与文档 | 1 | 0 | 1 |
| 合计 | 11 | 0 | 11 |

## 已完成

- [x] 设计规格编写（2026-11-01）
  - 作用：明确快照口径、组件、接口与降级行为，避免实现阶段反复猜需求
  - 位置：原稿路径 `docs/superpowers/specs/2026-11-01-session-context-composition-design.md`（已移除）；现存实现见 `ContextCompositionAdvisor` / `ContextCompositionStore`
  - 做法：按目标、决策、数据流、组件、DTO、API、错误、测试、实现步骤分节
  - 验收：规格无占位符、前后一致、范围可被一个实现计划覆盖

- [x] 规格自检（2026-11-01）
  - 作用：在编码前排除矛盾与模糊表述
  - 位置：同上
  - 做法：占位符扫描、内部一致性、范围检查、模糊性检查
  - 验收：part key、占比口径、降级行为均已明确

## 核心计算与存储

- [x] C1 `PromptAssembler` 分段 token 统计
  - 作用：让 system prompt 各段在装配时就能独立计量，作为快照的基础数据
  - 位置：`server/src/main/java/com/dark/javaHarness/prompt/PromptAssembler.java`、`PromptAssemblerTest.java`
  - 做法：新增 `RenderedSection(name, text, tokens)` 与 `assembleSections(...)`；`assemble(...)` 复用分段结果并按 `\n\n` 拼接，保持现有输出不变
  - 验收：现有 `PromptAssemblerTest` 全部通过；新增段名/顺序/token 断言；`assemble` 输出与旧行为一致

- [x] C2 `ContextComposition` DTO 与计算器
  - 作用：把 system 分段、RAG、工具 Schema、最终消息统一换算成 parts 与占比
  - 位置：`server/src/main/java/com/dark/javaHarness/domain/dto/ContextComposition.java`、`prompt/ContextCompositionCalculator.java` 及单测
  - 做法：按规格实现 part key/label/group；`system.other` 仅正数；最后一个 `UserMessage` 算当前消息，其余按角色汇总历史；`percent` 四舍五入 1 位小数
  - 验收：占比之和约等于 100%；历史/当前消息拆分正确；RAG 与工具 Schema 正确计入；总数为 0 时不生成 parts

- [x] C3 快照存储与写入器
  - 作用：按 session 保存最近一份快照，支撑监控页实时查询
  - 位置：`server/src/main/java/com/dark/javaHarness/prompt/ContextCompositionStore.java`、`ContextCompositionRecorder.java` 及单测
  - 做法：内存 Map，TTL 30 分钟、最多 200 个会话；同 session 覆盖旧快照；`get` 惰性清理；测试注入时钟避免 sleep
  - 验收：覆盖、TTL、容量淘汰、缺失返回空四类单测通过

## 请求链路接线

- [x] W1 Path A（ChatClient）快照
  - 作用：在预算裁剪后的真实请求上记录快照，保证看到的就是模型实际收到的输入
  - 位置：`server/src/main/java/com/dark/javaHarness/agent/AgentRequestSpecFactory.java`、`advisor/ContextCompositionAdvisor.java`
  - 做法：`assembleSystem` 返回 `SystemAssembly`；`resolveTools` 之后计算工具 Schema token；新增 advisor（Order = HIGHEST+4）携带 blueprint，读取最终 messages 后写入 recorder；`sessionId` 为空时不注册
  - 验收：advisor 不改写请求；最终 messages 与裁剪结果一致；Path A 单测通过

- [x] W2 Path B（AgentScope ReAct）快照
  - 作用：覆盖 AgentScope 执行路径，避免只有 ChatClient 路径有快照
  - 位置：`server/src/main/java/com/dark/javaHarness/agent/AgentScopeGeneralAgent.java`
  - 做法：在 `ContextAssemblingAdvisor.assemble(history)` 与 `resolveTools` 之后，用裁剪后的历史 + 当前 user + `SystemAssembly` + 工具 token 调用 recorder
  - 验收：Path B 单测/最小集成测试记录到正确 session/agent/turn；不产生额外 LLM 调用

## API 与 Web

- [x] A1 查询接口
  - 作用：给监控页与脚本提供只读、可复用的快照查询能力
  - 位置：`server/src/main/java/com/dark/javaHarness/controller/ContextCompositionController.java` 及测试
  - 做法：`GET /api/monitor/context?sessionId=...`；命中返回 200，无快照返回 404；不访问数据库、不触发 RAG
  - 验收：200/404 单测通过；空 sessionId 返回 404

- [x] F1 Web 类型、client 与 mock
  - 作用：让前端能正确解析接口，并在 mock 模式下正常开发
  - 位置：`web/src/api/types.ts`、`web/src/api/client.ts`、`web/src/api/mock/mock.ts`
  - 做法：新增 `MonitorContextComposition` 类型、`monitorContext(sessionId)` 方法与 mock 样例
  - 验收：`npm run typecheck` 通过；mock 返回结构合法

- [x] F2 监控页上下文面板
  - 作用：让用户在现有监控页直接看到会话上下文占比
  - 位置：`web/src/components/MonitorView.vue`（必要时新增子组件）
  - 做法：活跃会话行增加「上下文」按钮；点击请求 `monitorContext`；展示堆叠条 + 明细表 + 估算说明；处理加载、空态、错误；切换会话时刷新
  - 验收：有快照时正确渲染；无快照显示空态；`npm run typecheck` 与 mock 模式验证通过

## 联调与文档

- [x] V1 手工联调与文档
  - 作用：验证真实请求链路与页面展示，并把口径与限制写进文档
  - 位置：本地运行服务、监控页；`docs/features.md`、`docs/dev.md`（按需）
  - 做法：发一条聊天 → 调 `GET /api/monitor/context` → 打开监控页核对 system/RAG/工具/历史/当前消息占比；更新文档说明估算口径与限制
  - 验收：接口返回 parts 与预期一致；页面渲染正常；文档包含「不含轮内动态工具结果」「多 Agent 只保留最近一次」说明

## 推荐执行顺序

1. C1 → C2 → C3：先把计算与存储做成可单测的纯逻辑。
2. W1 → W2：接入两条执行路径，保证快照来源完整。
3. A1 → F1 → F2：接口与页面。
4. V1：真实链路联调与文档收口。
