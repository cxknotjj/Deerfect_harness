---
title: "项目总说明"
order: 2
---

# 项目总说明

> 面向第一次接触仓库的人与 AI：先读这一页建立“世界观”，再按链接深入。
> 本页只写稳定的定位、边界、技术栈、模块与原则；实现细节以 [principal.md](./principal.md)、[project-structure.md](./project-structure.md)、[tech-stack.md](./tech-stack.md)、[data-flow.md](./data-flow.md) 为准。
>
> 如果只看一篇：本页。如果要改代码：本页 + principal + project-structure + [guides/coding-conventions.md](./guides/coding-conventions.md) + [dev.md](./dev.md)。

---

## 一、一句话定位

**Deerfect Harness（仓库名 `javaHarness`）是一个可自托管的个人 Agent 助手**：把任务发进来，按任务形态选择「单 Agent 自主执行」或「多 Agent 并行编排」，最后汇总交付。它**不是 coding agent**，也不是通用工作流平台。

它解决的是“自己拼一套多 Agent 系统”的重复劳动：路由、拆解、并行、汇总、模型失败切换、工具沙箱、会话记忆、知识库、预算与观测，都在仓库里给出可运行的默认实现。

---

## 二、两条执行路径

| 路径 | 适用场景 | 执行体 | 输出 |
| --- | --- | --- | --- |
| **Agentic** | 单一交付物、步骤强顺序、对话/问答/单次查询 | AgentScope ReAct（`AgentScopeGeneralAgent`） | 逐 token 流式 |
| **Graph 编排** | ≥2 个可独立并行的工作流，或 ≥2 份可独立交付的产物 | 自研 `ParallelOrchestrationEngine`：lead 拆解 → 并行子任务 → 聚合 | 阶段进度 + 最终回答流式 |

入口统一由 `RouteJudge` 判定，也可用请求体 `routing`（`auto` / `agentic` / `graph`）显式指定。Graph 的子任务失败写占位跳过，不连坐；长编排支持检查点与断点续跑。

> 两条路径的请求生命周期、预算记账、观测落库见 [data-flow.md](./data-flow.md)；路径判定细节见 [design/2026-08-26-main-agent-route-judge-design.md](./design/2026-08-26-main-agent-route-judge-design.md)。

---

## 三、入口与能力面

**入口**

- REST API + SSE：`/api/chat`、`/api/chat/stream`、`/api/harness/*`
- CLI：纯 HTTP + SSE 的终端客户端
- Web 控制台：`deerfect-web/`，静态托管或本地 Vite dev server
- 渠道：QQ（NapCat）、飞书（webhook 出站 + 长连接入站）、邮件（只出不进）

**能力面**

- **模型路由**：`Agent → llm_route → llm_model`，按 priority 失败切换，管理面可配置
- **工具**：core 工具、MCP（stdio / HTTP）、声明式 HTTP、sandbox（Docker，Python/Shell/浏览器）；技能按 `skills/<name>/` 注入索引、`load_skill` 取正文；Schema 首轮只给名称、`expand_tool` 再展开
- **知识**：本地 Markdown 知识库 + pgvector/BM25 混合检索（RAG，可选依赖）
- **记忆**：会话快照、滚动摘要、用户画像、显式记忆、历史归档召回
- **主动化**：定时任务、Goal 台账、投递 outbox、渠道探活
- **观测**：`llm_call_log` / `tool_call_log` / `kb_retrieval_log` / `mcp_server_log`，以及 Prometheus 指标
- **安全**：口令换 HttpOnly Cookie（`X-API-Token` 机器通道）、沙箱隔离、工具最小权限

能力细节见各 `guides/`；**未实现项不再集中维护**：正在推进的见 [task-checklist/readme.md](./task-checklist/readme.md)，新想法走 `specs/`。

---

## 四、边界：不做什么

明确边界比堆功能更重要。以下不是“暂时没做”，而是当前定位之外：

