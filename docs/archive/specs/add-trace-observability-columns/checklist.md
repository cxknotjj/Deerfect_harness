# Checklist

## 迁移与兼容
- [x] V20 迁移执行成功,三新列全 NULL 兼容,存量行不回填
- [x] 服务启动无 Flyway 报错,旧数据查询不受影响

## 采集正确性
- [x] 流式成功调用:output_summary = 回复前 200 字符,first_token_ms > 0
      (实测 sessionId=95:outputSummary="正常",firstTokenMs=726)
- [x] SYNC 调用:output_summary 有值,first_token_ms 为 NULL(Observer 侧 0→null 口径)
- [x] 失败调用:output_summary 与 first_token_ms 均为 NULL,errorMsg 照旧
- [x] 供应商原生 usage 无缓存字段时 cached_tokens 落 NULL,无异常日志堆栈
      (实测 qwen3.7-flash 该次调用 cachedTokens=null 静默降级)
- [x] route-judge / memory-profile 调用记录三列不落异常值(补参为 null)

## 主链路安全
- [x] 采集处无网络/IO/锁,首 token 打点仅为内存赋值(compareAndSet)
- [x] 落库失败仅 warn(既有异步边界约束覆盖新字段,record_mapperFailure 测试保持通过)
- [x] server 全量测试通过(561,含 LlmCallRecorderTest 新断言)

## 前端消费(可选)
- [x] 轨迹页 LLM 行显示输出摘要;首 token/缓存命中率有值才显示
- [x] 真实发消息一轮后轨迹页立即可见新数据(浏览器实测确认)
