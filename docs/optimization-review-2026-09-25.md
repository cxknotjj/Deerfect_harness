# 项目优化审查报告（2026-09-25）

> 范围：server/shared 全部 Java 源码 + web 全部前端源码（24 文件 ≈2600 行）。
> 方法：逐维度代码走查，每条均带 `file:line` 证据，未做推测。
> 结论体例：`[严重度] 问题 (证据)`；「正面确认」为已达标项，防止误改。

---

## 一、服务端

### 1. 配置与安全

- **[高] 全部 API 无鉴权**：无 spring-security 依赖、无任何 token 校验。公网/局域网可直连
  `POST /api/chat`（烧 LLM token）、`POST /api/providers`（热改模型映射）、
  `DELETE /api/knowledge/documents/{name}`、`GET /api/llm-calls`（读全量调用日志）
  (ProviderAdminController.java:31-39; KnowledgeAdminController.java:105; LlmCallController.java:32-42)
- **[高] 敏感信息弱默认**：napcat.api-token 默认 `napcat123`、pgvector 密码 `postgresql`、
  MySQL root 空密码、self-id 硬编码 QQ 号 (application.yaml:41-42,173,183-184)
- [中] napcat.event-secret 默认空 = 入站上报不验签（代码跳过校验），防伪造仅靠私聊白名单
  (application.yaml:186; OneBotEventController.java:116-120)
- [低] 无 CORS 配置，跨端口前端接入能力未声明，依赖隐式同源假设

### 2. 数据库

- **[高] 观测表无界增长**：llm_call_log / tool_call_log / goal / mcp_server_log 无清理或归档
  策略（全工程仅 UserProfileService 一个 @Scheduled）；deleteSession 明确不触碰调用日志
  (SessionServiceImpl.java:281-284)
- [中] 画像扫描每 5 分钟全表过滤 `profile_extracted=0 AND last_active_at < X`，V19 加列未加索引
  (UserProfileService.java:124-130; V19__session_profile_columns.sql:12-14)
- [中] ChatMemory.add 逐条 saveContext：每条消息 1 读 + 1 次全量 JSON 覆写，N 条消息 2N 次
  DB 往返 (SessionServiceImpl.java:312-316)
- [中] saveContext 无锁读-改-写（读快照→追加→整行 UPDATE），同会话并发写可互相覆盖丢消息
  (SessionServiceImpl.java:170-213)
- [低] V20/V23 轨迹列（turn_id 等 4 列）无索引，当前无查询使用，纯写入
- [低] HikariCP 零调优（默认 10 连接），编排并行 + 观测落库共用 (application.yaml:39-43)

### 3. 资源与生命周期

- [中] prefetchPool 每请求 new 单线程池用完即弃；sync/stream 两处汇合块整段重复
  (ChatServiceImpl.java:120-133, 241-252, 417)
- [中] OneBotEventServiceImpl 构造器自建 newFixedThreadPool(8)（无界队列、无 @PreDestroy），
  与 NapCatChannelConfig.onebotExecutor（4 线程/队列 50）双池职责重叠
  (OneBotEventServiceImpl.java:72-76; NapCatChannelConfig.java:31-40)
- [中] QQ 聊天超时 `future.cancel(false)` 不中断线程，僵尸任务可占满 8 线程池
  （叠加 LLM 300s 读超时）；doHandle 外层 catch 全吞 (OneBotEventServiceImpl.java:140-152)
- [低] KnowledgeRetriever 检索池队列无界（有超时取消，仅延迟堆积）(KnowledgeRetriever.java:247-262)
- [低] LlmCallRecorder 逐条单行 insert、逐条 Mono 订阅，未批处理 (LlmCallRecorder.java:43-56,88)
- 正面确认：SandboxToolProvider / McpToolProvider / KnowledgeDirectoryWatcher 均有 shutdown 与
  超时兜底；流式取消路径 StreamConnectionLimiter.doFinally 释放闭环

### 4. 健壮性

- [中] saveContext JSON 序列化失败仅 log.error 后 return，本轮消息静默丢失无补偿
  (SessionServiceImpl.java:191-197)
- [低] ChatTimeoutProperties 只覆盖 4 项 LLM 通道超时；embedding/WebTools 15s 硬编码
  (ChatTimeoutProperties.java:19-30; WebTools.java:44)
- 正面确认：LlmRouteJudge 三重兜底不抛出；UserProfileService 失败不标记下轮重试、空输出防抹画像

### 5. 代码质量

- [中] 超长类：AgentChatCaller 749 行、MultiAgentGraphAgent 645 行、ChatServiceImpl 525 行、
  OneBotEventServiceImpl 495 行
- [中] 重复块：prefetch 汇合逻辑两份、writeBackContext 两份（sync/stream 各一）、
  safeMessage/trimForLog/summarize 三处近义工具方法、PROFILE_MODEL 与 route-judge 模型串
  重复硬编码 (ChatServiceImpl.java:460,511; KnowledgeRetriever.java:266; UserProfileService.java:48)
- 正面确认：shared 模块零 server 依赖，纯 DTO/枚举

### 6. 测试盲区

- [中] OneBotEventController 零测试——HMAC-SHA1 验签 signatureValid 全逻辑无覆盖
  (OneBotEventController.java:116-139)
- [低] LlmCallController / ProviderAdminController 零测试（Service 层有测）
- 正面确认：核心链路（Chat/Session/RouteJudge/Caller/Graph/Retriever）均有专测，共 60 个测试类

---

## 二、Web 端

### 1. 状态管理（含本轮新发现）

