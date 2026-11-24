# mem0 长期记忆扩展 · 设计（2026-11-23）

> 状态：已实施（2026-11-24，经插件机制落地；本地精简服务 `plugin-mem0-local`（mem0ai + Chroma，OSS REST 形状），Platform 不在本期）　日期：2026-11-23
>
> 结论先行：项目已有三套记忆机制（用户画像 / 会话摘要 / 显式记忆），mem0 **不替换任何一套**，而是作为第四路「跨会话自动提取的事实记忆」接入。推荐**自托管 mem0 OSS REST 服务**（`X-API-Key` 鉴权、无 `/v1/` 前缀、同步 `POST /memories`），在一轮成功写回的唯一汇合点异步写入，在 system prompt 增加 order 145 的检索段读取。**默认不新增数据库表、不新增抽象接口**；身份用 `session.creator`，禁止用 `default`。

---

## 一、定位：mem0 与现有三套记忆的边界

| 机制 | 存什么 | 存在哪 | 何时写 | 如何注入 |
| --- | --- | --- | --- | --- |
| 用户画像 `UserProfileService` | 软偏好（"- [key] value" 补丁） | `user-profile/user-profile.md` | 定时扫空闲会话，LLM 提炼后按 key 合并 | system 段 order 150，受 `app.memory.profile-enabled` 控制 |
| 会话摘要 `SessionSummaryService` | 会话内被裁剪轮次的压缩摘要 | `session_summary` 表 | 上下文裁剪时投递异步任务 | `ContextAssemblingAdvisor` 注入【历史摘要】 |
| 显式记忆 `MemoryServiceImpl` | 用户**明说**的事实 | `memory` 表（V13） | `remember` 工具 / REST | system 段 order 140，先于画像 |
| **mem0（新增）** | **跨会话自动提取的事实与实体关系** | **外部 mem0 服务（自建 pgvector / 图库）** | **一轮成功结束后异步 add** | **system 段 order 145，检索式注入** |

冲突优先级写进段文案：**显式记忆 > mem0 > 画像 > 会话摘要**。mem0 不得写入或覆盖画像文件、不得接管 `memory` 表的权威事实、不得承担会话内摘要。

为什么不合并到现有机制：`memory` 表按 key 覆盖且 `sessionId` 仅追溯、不参与过滤；画像靠 LLM 按 key 合并成单一文本；两者都不做语义检索和实体关系。mem0 的价值是"从整轮对话里自动抽取多条事实 + 相似度召回"，这是另一个职责，不是替代品。

---

## 二、方案选型：自托管 OSS 还是托管 Platform

| 维度 | 自托管 OSS REST（推荐） | 托管 Platform API |
| --- | --- | --- |
| 写入路径 | `POST /memories` | `POST /v3/memories/add/`（异步，返回 `event_id`，需轮询 `GET /v1/event/{id}/`） |
| 检索路径 | `POST /search` | `/v1/search/` 系列 |
| 鉴权头 | `X-API-Key: m0sk_...`（per-user key）或 `X-API-Key: <ADMIN_API_KEY>` | `Authorization: Token <api-key>` |
| 写入返回 | 返回抽取结果 `results`（以服务 `/docs` 的 OpenAPI 为准） | `{"event_id": ..., "status": "PENDING"}` |
| 数据位置 | 自建 Postgres/pgvector，数据不出域 | 厂商托管 |
| 成本 | 自带 LLM / embedder provider key | 平台计费 |
| 契合度 | 项目已有 Docker 三件套部署与数据自控要求 | 需评估数据出境与费用 |

**红线：两套 API 不可混用。** 自托管 OSS 没有 `/v1/` 前缀，不能照搬 Mem0 Platform 的 API Reference 或 Python SDK 的请求路径/鉴权头/异步语义。

自托管部署要点（官方文档）：

```bash
cd server
cp .env.example .env
# 必填：OPENAI_API_KEY（或其他受支持 provider）、JWT_SECRET=$(openssl rand -base64 48)
make bootstrap    # 起 Compose + 创建 admin + 生成第一把 m0sk_ API key
```

- API 端口 `8888`，Dashboard `3000`；`http://localhost:8888/docs` 是在线 OpenAPI，对接前先在这里确认真实请求体与响应结构。
- 1.x 起**鉴权默认开启**：`JWT_SECRET` 未设置直接拒绝启动；`AUTH_DISABLED=true` 只允许本地开发，生产禁用。
- 旧 `ADMIN_API_KEY` 仅作向后兼容（需 16 字符以上），新部署优先用 per-user key，可撤销且有审计日志。
- 容器自带 LLM provider 仅 `openai / anthropic / gemini`，embedder 仅 `openai / gemini`；若要用项目的通义/DeepSeek 兼容端点，需先验证是否支持自定义 base_url，不支持就得另备 provider key（**人需确认**）。

