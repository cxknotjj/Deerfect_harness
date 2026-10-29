# Checklist

## 超时参数化
- [x] `ChatTimeoutProperties` 纯绑定载体：字段无初始化值，yaml 注入；未配置键时 ChatClientFactory/AgentChatCaller 兜底现值（10/300/120），行为与现状一致（`ChatTimeoutPropertiesTest` 断言 `resolve(null, default)` 三档兜底）
- [x] 配置覆盖生效：yaml 配置值优先于兜底默认（`ChatTimeoutPropertiesTest` 断言 `resolve(50, 300)` → 50s）
- [x] 默认配置下全量回归通过（超时语义零变化；488 例全绿）

## 流式连接数限制
- [x] stream/resume 进入前 tryAcquire，流终结（complete/error/cancel）doFinally 释放，计数归零（`ChatServiceImplTest.streamReactive_limiterFull_secondRequestRejected_thenReleasedAfterTermination` 验证占用→拒绝→释放→恢复全链）
- [x] 超限请求立即 429（`GlobalExceptionHandler.handleConcurrentRequest` 映射，`GlobalExceptionHandlerTest.concurrentRequest_mapsTo429WithReadableMessage` 断言 429 + 可读提示 + warn 无堆栈），不排队不占用线程池
- [x] `max-stream-connections: 0` 时零行为（不计数直通），与现状一致（`StreamConnectionLimiterTest.zeroLimit_meansUnlimited`）
- [x] 计数不含同步 /api/chat（limiter 仅包裹 streamReactive/resume）；并发 10 流式抢占稳定无泄漏（`StreamConnectionLimiterTest.concurrentAcquireRelease_noLeak` 终态归零断言）

## COMPLEX 降级重答
- [x] 编排失败（非断开）→ 发降级进度行 → 会话 Agent 单模型重答 → 成功按正常响应返回 / meta SUCCEEDED（`ChatServiceImplTest` 流式降级成功用例）
- [x] 重答失败 → FAILED，错误信息含编排原异常 + 重答失败两段（同步双段失败用例 + 流式双段失败用例）
- [x] 客户端断开（cancelled）不触发重答，Goal 按既有取消语义 FAILED（编排 onErrorResume 仅 error 信号触发，cancel 直接终结不进入降级；MultiAgentStreamPipeline 既有用例覆盖取消语义）
- [x] `complex-fallback.enabled: false` 时行为与现状一致（条件判断短路，不包裹 withComplexFallback）
- [x] 重答不递归降级、不经 RouteJudge/编排二次入口（重答走会话绑定 Agent 具名直连 `executeStreamReactive(sessionAgentName, ...)`，resolvedAgent 为单 Agent 名，不再命中降级条件）
- [x] 同步路径（executeSync）同样具备降级语义（`ChatServiceImpl.chat` 编排 FAILED → `chatWithComplexFallback`，同步重答成功/失败用例覆盖）

## 收尾
- [x] `mvn test` 全量回归绿：488 例（0 失败 0 错误 1 skip 既有基线），BUILD SUCCESS
- [x] HARNESS_TODO P0「并发 / 资源控制」勾选并注明完成范围；docs/data-flow.md 流式链路已同步（步骤 0 过载保护 + COMPLEX 降级分支 + 超时参数化说明）
