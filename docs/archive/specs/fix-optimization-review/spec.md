# 优化审查修复 Spec（fix-optimization-review）

## Why
docs/optimization-review-2026-09-25.md 审查出服务端/web 端共 20+ 项问题。
本 spec 圈定本轮修复范围（可明确落地、风险可控的项），逐项「修复 → 测试 →
独立 commit」，修复完全部推送由用户另行指示。

## 范围内（8 项）

### S1 Tavily MCP 405 噪音（P0，一行）
- application.yaml `logging.level` 加 `io.modelcontextprotocol.client.LifecycleInitializer: ERROR`
  （带注释：tavily streamable 端点不支持 GET SSE 流，405 为预期；POST 路径 SDK 自行降级）
- 验证：server 全量测试 + 重启后日志无该 WARN 刷屏

### S2 观测表保留期清理 + 画像扫描索引（P1）
- **V24 迁移**：session 表加 `KEY idx_profile_scan (profile_extracted, last_active_at)`；
  llm_call_log / tool_call_log 各加 `KEY idx_created_at (created_at)`（清理依据列）
- **@Scheduled 清理**（新类 ObservabilityLogCleaner，收在 service/impl 或 config/logging 旁）：
  每日 1 次，按 `app.observability.retention-days`（默认 90，0=禁用）删 llm_call_log /
  tool_call_log 早于阈值的行；**分批删除**（LIMIT 循环，每批 1000，遵循批处理约定）；
  删除行数仅 info 单行汇总；失败仅 warn 不抛
- 验证：迁移应用成功；单测覆盖「retention=0 不删」「到期行被删、保留期内不删」（mock mapper）

### S3 QQ 渠道线程池收口（P2）
- 删除 OneBotEventServiceImpl 内部 `newFixedThreadPool(8)`，统一改用
  NapCatChannelConfig.onebotExecutor（4 线程/有界队列 50，已有 @PreDestroy）
- 聊天超时 `future.cancel(false)` → `cancel(true)`（中断底层 LLM 调用，防僵尸任务）
- 验证：OneBotEvent 相关既有测试全绿；无行为变化（仍是异步处理 + 超时放弃回复）

### S4 API 鉴权（P0）
- **最小 token Filter**（新 ApiTokenFilter，OncePerRequestFilter）：
  - 配置 `app.security.api-token`（默认空 = 不启用，本地单机零影响；配置后启用）
  - 启用时校验 `X-API-Token` 头（常量时间比较），失败 401
  - **豁免路径**：`/onebot/**`（自有 HMAC 验签）、`/error`；其余 /api/** 全覆盖
- **web 接入**：`VITE_API_TOKEN` 构建时注入，client.ts 统一为 REST 与 SSE fetch 加
  `X-API-Token` 头（空值不加，本地默认关时零改动）
- CLI/QQ 进程内调用不经 HTTP，无需改动
- 验证：Filter 单测（启用+对 token/错 token/无 token、禁用放行、豁免路径放行）

### W1 web 静默失败反馈（P1）
- fetchPage / useAgents.load 加 catch：接 App.vue 既有 showTip 轻提示
  （经 hooks/onError 回调传入，避免组合函数反向依赖视图）；失败不中断后续重试
- 验证：npm run typecheck + build

### W2 会话项键盘可达（P1）
- SessionList.vue 会话 li：加 `tabindex="0"` + `role="button"` + Enter/Space 触发选中 +
  `:aria-label`（会话名+时间）；删除按钮补 `aria-label`；焦点态样式复用 hover token
- 验证：typecheck + build（键盘行为手测）

### W3 web 小修一批（低危杂项，一个 commit）
- abort 判断去重：client.ts 导出 `isAbortError(e)`，useChat 复用
- SSE `\r\n` 容错：data 载荷 trim 尾部 `\r`
- package.json `"build"` 串上 `vue-tsc --noEmit &&`（构建即类型检查）
- CSS：滚动条 thumb hover 硬编码色改 var；reduced-motion 清单补 agent-menu-in

## 范围外（明确不做，理由）
- 超长类拆分重构：纯结构无行为收益，单独立项
- saveContext 原子化/补偿：涉及并发设计决策，单独立项
- 流式 O(n²) 渲染优化：需增量 markdown 方案选型，单独立项
- HikariCP 调优 / CORS / VITE_API_BASE / CSP：依赖生产部署形态确定
- 多标签页 storage 竞态、TraceView 分页：需要产品决策，暂缓
- 观测表轨迹列索引：当前无查询使用，等查询出现再加

## Impact
- Affected code：application.yaml、V24 迁移（新）、ObservabilityLogCleaner（新）、
  ApiTokenFilter（新）、OneBotEventServiceImpl、NapCatChannelConfig（如需暴露 executor）、
  web：client.ts / useSessions.ts / useAgents.ts / useChat.ts / App.vue / SessionList.vue /
  MessageBubble.vue（无改动预期）/ main.css / package.json
- 测试：server 全量 `mvn -pl server -am test`（每项修复后必跑）；web `npm run build`
  （含 typecheck）
- 每项独立 commit（Conventional Commits 中文描述，-F 文件防乱码）；不推送（另行指示）