1. **不是 coding agent / IDE**：不以读写仓库、跑测试、提交代码为核心任务。
2. **不是多租户 SaaS**：单实例、单管理员为主；鉴权是口令/Token，不是账号体系与租户隔离。
3. **不是通用工作流平台**：没有可视化画布、任意 DAG 编排、插件市场；复杂路径固定为 lead → 子任务 → 聚合。
4. **不是模型托管/训练平台**：模型来自外部 OpenAI 兼容端点，仓库不训练、不部署模型。
5. **不是知识库采集平台**：知识来自本地 `knowledge/**` 或手工上传，不做网页爬取与持续采集。
6. **当前按单实例设计**：多实例水平扩展、Redis 会话缓存、消息队列、OpenTelemetry 分布式追踪都未做，也不在当前计划内。
7. **Web 是控制台，不是产品前端框架**：页面服务于配置、对话、观测与文档浏览，不承担独立产品站点。

---

## 五、技术栈

| 分类 | 技术 | 版本 / 说明 |
| --- | --- | --- |
| 语言 / 构建 | Java + Maven | Java 25；Maven Enforcer 强制 JDK 25+ |
| 核心框架 | Spring Boot | 4.1.2 |
| AI 框架 | Spring AI | 2.0.2；OpenAI 兼容协议对接多供应商 |
| Agent 执行 | AgentScope | `agentscope-core` 2.0.4；仅用于 Agentic ReAct 路径 |
| 编排 | 自研 `ParallelOrchestrationEngine` | 替代早期 StateGraph 方案 |
| ORM / 数据库 | MyBatis-Plus + MySQL | MyBatis-Plus 3.5.17；MySQL 8+；HikariCP |
| 向量库 | PostgreSQL + pgvector | 可选；RAG 专用数据源，懒连接，PG 不可用不影响启动 |
| Schema | Flyway | `db/migration/V*__*.sql`；只新增，不改已发布迁移 |
| 流式 | Spring MVC + Reactor `Flux<String>` | SSE 风格行协议；不是全量 WebFlux |
| 前端 | Vue 3 + Vite + TypeScript | `deerfect-web/`；marked + DOMPurify 渲染文档 |
| 测试 | JUnit 5 / Mockito / Vitest / Playwright | 后端行为测试 + 前端单测 + e2e |
| 部署 | Docker / GitHub Pages | 三件套部署；Pages 仅静态 demo |

版本以 [tech-stack.md](./tech-stack.md) 和 `pom.xml` / `package.json` 为准；本表只做快速认知。

---

## 六、模块图

```text
deerfect-cli ──HTTP/SSE──┐
                         ▼
                  deerfect-server ──compile──> deerfect-api
                         │ runtime
                         ├─ channel-qq
                         ├─ channel-email
                         └─ channel-feishu

channel-* ──compile──> deerfect-api
wms-mcp-adapter（独立 MCP 适配，不属于 core）
deerfect-web（独立前端，静态托管 / 反向代理 /api）
```

| 模块 | 职责 | 依赖方向 |
| --- | --- | --- |
| `deerfect-api` | 契约层：`contract.api` 端口、`contract.spi` SPI、`contract.model` 载荷、`view` DTO | 无同反应堆依赖 |
| `deerfect-server` | Spring Boot 主服务、core 实现、fat jar 宿主 | compile → `deerfect-api`；runtime → 三个 `channel-*` |
| `channel-qq` | QQ / NapCat（OneBot 11） | compile → `deerfect-api` |
| `channel-email` | 邮件渠道（只出不进，SMTP/465 SSL） | compile → `deerfect-api` |
| `channel-feishu` | 飞书渠道（webhook 出站 + 长连接入站） | compile → `deerfect-api` |
| `deerfect-cli` | 终端客户端，纯 HTTP + SSE | compile → `deerfect-api` |
| `wms-mcp-adapter` | WMS 只读 MCP 适配范例 | 独立 |

**硬边界**：core（`deerfect-server`）**编译期不得依赖任何 `channel-*`**，由 `maven-enforcer-plugin` 守护；渠道通过 `deerfect-api` 的 `MessageSender` / `ReplyPostProcessor` / `ChannelHealth` 等 SPI 接入。详见 [design/channel-core-module-split.md](./design/channel-core-module-split.md)。

---

## 七、目录入口

