# 🧭 项目功能介绍

> 面向首次接触项目的读者，一页说明「现在能做什么、还没做什么」。
> 本文按 2026-10-28 的代码与配置核对；技术基线为 **Java 25 / Spring Boot 4.1.2 / Spring AI 2.0.2**（详见 [TECH_STACK.md](./TECH_STACK.md)）。

---

## 一、项目定位

Deerfect Harness 是一个基于 Spring AI 的双路径 Agent 平台：**Agentic 路径**由单 Agent 显式 ReAct 循环自主执行；**Graph 编排路径**自动拆解、专家并行、聚合交付，全过程流式可视。所有能力默认只依赖 MySQL，Docker 仅用于沙箱工具。

## 二、使用入口

| 入口 | 已实现能力 | 文档 |
|---|---|---|
| REST API | 同步聊天、SSE 流式聊天、断点续跑、Agent/Goal/会话管理、观测查询 | [api.md](./guides/api.md) |
| 终端 CLI | 多轮聊天、新建会话、切换 Agent、断点续跑、流式 Markdown 渲染 | [cli.md](./guides/cli.md) |
| Web 控制台 | 会话列表、流式打字机、思考折叠、Trace 观测、知识库管理页 | [README](../README.md) |
| QQ 机器人 | NapCat/OneBot 11、群聊 @ 唤醒、渐进分段回复、拟人延迟、表情包、防刷 | [qq-channel.md](./guides/qq-channel.md) |
| MCP Server | 主服务内置 Streamable HTTP MCP 端点，可对外暴露内置工具 | [TECH_STACK.md](./TECH_STACK.md) |

## 三、双路径执行：Agentic 与 Graph 编排

- **路径判定按任务形态**：`RouteJudge` 判的是「是否存在可独立并行的工作流 / 多份交付物」，而非消息长短或主观难度；内部枚举 `SIMPLE` / `COMPLEX` 分别对应 Agentic 与 Graph 两条路径。
- **两段式判定**：先规则短路（多工作流措辞 → Graph；≤12 字短消息 → Agentic），灰区再走一次轻量 LLM 判定，返回 `route` / `confidence` / `workstreams` / `deliverables` / `reason`。
- **置信度策略**：Graph 需同时满足「≥2 个可独立工作流」且「置信度 ≥0.8」，否则收敛为 Agentic，避免为单工作流支付编排成本；判定异常一律兜底 Agentic。
- **降级策略**：Agentic 链路不升级 Graph——ReAct 迭代耗尽时按 `GenerateReason.MAX_ITERATIONS` 判定：有整理结果按「部分完成」返回，无结果返回兜底话术。Graph 编排异常时先在编排层简化（聚合失败用已完成子任务结果汇总），仍失败再降级为会话绑定 Agent 单模型重答一次，不递归。
- **Agentic 路径**：`AgentScopeGeneralAgent` 以 ReActAgent 执行显式推理-行动循环（默认开启，`app.agentic.enabled`），工具仍由 Spring AI 提供并经桥接进入 Toolkit；初始化失败可回退 ChatClient 单次调用路径。
- **Graph 编排路径**：自研 `ParallelOrchestrationEngine` 执行「Lead 拆解 → 专家并行 → Aggregator 聚合」；子任务至多 4 条，禁止凑数。
- **失败不连坐**：单个子任务失败写占位结果，聚合照常执行并注入降级说明。
- **产品侧显式开关**：请求体 `routing`（`auto` / `agentic` / `graph`）可覆盖判定；Web 端「路径」下拉、CLI `/routing` 均走同一字段，绑定专属 Agent 的会话忽略 `graph`（保留领域工具直答）。
- **决策可观测**：判定结果（路径 / 置信度 / 工作流 / 来源）写入日志、SSE `meta.route` 与同步响应 `route`，Web 消息头显示路径徽标，悬浮可见来源与理由。
- **断点续跑**：按 `LEAD_DONE` / `SUBTASKS_DONE` / `DONE` 三阶段落检查点，已完成阶段不重跑（[resume.md](./guides/resume.md)）。

## 四、Agent 与模型体系

- **全表驱动**：`agent` 表一行即一个 Agent（prompt / model / tools / knowledge / thinking），`model_provider` 表一行即一个模型或供应商。
- **零代码扩展**：新增 Agent、接入第三方 OpenAI 兼容供应商均只需加数据行，重启或改库即生效（[models.md](./guides/models.md)）。
- **内置角色**：`general`、`deepseek` 聊天 Agent，`multi-agent` / `lead` / `aggregator` 内部编排角色，`researcher` / `coder` / `analyst` / `writer` 四类专家，另可按表扩展领域专员（如 `java-tutor`、`wms`）。
- **按 Agent 配置**：模型、系统提示词、工具集、知识库绑定、思考内容显示均按行生效。

## 五、工具体系

