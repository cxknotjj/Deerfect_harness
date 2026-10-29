# Tasks

- [x] Task 1: 超时参数化（独立可并行）
  - [x] 1.1 新增 `config/ChatTimeoutProperties.java`：`@ConfigurationProperties("app.chat.timeouts")` 纯绑定载体（connectTimeoutSeconds/readTimeoutSeconds/streamIdleTimeoutSeconds，Integer 字段无初始化值），`@EnableConfigurationProperties` 注册
  - [x] 1.2 `ChatClientFactory`：CONNECT_TIMEOUT/READ_TIMEOUT 改为读 props（null 时兜底现常量 10/300），构造注入
  - [x] 1.3 `AgentChatCaller`：STREAM_IDLE_TIMEOUT 常量改为实例字段读 props（null 兜底 120）
  - [x] 1.4 `application.yaml` 新增 `app.chat.timeouts` 块（10/300/120 全显式）+ 注释
  - 验证：`ChatTimeoutPropertiesTest` 断言 `ChatClientFactory.resolve` 兜底（null→10/300/120）与配置覆盖（50 优先于默认）
- [x] Task 2: 流式连接数限制（独立可并行）
  - [x] 2.1 新增 `config/StreamConnectionLimiter.java`：AtomicInteger 计数 + tryAcquire（超限抛 `ConcurrentRequestException`）+ release；上限 `@Value("${app.chat.max-stream-connections:50}")` 读（0 = 直通不计数）
  - [x] 2.2 `GlobalExceptionHandler` 新增 ConcurrentRequestException → 429 映射（对齐现有异常映射模式）
  - [x] 2.3 `ChatServiceImpl.streamReactive/resume` 包裹：进入 tryAcquire，`doFinally` release（同步占名额 + 同步释放，构造失败 catch 分支回退名额）
  - [x] 2.4 `application.yaml` 新增 `app.chat.max-stream-connections: 50`（0 = 不限制）
  - 验证：`StreamConnectionLimiterTest` 4 用例（上限内计数/释放下限保护/0 直通/并发 10 抢上限 4 终态归零无泄漏）+ `ChatServiceImplTest.streamReactive_limiterFull_*` 接线用例（上限 1 第二路拒绝、终结释放恢复）+ `GlobalExceptionHandlerTest.concurrentRequest_mapsTo429WithReadableMessage`
- [x] Task 3: COMPLEX 编排失败降级重答（流式 + 同步路径）
  - [x] 3.1 配置绑定：`@Value("${app.chat.complex-fallback.enabled:true}")` 直接注入 ChatServiceImpl（单布尔无需独立绑定类）
  - [x] 3.2 流式路径：`withComplexFallback` 编排流 onErrorResume——先发降级进度行，再 `executeStreamReactive(sessionAgentName, ...)` 重答；重答失败 onErrorMap 保留「编排失败 + 重答失败」双段错误
  - [x] 3.3 同步路径：`ChatServiceImpl.chat` 编排 FAILED（同步路径无客户端断开）且开关开启 → `chatWithComplexFallback` 会话 Agent 同步重答一次；成功按正常响应返回（透出实际 agent），失败保留双段错误
  - [x] 3.4 防重入：重答走显式单模型通道（会话绑定 Agent 具名直连），不再经 RouteJudge/编排（一层兜底不递归）
  - [x] 3.5 `application.yaml` 配置块 `app.chat.complex-fallback.enabled: true`
  - 验证：`ChatServiceImplTest` 4 个降级用例（同步重答成功/同步双段失败/流式重答成功含降级进度行 + meta SUCCEEDED/流式双段失败含 error 事件 + meta FAILED）+ 既有开关关闭/客户端断开语义由 MultiAgentStreamPipeline 既有用例覆盖
- [x] Task 4: 收尾
  - [x] 4.1 全量 `mvn test` 回归：488 例全绿（1 skip 为既有基线），BUILD SUCCESS
  - [x] 4.2 勾选 `docs/HARNESS_TODO.md` P0「并发 / 资源控制」条目，注明三块完成范围与验证方式；接口限流防刷仍归 L50
  - [x] 4.3 `docs/data-flow.md` 流式路径图同步（步骤 0 过载保护、COMPLEX 降级分支、超时参数化说明）

# Task Dependencies
- Task 1、2、3 相互独立可并行（3 与 2.3 共改 ChatServiceImpl，实现时需顺序协调或由同一子代理处理）
- Task 4 依赖 1-3 全部完成