---

## 三、代码对接点

### 3.1 配置：沿用 `app.memory.*` 命名空间

在 `deerfect-server/src/main/resources/application-agent.yaml` 现有 `app.memory:` 下追加 `mem0:` 子树（该文件已被 `application.yaml` import，且与其它域文件键区间不重叠）：

```yaml
  memory:
    mem0:
      enabled: false                              # 总开关，默认关闭；客户端/写入钩子/注入段同开关装配
      base-url: ${MEM0_BASE_URL:http://127.0.0.1:8888}
      api-key: ${MEM0_API_KEY:}                   # m0sk_...，只从环境变量注入
      write-enabled: true
      search-enabled: true
      top-k: 5                                    # 单次召回条数
      max-chars: 1200                             # 注入段字符上限
      connect-timeout-ms: 2000
      read-timeout-ms: 3000
```

- 数值只在 yaml 维护，代码里不写第二套（`docs/principal.md` 第七节）。
- 密钥走环境变量，`.env.local` 已 gitignore；手工启动不会自动加载它，需 `set -a; source .env.local; set +a`（`docs/dev.md`）；Docker 部署用 `docker/.env`。
- 条件装配范式参考 `config/knowledge/KnowledgeConfig.java:49` 与 `memory/UserProfileService.java:57`（`@ConditionalOnProperty`）。

### 3.2 客户端：外部服务边界，参考 `WmsRestClient`

`wms-mcp-adapter/src/main/java/com/dark/wms/client/WmsRestClient.java:36-85` 是项目里最贴近的范例：Spring `RestClient` + `SimpleClientHttpRequestFactory` 超时 + 构造器注入 `RestClient.Builder`（包可见测试构造器）+ 中文错误分类。

- 新建 `memory/mem0/Mem0Client.java`（**单个类，不抽接口**：目前只有一个实现，抽接口会撞 `docs/principal.md` 第一节「新增抽象前 ≥3 处真实复用点」；Mockito 可直接 mock 具体类）。
- 只做三件事：`add(messages, user_id, metadata)`、`search(query, user_id, top_k)`、`delete(memoryId)`；配置类 `Mem0Properties` + `@ConfigurationProperties(prefix = "app.memory.mem0")`。
- 请求头统一 `X-API-Key`；OSS 路径 `POST /memories`、`POST /search`。
- `try/catch` 与降级**只允许出现在客户端与异步任务入口**（外部系统返回属于系统边界，`docs/principal.md` 第二节）；超时/5xx 记 WARN 并跳过，不向业务路径抛异常，也不在 service/agent 层写空值兜底。

### 3.3 身份映射：最高风险点

| mem0 字段 | 映射来源 | 说明 |
| --- | --- | --- |
| `user_id` | `session.creator` | 形如 `qq:123` / `feishu:ou_xxx` / `cli` / `anonymous`，由 `SessionServiceImpl` 写入，字段宽 64（V16） |
| `session_id` / `run_id` | `TurnPersistence.SessionCtx.sid()` | 会话数字 id 的字符串形式 |
| `metadata` | `channel`、`agentName`、`turnId`、`app=java-harness` | 便于审计与去重；`turnId` 来自 `TurnPersistence.newTurnId()` |

**禁止**把 `MemoryServiceImpl.DEFAULT_TENANT = "default"` 当作 mem0 用户身份——那是全库单租户，QQ / 飞书 / CLI 用户会被串成一份记忆。`ChatRequest` 没有 userId（`deerfect-api/.../view/ChatRequest.java:16-22`），唯一可靠来源就是 `session.creator`。

注意：`cli` 与 `anonymous` 是共享标识，若需要区分不同 CLI 用户，**必须先引入用户标识**（人工决策），否则所有 CLI 用户共享一份 mem0 记忆。

### 3.4 写入时机：只挂一个汇合点

项目有两条写回通路，且各有多处调用：

- 同步：`ChatTurnExecutor.java:84,109` → `TurnPersistence.writeBackSucceededTurn`
- 流式：`ChatStreamPipeline.java:199` 的 `doOnComplete` → `writeBackReply`（`doOnCancel` 不写）
- 两者最终都进 `TurnPersistence.writeBackBoth`（私有，`:116-121`）

