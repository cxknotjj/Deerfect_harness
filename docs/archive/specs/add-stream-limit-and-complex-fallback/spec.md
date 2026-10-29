# 流式连接数限制与 COMPLEX 降级重答 Spec

对应 `docs/HARNESS_TODO.md` P0「并发 / 资源控制」剩余部分（L52-54）：线程池隔离已完成，本 spec 补齐流式连接数限制、超时参数化、COMPLEX 编排失败降级 SIMPLE 单模型重答。

## Why
当前 `/api/chat/stream`、`/api/chat/resume` 无并发上限，恶意或异常客户端可无限开流耗尽线程池；LLM 三处超时（连接 10s/读 300s/流式 idle 120s）硬编码在 Java 常量里，调优需改代码重编；multi-agent 编排失败只发一条「已回退」进度行即结束（用户拿不到任何回答），需降级为 SIMPLE 单模型重答兜底。

## What Changes
- 新增流式连接限流器：stream/resume 两入口 tryAcquire，流终结（doFinally）释放；超限抛异常映射 429；`app.chat.max-stream-connections` 配置（0 = 不限制，遵循项目 0 语义）
- 三处 LLM 超时参数化到 `app.chat.timeouts.*`（connect/read/stream-idle 秒值），`ChatClientFactory`/`AgentChatCaller` 改读配置，未配置时用现值兜底（行为不变）；**BREAKING**：无（默认值=现硬编码值）
- COMPLEX 编排失败降级：流式与同步路径，编排自身失败（非客户端断开）时用会话绑定 Agent 单模型重答一次；重答成功 Goal 置 SUCCEEDED，重答也失败才 FAILED 并保留原编排异常信息；进度行透出降级事实；`app.chat.complex-fallback.*` 开关配置，重答自身不递归降级
- `GlobalExceptionHandler` 新增并发超限异常映射 429
- 完成后勾选 HARNESS_TODO L52 并注明剩余项

## Impact
- Affected specs: 无既有 spec
- Affected code:
  - 新增 `config/StreamConnectionLimiter.java`（@Component，AtomicInteger 计数）
  - 新增 `config/ChatTimeoutProperties.java`（@ConfigurationProperties 纯绑定载体，字段无初始化值，遵循 ContextBudgetProperties 模式）
  - `config/agent/ChatClientFactory.java`：CONNECT/READ_TIMEOUT 改从 props 读、null 兜底现值
  - `agent/AgentChatCaller.java`：STREAM_IDLE_TIMEOUT 改从 props 读
  - `service/impl/ChatServiceImpl.java`：streamReactive/resume 包裹 limiter；编排失败降级重答
  - `service/impl/AgentServiceImpl.java`：同步路径（executeSync/executeReactive）失败降级钩子
  - `exception/GlobalExceptionHandler.java`：新增 429 映射
  - `src/main/resources/application.yaml`：新增 `app.chat.max-stream-connections`、`app.chat.timeouts.*`、`app.chat.complex-fallback.*`
  - 测试：StreamConnectionLimiterTest（计数/超限/释放）、降级重答用例（mock 编排失败→重答成功/重答再失败）、超时绑定用例

## ADDED Requirements

### Requirement: 流式连接数限制
系统 SHALL 对 `/api/chat/stream` 与 `/api/chat/resume` 的活跃 SSE 流计数限制：进入前 tryAcquire，失败抛并发超限异常（HTTP 429）；流终结（complete/error/cancel）doFinally 释放。上限 `app.chat.max-stream-connections`，0 = 不限制。计数不含同步 `/api/chat`（已有 goalExecutor 有界池兜底）。

#### Scenario: 超限拒绝
- **WHEN** 活跃流数已达上限且新流式请求到达
- **THEN** 立即返回 429，不占用线程池排队，已有流不受影响

#### Scenario: 流终结释放
- **WHEN** 任一活跃流完成、出错或客户端断开
- **THEN** 计数减一，后续请求可进入

#### Scenario: 不限制模式
- **WHEN** `max-stream-connections: 0`
- **THEN** 不做任何计数，行为与现状一致

### Requirement: LLM 超时参数化
三处超时 SHALL 从 `app.chat.timeouts.*` 读取（秒，纯绑定载体字段无初始化值）：
- `connect-timeout-seconds`（默认兜底 10，ChatClientFactory）
- `read-timeout-seconds`（默认兜底 300，阻塞调用读超时）
- `stream-idle-timeout-seconds`（默认兜底 120，AgentChatCaller 流式空闲超时）

yaml 显式写全默认值；未配置键时 Java 兜底现值，行为与现状完全一致。

#### Scenario: 默认行为不变
- **WHEN** yaml 未新增任何 timeouts 键启动
- **THEN** 三处超时与现硬编码值一致（10/300/120）

#### Scenario: 配置生效
- **WHEN** `read-timeout-seconds: 60` 后调用阻塞 LLM 且端点挂起
- **THEN** 约 60s 读超时失败（经 LlmRetry 既有语义处理）

### Requirement: COMPLEX 编排失败降级重答
multi-agent 编排自身失败时（Goal FAILED / 流式编排异常），系统 SHALL 降级为 SIMPLE 单模型重答一次：
- 重答 Agent = 会话绑定 Agent（与 SIMPLE 路径一致），复用既有会话记忆与 LlmRetry
- 触发条件：编排异常或 Goal 置 FAILED；**客户端断开（cancelled）不触发**（对断开的客户端重答无意义）
- 重答成功 → Goal 置 SUCCEEDED，输出按正常回答流式透出，meta 状态 SUCCEEDED
- 重答失败 → Goal FAILED，错误信息保留编排原始异常 + 重答失败原因
- 进度行透出降级事实（如「编排异常，降级为单模型重答」），CLI 可见
- 重答自身不再递归降级（一层兜底）
- 开关 `app.chat.complex-fallback.enabled`（默认 true）；同步与流式路径行为一致

#### Scenario: 编排失败降级成功
- **WHEN** COMPLEX 编排中某子任务导致图执行异常，且会话 Agent 可用
- **THEN** 先发降级进度行，随后单模型流式重答，最终 meta SUCCEEDED

#### Scenario: 重答也失败
- **WHEN** 编排失败且重答的 LLM 调用也失败
- **THEN** Goal FAILED，meta FAILED，错误信息含编排与重答两段原因

#### Scenario: 客户端断开不降级
- **WHEN** 客户端断开导致编排取消
- **THEN** 不发起重答（Goal 按既有取消语义置 FAILED）

#### Scenario: 开关关闭
- **WHEN** `complex-fallback.enabled: false` 且编排失败
- **THEN** 行为与现状一致（仅「已回退」进度行，Goal FAILED，无重答）

## MODIFIED Requirements
无

## REMOVED Requirements
无

## 范围外（防蔓延）
- RAG 旁路超时治理（HARNESS_TODO L76，独立条目）
- 接口限流防刷与 Resilience4j 重试（HARNESS_TODO L50，另一条目；本 spec 不引入新依赖）
- 同步 /api/chat 的显式连接数限制（线程池已兜底）
