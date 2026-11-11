# server

Spring Boot 主服务：**core 实现 + HTTP 接口 + fat jar 宿主**。所有渠道、CLI、Web 最终都落到这里。

它同时是唯一实现 `harness-api` 契约的模块——`contract.api` 的每个端口在这里都有 `Impl`，
`contract.spi` 的每个 SPI 由渠道实现、在这里被调用。

## 目录结构

```text
deerfect-server/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/com/dark/javaHarness/   # 见下表，19 个顶层包
    │   └── resources/
    │       ├── application.yaml         # 入口，import 下面 9 个分域配置
    │       ├── application-{agent,ai,channel,chat,datasource,knowledge,qq,security,tts}.yaml
    │       ├── db/migration/            # Flyway，V1 ~ V16
    │       └── static/knowledge.html    # 知识库检索调试页
    └── test/java/com/dark/javaHarness/  # 与主包同构，100+ 测试类
```

### 分层：接口与实现分离

```text
controller/  ──▶  service/（接口）  ──▶  service/impl/（实现）  ──▶  mapper/  ──▶  domain/entity/
                        │
                        └──▶ agent / graph / tool / knowledge / memory   （运行时关注点）
```

### 顶层包

| 包 | 文件数 | 作用 |
| --- | --- | --- |
| （根） | 1 | `JavaHarnessApplication` 启动类 |
| `controller/` | 12 | REST 端点：会话、聊天、知识库、记忆、定时任务、Provider、监控、观测查询 |
| `service/` | 6 + 19 | 领域服务接口（`AgentConfigProvider`、`GoalService`、`MemoryService`、`RouteJudge`、`ScheduleService`、`ProviderAdminService`），实现在 `service/impl/` |
| `agent/` | 21 | Agent 抽象与执行体：`Agent`、`AgentRegistry`、Agentic 路径的调用/流式管道、进度监听、AgentScope 适配（`agentic/`） |
| `graph/` | 11 + 4 | Graph 多 Agent 编排：节点、编排提示、预算、思考节流、产出解析；`graph/engine/` 是并行执行引擎与检查点 |
| `tool/` | 10 + 22 | 工具装配与装饰器（懒加载、预算、追踪、观测）、`builtin/`（14，含沙箱/网页/TTS/记忆）、`mcp/`（4）、`budget/`、`trace/` |
| `knowledge/` | 10 | 知识库：目录扫描、Markdown 切分、BM25 索引、混合检索、导入 |
| `memory/` | 4 | 长期记忆与用户画像：显式记忆段、画像提炼、会话滚动摘要 |
| `prompt/` | 14 | 上下文组装：分段提供者（目标/记忆/画像）、预算计算、组装记录与快照 |
| `advisor/` | 3 | Spring AI Advisor：上下文组装、提示预算、组合记录 |
| `message/` | 5 | 投递层：outbox、退避重投、渠道探活告警、渠道注册表 |
| `state/` | 6 + 2 | 编排检查点与状态键（续跑的依据）；`state/persistence/` 落库 |
| `domain/` | 9 + 15 + 4 | 领域数据：根级为日志/路由/任务等，`entity/` 为 MyBatis 实体，`dto/` 为内部传输对象 |
| `mapper/` | 16 | MyBatis-Plus Mapper |
| `observability/` | 14 | 观测落库与查询：LLM 调用、工具调用、知识命中、组件状态、指标、数据清理 |
| `exception/` | 5 | `GlobalExceptionHandler` 与领域异常（并发冲突、续跑冲突、模型额度/鉴权） |
| `config/` | 22 | 配置属性与装配；子包 `agent`/`datasource`/`knowledge`/`logging`/`security` |
| `validation/` | 2 | 纯规则校验（文件名、Provider 配置），无副作用 |
| `voice/` | 2 | TTS 配置与内存工件存储（实现 `contract.api.VoiceArtifactStore`） |
| `enums/` | 1 | `AgentConstants`（默认 Agent 名等） |

### `resources/` 里的分域配置

`application.yaml` 只做 import，每个域一个文件，便于按需改：

| 文件 | 内容 |
| --- | --- |
| `application-ai.yaml` | 模型提供方、超时、重试 |
| `application-agent.yaml` | Agent 执行、路由、告警阈值与抑制窗口 |
| `application-chat.yaml` | 会话上下文预算、裁剪、摘要 |
| `application-channel.yaml` | 邮件 / 飞书（webhook 与长连接）配置 |
| `application-qq.yaml` | NapCat 地址、事件准入规则、限频、表情索引 |
| `application-knowledge.yaml` | 知识库目录、切分参数、检索权重 |
| `application-security.yaml` | API token、登录态 |
| `application-tts.yaml` | 语音合成 |
| `application-datasource.yaml` | MySQL 连接与连接池 |

### `db/migration/`

Flyway 脚本 V1 ~ V16，**只增不改**。已应用的脚本不要再编辑——改了校验和会直接启动失败。
新增字段一律加新版本（例如 V16 把 `session.creator` 从 `varchar(30)` 放宽到 64，
因为飞书 `open_id` 加前缀后有 42 字符）。

## 测试

`src/test/java` 与主包同构，100+ 测试类，三类值得知道：

- `ArchitectureTest`：分层与依赖方向的机器守卫（controller 不得直接依赖 mapper 等）。
- `*ContractTest`：跨实现的契约（编排、会话）——换实现也要满足同一组断言。
- `support/MutableClock`：可控时钟，用来测超时/过期/重试窗口，避免 `Thread.sleep`。

跑全量：`mvn -s .mvn/settings.xml verify`（含 JaCoCo 门槛，当前 81.5%，门槛 79）。

## 边界

- **不实现渠道**：`server` 对 `channel-*` 只有 runtime 依赖，compile 期看不见任何渠道类
  （enforcer 守着）。新增渠道不改 core。
- **不放 HTTP DTO 到契约层之外的地方**：对外契约类型在 `harness-api` 的 `view`，
  这里只负责装配。
- **不直接返回实体**：端口返回 `record`（如 `SessionInfo`），实体留在 `domain/entity`。