**做法：只在 `writeBackBoth` 内加一次 best-effort 投递**，不要在 `agentService.execute*` 层挂钩子（COMPLEX 降级重答会重复 add）。失败轮、客户端断开不写，符合"失败不污染记忆"。

异步执行参考 `memory/SessionSummaryService.java:51,61-66,130-149`：单线程守护池 + 单用户 `inFlight` 去重 + `DiscardPolicy` + 失败不重试。写入内容为本轮 user/assistant 两条 message；`metadata.turn_id` 用于同轮去重（短 TTL 集合，TTL 数值进 yaml）。

### 3.5 读取与注入：改 `PromptAssembler`，不是加个 bean

现有扩展点是"固定内部段 + 四类外部 provider"，四类 provider 签名各不相同，其中 `ExplicitMemorySectionProvider.provide(String agentName)` 的实现是"首个非空即返回"（`PromptAssembler.java:282-287`）——**再挂一个实现会被静默遮蔽**。

推荐路径：

1. `PromptSection` 是内部段契约（`prompt/PromptSection.java:11-25`，`name/order/render`）。新增一个内部 `Mem0Section`，order **145**，插在显式记忆（140）与画像（150）之间。
2. `PromptSection.Context` 目前只有 `agentName / rolePrompt / toolNames / channel / sessionId`，**没有当前 user message**；语义检索需要 query，因此必须给 `Context` 增加 `userMessage` 字段，并更新所有构造调用点。这是本方案绕不开的改动。
3. `PromptAssembler` 构造器加入 `Mem0Client`（或专用召回类）参数；现有 2/3/5/6 参重载（`:67-97`）与 `ChatAgentConfig.java:78-88`、`PromptAssemblerTest.java:57-65` 的直接 `new` 调用需同步更新。
4. 段内容：`search(query = userMessage, user_id = creator, top_k = yaml)` → 按 `max-chars` 截断 → 文案声明「以下为长期记忆，冲突时以用户明确记忆为准」。检索为空时返回空串（该段自动跳过）。

### 3.6 工具暴露（可选，P2，默认不做）

若后续要让模型主动查/写 mem0：

- 新建 `tool/builtin/mem0/Mem0Tools.java`，`@Tool` 命名与现有 `remember` / `list_memories` 明显区分，避免模型混淆。
- 必须在 `ToolAssignments` 同时做三件事：`indexAnnotated` 索引、`TOOL_PURPOSES` 用途文案、`agent.tools` 列声明组名/精确名（V13 给 memory 加组名的先例）。只加 Java 注解不会生效。
- `ArchitectureTest.java:39-44` 禁止 `tool..` 依赖 `service.impl..`，工具只能依赖 client 或 service 接口。
- 默认不开放给模型，等检索质量验证通过后再决定。

### 3.7 数据库：默认零迁移

mem0 自存数据，`turn_id` 放 metadata 即可，**不需要新增 Flyway 迁移**。

若之后要做同步审计/失败回溯，再新增一张表；编号以迁移目录当前最大版本 +1 为准（当前为 **V26**，新增从 **V27** 起）。注意 `docs/principal.md` 仍写"基线 V13"，已漂移，**不要照文档编号**，也不要修改已发布迁移（`docs/project-structure.md` 迁移章节）。

---

## 四、测试方案

项目已有全量审查报告 `docs/reports/2026-11-14-test-data-dependency-audit.md`（空库 1136 用例 0 失败），新增测试必须维持"不依赖真实库数据、不触发真实外网调用"。

| 测什么 | 怎么做 | 参照 |
| --- | --- | --- |
| HTTP 客户端路径/鉴权头/请求体/响应/错误分类 | `MockRestServiceServer` + 包可见的 `RestClient.Builder` 构造器 | `wms-mcp-adapter/src/test/.../WmsRestClientTest.java:33-50` |
| 注入段渲染、截断、空结果跳过 | Mockito mock `Mem0Client` | `test/.../memory/ExplicitMemorySectionProviderTest.java:24-49` |
| 一轮成功写一次；失败轮 / `doOnCancel` 不写；同 `turnId` 不重复 | `TurnPersistence` 行为测试 + mock 投递器 | `test/.../service/impl/` 既有风格 |
| mem0 不可达时主链路不受影响 | 客户端超时用例 + 异步投递异常吞噬断言 | `SessionSummaryService` 的错误处理 |
| 真服务冒烟 | `@Tag("live")` 默认排除，显式 profile 才跑 | 审查报告 P1：不要用"环境变量存在"当开关 |