- **[高] 切会话竞态改名（已修复并提交）**：`onRoundSucceeded`/`persist` 原用实时
  `getSessionId()`，旧流收尾时用户已切走会把新会话改错名/缓存串桶。
  修复：useChat.ts streamText 捕获发起时 `sidAtStart` + 桶引用，收尾按快照回调与落盘
  (useChat.ts:151-152,194-195; App.vue:55)
- [中] 多标签页竞态：saveCache 为 read-modify-write 非原子，无 storage 事件监听/Web Locks，
  两标签页并发写后写整份赢 (chatCache.ts:80-88)
- [中] 错误无统一类型/分流：REST 错抛 `Error("HTTP xxx: body")`，网络错/HTTP 错/abort
  在 UI 层不可区分；abort 判断在 client.ts:251-253 与 useChat.ts:171 重复实现
- [低] onMeta 桶迁移未校验流是否仍活跃，stop/切会话瞬间 meta 到达有迁移窗口 (useChat.ts:150-160)
- [低] chatCache.read 把任意 object 断言 CacheShape，畸形缓存元素形状不校验 (chatCache.ts:36,76)
- 正面确认：全库 0 处 any、strict:true；JSON.parse 均有 try-catch；缓存有双上限 + quota 降级

### 2. 健壮性与反馈

- **[高] 静默失败**：fetchPage / useAgents.load 仅 try/finally 无 catch，列表加载失败
  纯白屏无任何提示 (useSessions.ts:33-49; useAgents.ts:14-22; App.vue:60-61)
- [低] SSE 按 `\n` 切行，服务端发 `\r\n` 时 data 载荷尾部残留 `\r` (client.ts:141-144,236-241)

### 3. 性能

- [中高] 流式 O(n²) 重渲染：每 token 对整条回复重跑 marked.parse + DOMPurify + 2 次全文正则
  (MessageBubble.vue:24-45)；ChatWindow 对 messages `deep:true` watch 每 token 触发 O(n) 遍历
  (ChatWindow.vue:34-44)
- [中] 消息列表无虚拟化，200 条上限全量 v-for（含每条 markdown DOM）(ChatWindow.vue:132-142)
- [中] TraceView 一次性全量拉 200 LLM + 200 工具调用，无分页 (client.ts:87-94; TraceView.vue:50)；
  ChatWindow.loadTokens 每轮流结束 1s 后全量重拉 (ChatWindow.vue:83-90)
- [低] TraceView/ChatWindow 静态 import 未懒加载（总 JS 172KB，收益有限）
- 正面确认：dist 总 1.2M（JS 172KB + CSS 22KB），体积健康

### 4. 可达性与 UX

- **[高] 会话项键盘不可达**：`li @click` 无 tabindex/role/keydown，键盘用户无法切换会话
  (SessionList.vue:91-97)——web/TODO.md 已列存量项
- [中] 抽屉无焦点管理：开不移入、关不归还、无 focus trap，仅 Escape 关闭已实现 (App.vue:167-189)
- [低] 图标按钮（✕/⧉/↻/赞踩）仅 title 无 aria-label (MessageBubble.vue:109-149)
- [低] reduced-motion 清单缺 agent-menu-in 下拉动画 (main.css:749)；滚动条 thumb hover 硬编码
  `#33404f` 不走 token (main.css:81)；`meta color-scheme` 静态硬编码 dark (index.html:6)
- 正面确认：AgentSelect 有完整 listbox/option + aria + 键盘导航；v-html 唯一使用点经
  DOMPurify 消毒、代码语言标签白名单——XSS 面干净

### 5. 工程化

- **[高] 零测试**：无 test script、无任何 spec 文件（relativeTime 注释自称「便于单测」却无测试）
  (package.json:6-11)
- [中] 无 ESLint/Prettier 配置 (package.json:17-22)
- [中] `build` 不含 typecheck（未串 vue-tsc，typecheck 是独立 script）(package.json:8-10)
- [中] API 路径硬编码相对 `/api/...`，无 `VITE_API_BASE`，生产必须同域反代 (client.ts:39,161)
- [低] index.html 无 description/CSP

---

## 三、遗留未落地项（历史会话/TODO 存量）

1. **Tavily MCP 405 噪音未处理**：tavily streamable 端点不支持 GET SSE 流，SDK
   `LifecycleInitializer` 每 30 分钟 WARN 全堆栈（已实证 logger 名）。一行配置待加：
   `io.modelcontextprotocol.client.LifecycleInitializer: ERROR`（application.yaml logging.level）
2. web/TODO.md 存量：键盘可达性、vitest 单测、部署方案（nginx or server 托管）+ CI/CD
3. dashscope 供应商偶发 I/O error / 120s 流超时——重试与降级链路已兜底，属环境噪音

---

## 四、建议实施顺序（跨端统一排）

| 批次 | 事项 | 理由 |
|---|---|---|
| P0 | API 鉴权（最小 token Filter，覆盖 /api/chat、/providers、/knowledge） | 公网裸奔烧钱写库，当前最大风险 |
| P0 | Tavily 405 降噪（一行配置） | 成本极低，日志噪音每 30 分钟刷屏 |
| P1 | 观测表保留期清理 + 画像扫描索引（一个 Flyway + 一个 @Scheduled） | 消两处无界增长/全表扫 |
| P1 | web 静默失败反馈 + 键盘可达会话项 | 核心路径可用性 |
| P2 | QQ 渠道线程池收口（删内部 8 线程池 + cancel(true)） | 僵尸任务风险，单机自用可缓 |
| P2 | 流式渲染 O(n²) 优化（增量 markdown / 节流 sanitize） | 长回复卡顿感知来源 |
| P3 | saveContext 原子化、观测落库批处理、vitest 引入、ESLint、重复块收编 | 体验与工程质量 |