| 想找什么 | 去哪里 |
| --- | --- |
| 后端入口 / 分层 | `deerfect-server/src/main/java/com/dark/javaHarness/`：`controller` → `service` → `mapper` / `domain.entity`；`agent` / `graph` / `llm` / `tool` / `observability` 等运行时关注点独立成包 |
| 数据库迁移 | `deerfect-server/src/main/resources/db/migration/`；版本号取目录当前最大 +1 |
| 前端 | `deerfect-web/src/`：`api` / `components` / `composables` / `styles` / `utils` / `docs` |
| 文档 | `docs/`：根层总览与约束、`guides/` 使用与协议、`design/` 设计与 ADR、`task-checklist/` 跨功能进度、`reports/` 报告、`archive/` 历史存档 |
| 变更 | `specs/`：在途 spec（Spec Kit：spec → plan → tasks）；完成后归档 `docs/archive/specs/` |
| 渠道 | `channel-qq/`、`channel-email/`、`channel-feishu/`，各自有 `README.md` |
| 领域适配 | `wms-mcp-adapter/` |

完整目录树与逐文件说明见 [project-structure.md](./project-structure.md)。

---

## 八、不可违反的原则

以下是硬约束；每条都应能一眼判断是否被违反。原理与例外见 [principal.md](./principal.md)。

1. **防御性兜底只在边界**：HTTP 入参、外部系统返回、反序列化、异步任务入口；内部调用信任类型与上游校验，不写静默兜底。
2. **分层与依赖方向**：`controller` 不写业务分支、不直连 Mapper；`service` 编排；`mapper` / `entity` 只做数据读写；core 不依赖渠道。
3. **重构期不留兼容层**：旧构造器、旧重载、旧接口、旧字段直接删；不写 deprecated 桥接。
4. **单一事实来源**：上下文预算只在 `app.context.*`；token 估算只用 `TokenEstimator`；工具装饰器只在 `DefaultToolDecorators`；数据库变更只新增 Flyway 迁移。
5. **bug 修复默认根治**：先定位根因；只有根因在上游且根治成本过高时才允许最小补丁，并注释根因、留文档链接。
6. **测试以行为和契约为准**：修 bug 必须补一条能复现的行为级测试；不作为私有方法写单测。
7. **密钥不入库、不进日志**：凭证只通过环境变量引用（`credentialRef`），前端不写密钥。
8. **文档与代码同批更新**：结构与接口变化同一次提交更新文档；不写死锚点；任务完成数只在 [task-checklist/readme.md](./task-checklist/readme.md) 汇总，设计文档状态只在 [design/README.md](./design/README.md) 汇总。

---

## 九、阅读路线

| 目标 | 顺序 |
| --- | --- |
| 5 分钟了解项目 | 本页 → [README](../README.md)（速览与快速开始）→ [data-flow.md](./data-flow.md)（请求怎么跑） |
| 改后端代码 | 本页 → [principal.md](./principal.md) → [project-structure.md](./project-structure.md) → [guides/coding-conventions.md](./guides/coding-conventions.md) → [data-flow.md](./data-flow.md) |
| 加功能 / 改行为 | [../specs/README.md](../specs/README.md)（Spec Kit：spec → plan → tasks）→ [design/README.md](./design/README.md)（查已有决策） |
| 跑起来 / 排障 | [dev.md](./dev.md) → [ops.md](./ops.md) → [guides/functional-testing.md](./guides/functional-testing.md) |
| 看架构决策 | [design/README.md](./design/README.md)（ADR 索引，含状态与替代方案） |
| 看当前进度 | [task-checklist/readme.md](./task-checklist/readme.md) → [design/README.md](./design/README.md)（在做的决策与草案） |
| 改前端 | 本页 → [guides/coding-conventions.md](./guides/coding-conventions.md) → `deerfect-web/src/` → [dev.md](./dev.md) 的「前端开发与验证」 |

---

## 十、维护约定

- 本页只在**定位、边界、技术栈、模块图、目录入口、原则**发生变化时更新；功能清单、完成数、待办状态不写在这里。
- 新增模块、替换核心框架、改变执行路径或部署形态时，必须同一次提交更新本页。
- 与代码冲突时，视为**代码或文档待修**，不把过期描述当事实；任务进度以 [task-checklist/readme.md](./task-checklist/readme.md) 为准，设计文档状态以 [design/README.md](./design/README.md) 为准。