验证命令（项目硬规则）：`mvn -s .mvn/settings.xml -pl deerfect-server -am test`；跨模块或 SQL 变更需全量 `install` 通过后才算完成。

---

## 五、实施分期

| 阶段 | 内容 | 验收 | 执行方 |
| --- | --- | --- | --- |
| P0 | 选自托管/平台；准备 provider key；部署 mem0、生成 `m0sk_` key；在 `/docs` 实测 `score` 方向与返回结构 | 有可用 base-url 与 key，`POST /memories` + `POST /search` 冒烟通过 | **人前置** |
| P1 | 配置属性类 + `Mem0Client` + 客户端测试 | 单测覆盖鉴权头、路径、错误分类；`enabled:false` 时不装配 | AI |
| P2 | `TurnPersistence.writeBackBoth` 单钩子 + 异步投递 + 身份映射 + 行为测试 | 成功轮写一次、失败/取消不写、CLI 与 QQ 用户不串号 | AI |
| P3 | `PromptSection.Context` 加 `userMessage` + order 145 段 + `PromptAssembler`/`ChatAgentConfig` 同步更新 + 测试 | 段出现在 general/lead，空召回不占位，编译与既有测试全绿 | AI |
| P4（可选） | `Mem0Tools` + `ToolAssignments` 授权 + 可能的 V27 | 工具不注入默认 agent，显式授权后可用 | 人决策后 AI |
| P5 | 接入日志/指标，观测写入失败率与召回命中率 | 运营面板可见 | AI |

---

## 六、风险与红线（基于代码事实）

1. **身份串号**：用 `default` 当 user_id、或 CLI/anonymous 不区分用户，是最高风险。
2. **API 混用**：OSS 无 `/v1/` 前缀、无 V3 异步 event；照搬平台 SDK 会全部 404 或解析失败。
3. **鉴权默认值**：1.x 起默认开启，`JWT_SECRET` 必填；忘了配 key 会全 401，图省事关 `AUTH_DISABLED` 等于把记忆库裸奔公网。
4. **`score` 方向**：官方自托管博客把 `score` 描述为 cosine distance（**越小越近**），平台生态常见口径是越大越相关；必须实测后再定阈值，**不要照抄任何 SDK 示例的阈值**。
5. **段扩展不是加 bean**：构造器重载、`Context` record、`ChatAgentConfig`、`PromptAssemblerTest` 四处都要改，漏一处就启动失败或段静默消失。
6. **段遮蔽**：复用 `ExplicitMemorySectionProvider` 多实现会被"首个非空即返回"吞掉。
7. **重复写入**：三条写回路径（同步成功 / 流式完成 / 降级重答）只能收敛到 `writeBackBoth` 一处。
8. **抽象红线**：当前只有一个客户端实现，不要为"以后可能换厂商"抽接口（`docs/principal.md` 第一节）。
9. **兜底边界**：`try/catch` 只能在客户端和异步入口；logic 层不得出现静默空值返回。
10. **成本与延迟**：每次 add/search 都会触发 mem0 侧的 LLM/embedder 调用；`top-k`、段字符上限、写入开关都要能在 yaml 关掉。

---

## 七、待人拍板的问题

- 自托管 OSS 还是托管 Platform？数据能否出域？
- mem0 与现有三套记忆的冲突优先级是否接受「显式记忆 > mem0 > 画像 > 摘要」？
- CLI / anonymous 用户如何区分（是否需要引入用户标识）？
- 是否新增同步审计表（V27）？还是只靠 metadata 与日志？
- `top-k` / `max-chars` / `score` 阈值取多少（依赖 P0 实测）？
- 是否把 mem0 暴露为模型可调用的工具？
- 用户删除记忆时，是否级联删除 mem0 侧数据（`DELETE /memories`）？留存期多久？

## 八、参考

- Mem0 自托管部署：https://docs.mem0.ai/open-source/setup
- Mem0 OSS REST API：https://docs.mem0.ai/open-source/features/rest-api
- Mem0 Platform API（**仅作差异对照，路径不可用于 OSS**）：https://docs.mem0.ai/api-reference/memory/add-memories
- 项目内：`docs/principal.md`、`docs/project-structure.md`、`docs/reports/2026-11-14-test-data-dependency-audit.md`、`docs/dev.md`