| 能力 | 说明 |
|---|---|
| 自研工具 | 网页抓取（jsoup 正文提取、Markdown 输出、错误分类与缓存）、时间等本地工具 |
| MCP 工具 | stdio / Streamable HTTP 多 server，懒连接、按 server 故障隔离，配置见 `mcp-config.json`（[mcp-tools.md](./guides/mcp-tools.md)） |
| 容器沙箱 | Python / Shell / 文件读写 / 浏览器操作只在 Docker 容器内执行，宿主机零暴露；无 Docker 时该组工具自动禁用 |
| 技能（Skill） | `skills/` 目录 Markdown + front-matter，按 Agent 可见集注入索引，`load_skill` 按需取正文 |
| 最小权限 | `general` 收敛为只读探索面；执行 / 写入只授予 `coder` / `analyst` |
| Schema 延迟加载 | 首轮只暴露工具名称与用途，模型调 `expand_tool` 后再注入完整参数 Schema |
| 领域适配范例 | 独立 `wms-mcp-adapter`：只读 GET、分页钳制、双侧鉴权、审计字段过滤（[wms-mcp.md](./guides/wms-mcp.md)） |

## 六、知识与记忆

- **RAG 知识库**：pgvector 向量库 + DashScope text-embedding-v4，`knowledge/` 目录增量摄取，多知识库按 Agent 绑定。
- **混合检索**：向量召回 + BM25 关键词召回，RRF 融合排序；回答带【出处N】内联引用与来源尾注。
- **知识库增强**：目录监听自动摄取、上传 / 删除 / 同步 / 调试检索、单文件管理页 `/knowledge.html`（[knowledge-rag.md](./guides/knowledge-rag.md)）。
- **会话记忆**：多轮上下文自动组装并按 token 预算裁剪，超长历史从最旧丢弃。
- **用户画像**：定时提炼跨会话偏好，注入 `general` / `lead` 的 system prompt。
- **Goal 台账**：目标全生命周期 `PENDING → RUNNING → SUCCEEDED/FAILED` 落库，支持异步提交与历史查询。

## 七、可靠性护栏

| 护栏 | 默认值 |
|---|---|
| 流式连接上限 | 50（超限 429） |
| 子任务并发 | 2（信号量错峰） |
| 子任务墙钟超时 | 600 秒 |
| 单次编排 token 上限 | 60000（超限跳过剩余子任务，聚合照常） |
| 输出封顶 | Lead 4000 / 专家 4000 / 最终回答 8000 |
| 单次工具调用上限 | 4 次，工具结果注入 5000 token |
| 超时梯队 | 连接 10s / 读 300s / 流空闲 40s / 首帧 20s / 路由判定 10s |

另有：客户端断连后零新增 LLM 调用、失败连接丢池重建、LLM 调用 3 次退避重试、Graph 路径失败降级重答。配置口径见 [context-optimization.md](./guides/context-optimization.md)。

## 八、观测与成本

- **四张观测表**：LLM 调用、工具调用、RAG 检索、MCP 连接事件，全部异步落库。
- **查询接口**：`/api/llm-calls`、`/api/tool-calls`、`/api/kb-retrievals`，Web Trace 页可视化。
- **链路标识**：`turnId` / `traceId` / `parent_span` 贯通路由、子任务与聚合。
- **保留期治理**：观测数据默认保留 90 天，每日凌晨低峰分批清理。
- **轻量追踪**：自建 span 关系；Micrometer / OpenTelemetry 尚未接入。

## 九、安全与部署

- **双通道鉴权**：机器通道 `X-API-Token`（常量时间比较）、登录通道口令换 HttpOnly Cookie（168 小时滑动续期，连续失败 5 次锁定 60 秒）；两通道均未配置则本地直通。
- **密钥管理**：API Key 不落库，`.env.local` 与 `mcp-config.json` 均不入 git。
- **数据库演进**：Flyway 版本化迁移，启动自动建表 / 升级。
- **模块化**：`harness-api` / `channel-qq` / `server` / `cli` / `wms-mcp-adapter` 五模块 + 独立 `web`。
- **容器化**：Dockerfile + docker-compose（app + MySQL + pgvector），见 [docker-deploy.md](./guides/docker-deploy.md)。
- **测试**：92 个测试类 / 830 个用例（server 78/687、channel-qq 7/72、cli 3/28、wms-mcp-adapter 4/43），详见 [functional-testing.md](./guides/functional-testing.md)。

## 十、尚未实现（路线图未勾选项）

接口级限流防刷、CI/CD、Actuator + Prometheus、Micrometer 完整链路追踪、Testcontainers 集成测试、Swagger/OpenAPI、Redis 会话缓存、消息队列、多实例水平扩展、子任务依赖编排、子任务失败重规划、Goal 失败重投、长期记忆滚动摘要、对话历史 RAG 归档、PDF 导出、多模态输入、Web 端分页与独立域名部署。

> 完整清单与验收口径见 [HARNESS_TODO.md](./HARNESS_TODO.md)。

## 十一、延伸阅读

| 文档 | 内容 |
|---|---|
| [README](../README.md) | 项目速览与快速开始 |
| [architecture.md](./architecture.md) | 架构总览与两路径数据流 |
| [data-flow.md](./data-flow.md) | 请求生命周期、预算记账、观测落库 |
| [TECH_STACK.md](./TECH_STACK.md) | 技术栈明细与当前版本 |
| [guides/api.md](./guides/api.md) | REST 接口全表 |
| [guides/functional-testing.md](./guides/functional-testing.md) | 测试全景 |
| [HARNESS_TODO.md](./HARNESS_TODO.md) | 路线图（未完成 / 已完成存档） |

---

[⬅ 返回文档索引](./README.md)
