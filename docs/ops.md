# 运行观测与运维手册

> 指标、日志、保留期与告警的口径。本地启动、端口与工具链见 [dev.md](./dev.md)。

## 观测端点与指标

端点（只暴露 health / info / prometheus 三类，`env` / `heapdump` 等不开放）：

- `GET /actuator/health`、`/actuator/health/liveness`、`/actuator/health/readiness`
- `GET /actuator/info`
- `GET /actuator/prometheus`（Prometheus 抓取格式）

指标（`HarnessMetrics` 埋点，路径判定/LLM 台账/流控/编排降级四个位置）：

| 指标                                | 类型      | 说明 / PromQL 示例                                                                                            |
| --------------------------------- | ------- | --------------------------------------------------------------------------------------------------------- |
| `harness_sse_active`              | gauge   | 活跃 SSE 连接数                                                                                                |
| `harness_llm_first_token_seconds` | timer   | 首 token 延迟。P95：`histogram_quantile(0.95, sum by (le) (rate(harness_llm_first_token_seconds_bucket[5m])))` |
| `harness_llm_duration_seconds`    | timer   | 单次调用耗时；route-judge P95 取 `{agent="route-judge"}`                                                          |
| `harness_llm_calls_total`         | counter | 按 `status=ok/error`；错误率 = `sum(rate(...{status="error"}[5m])) / sum(rate(...[5m]))`                       |
| `harness_graph_fallbacks_total`   | counter | `stage=to_agentic`（编排失败降级单 Agent）/ `digest`（聚合失败降级汇总）                                                     |
| `harness_route_decisions_total`   | counter | 按 `path=graph/agentic`；Graph 降级率 = `fallbacks{stage="to_agentic"}` 速率 / `path="graph"` 速率                 |
| `harness_llm_tokens_total`        | counter | 按 `kind=prompt/completion/total`、`model`；每小时用量 `increase(harness_llm_tokens_total{kind="total"}[1h])`     |

成本由 token 用量 × 供应商单价换算（单价随供应商调整，不在代码里固化，避免过期价格变成伪指标）。

### 会话上下文构成快照

- `GET /api/monitor/context?sessionId=...`：返回该会话最近一次真实 LLM 请求发往模型前的输入构成（system 分段 / RAG / 工具 Schema / 历史 user / 历史 assistant / 当前 user）；无快照返回 404。
- 快照为进程内内存数据：同会话覆盖最近一份，TTL 30 分钟、最多 200 个会话，重启清空；接口只读快照，不重新组装 prompt、不触发 RAG、不访问数据库。
- 估算口径统一为 `TokenEstimator`，占比之和为 100%；ReAct 轮内动态追加的工具结果不计入（属于 `tool_call_log`），多 Agent 编排只保留最近一次调用的快照并标注实际 agent 名。
- Web 监控页「活跃会话」的「上下文」按钮调用该接口，展示堆叠占比条与明细表。

### 日志与 TraceId

控制台输出 logstash JSON（`logging.structured.format.console: logstash`），`TraceIdFilter`
把请求头 `X-Trace-Id`（缺省生成 32 位 hex）写入 MDC 的 `traceId` 并回写响应头；Reactor
异步链路（route-judge / Agent 流式生成所在的 boundedElastic）经 `ContextPropagationConfig`
开启自动传播后继承 MDC（模型 SDK 自建线程不在传播范围）。本地想恢复可读文本日志，启动
参数加 `--logging.structured.format.console=`。

### 数据保留期（两个清理任务，均可配、0 = 禁用该表）

| 任务 | 覆盖表 | 保留期配置（默认） | 时间基准 |
| --- | --- | --- | --- |
| `ObservabilityLogCleaner` | `llm_call_log` / `tool_call_log` / `kb_retrieval_log` / `mcp_server_log` | `app.observability.retention-days`（90） | `created_at` |
| `DataRetentionCleaner` | `session_message_archive` / `goal`（级联 `orchestration_checkpoint`）/ `delivery_outbox`（仅终态） | `app.retention.archive-days` / `goal-days`（180）、`outbox-sent-days`（7）/ `outbox-dead-days`（30） | `archived_at` / `finished_at` / `created_at` |

- 两者都分批删除（观测 1000 行/批、业务 500 行/批），失败仅 warn 不抛，下一轮调度重试；
  cron 错开（03:00 / 03:30）。
- 业务数据与日志分开配的理由：归档是 `recall_history` 的数据源，`goal` 含 objective/summary
  正文，和「丢了不心疼」的日志共用保留期会把两件事混在一起。
- `goal` 只清终态（`SUCCEEDED` / `FAILED`）且必须先级联删检查点——只删 goal 会留下既不能被
  resume、也查不到归属的 `state_json`。
- 删除会话时级联清：`session_messages`（快照）、`session_summary`（滚动摘要）、渠道绑定；
  `session_message_archive` 按上面的保留期走，**不随会话删除**（跨会话召回要用）。

### 渠道探活告警（能报什么 / 不能报什么）

`ChannelWatchdog` 每 `app.channel-watchdog.interval-ms`（默认 5 分钟）读一次各渠道的
`ChannelHealth`，连续 `app.alert.min-failures`（默认 3）次 `DOWN` 才发告警；恢复后补一条
「已恢复」；同一渠道 `app.alert.throttle-hours`（默认 24 小时）内不重复提醒。
告警本身写 `delivery_outbox`（`source=alert`），所以发失败还会按退避重投，可在表里查。

两条判定口径值得记住：

- **只对「确认不可用」告警**：`ChannelHealth.Status` 四态里，`UNKNOWN` 不是故障。邮件渠道
  用「最近一次投递」当探针，7 天没投递只能报 `UNKNOWN`——把它当故障会每周发一条假警报。
- **告警不走故障渠道**：入队前会把 `alert-chain` 里该渠道的段删掉（`qq` 挂了走
  `email:me@example.com`）。删完为空时只记 ERROR 日志——此时确实没有可用通道。

**边界（刻意不做进程外心跳）**：本层只覆盖「进程活着、渠道发不出去」。进程/主机死亡不在
范围内，理由：单实例个人部署下进程死了你通常会自己察觉；`restart: unless-stopped` 已覆盖
崩溃拉起；外部观察者本身是需要长期维护、失效时无人监控的依赖。**残余风险**：容器一直起不来
（镜像损坏、磁盘满、端口占用）时所有通知一起停，发现方式退化为「你注意到今天没收到消息」。
将来若确实需要，接一个外部探活调 `/actuator/health` 即可，不需要改本层结构。
