# Tasks

## P0:三字段落地(output_summary / first_token_ms / cached_tokens)

- [x] Task 1: Flyway 迁移 V20
  - [x] 1.1 `V20__llm_call_log_trace_columns.sql`:llm_call_log 加
        output_summary VARCHAR(500) / first_token_ms BIGINT / cached_tokens INT,全 NULL
  - [x] 1.2 实体 LlmCallLogEntity 加 3 字段
- [x] Task 2: 观测值对象与落库
  - [x] 2.1 LlmCallLog record 加 outputSummary / firstTokenMs / cachedTokens
  - [x] 2.2 LlmCallObserver:okStream/record 传递摘要与首 token;SYNC 通道摘要采集
  - [x] 2.3 LlmCallRecorder.doInsert 写 3 列(output_summary 截断 200)
  - [x] 2.4 cached_tokens 尽力解析 Usage 原生结构(OpenAiApi.Usage.promptTokensDetails),
        失败静默 NULL
  - [x] 2.5 record 签名扩散适配:GeneralAssistantAgent / LlmRouteJudge /
        UserProfileService(机械补参)
- [x] Task 3: 流式首 token 打点
  - [x] 3.1 AgentChatCaller tokenStream 管道 doOnNext 首元素记录绝对时间戳
        (AtomicLong firstTokenAt 沿 tokenStreamWithWatchdog/streamCore/streamAttempt 链传递),
        CallContext.ok 经 observer.okStream 落 TTFT
- [x] Task 4: 测试
  - [x] 4.1 LlmCallRecorderTest 补 3 字段断言(摘要截断/TTFT/缓存 NULL 兜底)
  - [x] 4.2 server 全量测试通过(561 通过)
- [x] Task 5: 前端消费(可选,小)
  - [x] 5.1 api/types.ts LlmCallItem 加 3 字段;TraceView LLM 行显示摘要
        (detail 位)与首 token/缓存(有值才显示);mock 数据补演示字段
- [x] Task 6: 端到端验证
  - [x] 6.1 真实发消息一轮,查库三列落值(outputSummary="正常"/firstTokenMs=726/
        cachedTokens=NULL 降级);轨迹页立即可见摘要与首 token

## P1(暂缓,已记 web/TODO.md,另立 spec):turn_id / trace_id 全链路上下文传递

# Task Dependencies
- Task 2 依赖 Task 1(实体字段);Task 3 依赖 Task 2(Observer 签名)
- Task 4 依赖 Task 2/3;Task 5/6 依赖 Task 4
- Task 5 为可选增强,可与 Task 6 并行
