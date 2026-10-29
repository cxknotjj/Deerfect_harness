# Tasks

## P0:纯前端展示(回复时间 / token 分向总量 / 缓存命中率)

- [ ] Task 1: 轨迹行调用时间
  - [ ] 1.1 LlmCallItem 已有 createdAt;TraceView meta 尾部展示(当日 HH:mm:ss,跨日 MM-dd HH:mm)
- [ ] Task 2: 统计区增强
  - [ ] 2.1 stats 拆分:Σ promptTokens(输入)/ Σ completionTokens(输出)两个 chip,
        替换原合计 chip
  - [ ] 2.2 缓存命中率 chip:Σ cachedTokens / Σ promptTokens,百分比一位小数;
        分母 0 或全 NULL 不显示
- [ ] 2.3 typecheck + mock 模式浏览器验证(缓存 chip 用 mock 数据可见)

## P1:重试可见性(后端加字段)

- [ ] Task 3: 后端字段与采集
  - [ ] 3.1 V21 迁移:llm_call_log 加 attempt INT / max_attempts INT,全 NULL
  - [ ] 3.2 LlmCallLog record + LlmCallLogEntity 加 2 字段;doInsert 写列
  - [ ] 3.3 CallContext.ok/error 加参:callWithRetry 重试块与 streamWithAssembly
        for-attempt 循环传 attempt 与 retry.maxAttempts();invokeAndRecord 记 1/1
  - [ ] 3.4 GeneralAssistantAgent(路径 A 无重试)记 1/1;LlmRouteJudge/UserProfileService 补 null
- [ ] Task 4: 测试与前端
  - [ ] 4.1 LlmCallRecorderTest 补 attempt/maxAttempts 断言;server 全量通过
  - [ ] 4.2 LlmCallItem 加 2 字段;TraceView 仅 attempt>1 显示「尝试 n/m」
  - [ ] 4.3 端到端:真实调用重试场景(可临时改错 key 触发一次重试)后轨迹页可见

# Task Dependencies
- Task 1/2 无依赖,可先行
- Task 3/4 为一组;Task 4.2 依赖 3.x
