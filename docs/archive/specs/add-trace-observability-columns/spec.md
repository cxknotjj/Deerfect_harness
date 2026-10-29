# 轨迹观测字段扩展 — 服务端影响评估与实施 Spec

## Why
轨迹 tab 当前只能显示模型名/耗时/token,缺少输出内容、首 token 延迟、缓存命中、
轮次与调用关联等观测维度。本 spec 逐字段评估**新增观测列对服务端的影响面**,
并给出分期实施方案。

## 服务端现状(影响评估的基线)

**写入链路**(加字段必经之路):

```
调用出口                        观测值对象              落库
─────────────────────         ──────────────         ─────────────────────────
AgentChatCaller(call/stream) → LlmCallObserver ─┐
GeneralAssistantAgent(路径A) ──────────────────┤→ new LlmCallLog(record,14字段)
LlmRouteJudge(路由判定)     ──────────────────┤      ↓
UserProfileService(记忆画像)──────────────────┘  LlmCallRecorder.record()
                                                      ↓ boundedElastic 异步
                                                 doInsert → mapper.insert
```

关键事实(决定影响上限):
1. **观测永不影响主链路**:`LlmCallRecorder` 落库走 `boundedElastic` 异步,
   异常仅 warn 不传播——加列只改"异步侧组装",主链路零新增失败面
2. **record 签名扩散**:`LlmCallLog` 为 14 字段不可变 record,加字段需同步修改
   5 处 `new LlmCallLog(` 调用点(Observer / GeneralAssistantAgent / LlmRouteJudge /
   UserProfileService / 单测),编译器兜底,机械改动
3. **查询接口零改动**:`LlmCallController` / `ToolCallController` 直接序列化实体,
   新列自动带出,前端宽容忽略未知字段
4. **迁移机制**:Flyway,当前版本 V19,新列走 V20 全 NULL 兼容,存量数据无感

## What Changes(逐字段影响评估)

### P0-1 `output_summary`(LLM 输出内容摘要) — 影响小,收益最大
- **服务端影响**:
  - 迁移:`llm_call_log` 加 `output_summary VARCHAR(500)`(截断 200 字符存储,
    预留余量)
  - 数据已在手:`LlmCallObserver.okStream` 的 `content` 参数即流式全文,
    路径 A / SYNC 通道响应处同样已持有回复文本——**不新增任何采集链路**,
    仅 Observer→record→doInsert 传递 + 复用既有 `truncate` 模式
  - route-judge / memory-profile 等非对话调用:输出为 route JSON / 画像文本,
    统一截断落库(可观测其输出,无副作用)
- **风险**:流式失败时无 content,落 NULL(与现状 errorMsg 同语义);无
- **前端收益**:轨迹 ASSISTANT 行有实际回复片段可看

### P0-2 `first_token_ms`(首 token 延迟,毫秒) — 影响中,需动流装配
- **服务端影响**:
  - 迁移:`llm_call_log` 加 `first_token_ms BIGINT` NULL
  - 唯一需要动"采集时机"的字段:`AgentChatCaller` 流装配处对 token 流
    `doOnNext` 首元素时记录 `System.currentTimeMillis() - start`,经
    `LlmCallObserver.okStream` 新参传入
  - **SYNC 通道恒 NULL**(阻塞调用无首 token 概念);失败调用 NULL
  - Observer 签名扩散:okStream 加 1 参(仅 AgentChatCaller 一个调用方)
- **风险**:doOnNext 里加一个"首元素时赋值"的原子动作,无网络/IO,不引入
  背压或时序问题;赋值放在组装期局部变量,线程安全由 Reactor 串行保证
- **前端收益**:TTFT 直接显示,tok/s = completionTokens/(durationMs-first_token_ms)
  前端可推导

### P0-3 `cached_tokens`(缓存命中 token) — 影响小,尽力采集
- **服务端影响**:
  - 迁移:`llm_call_log` 加 `cached_tokens INT` NULL
  - 采集口:`Usage.getNativeUsage()` 取供应商原生 usage,DashScope/OpenAI 兼容
    响应中为 `prompt_tokens_details.cached_tokens`;反射/类型探测取值,
    **取不到(供应商不支持/结构变化)则 NULL,不抛错**
  - 与 tokensEstimated 互斥语义:估算流式调用无缓存信息,NULL
- **风险**:唯一的"结构不确定"字段——原生 usage 类型随 Spring AI 版本/供应商
  变化,故按"尽力采集"设计,探测失败静默 NULL,零主链路影响
- **前端收益**:缓存命中率(轨迹详情或 chips)

### P1-4 `turn_id`(轮次标识) — 影响大,暂缓
- **服务端影响**(为什么贵):
  - `ChatService` 每轮生成 turnId,需穿透 ChatService→AgentService→Agent→
    ChatClient 调用链(Reactor context 或 ThreadLocal——前者才兼容流式)
  - 三入口(CLI/QQ/web)全部经过该链路,传递点 ~5-6 处
  - `tool_call_log` 同样要加列,ToolCallTracer 侧也要拿到 turnId(工具执行在
    独立回调线程,Reactor context 不直达,需要 ThreadLocal 桥接)
- **价值**:「N 轮 M 步」统计、条带按轮分段——单轮对话场景价值有限
- **结论**:暂缓,等做多轮工具循环展示时一并做

### P1-5 `trace_id` / `parent_id`(跨 agent 调用关联) — 影响大,暂缓
- **服务端影响**:同 turn_id 的上下文传递问题,且需定义编排期 span 语义
  (lead 的 LLM 调用 = parent,researcher 的调用 = child),动 multi-agent
  编排图内部
- **价值**:SUBTOOL 层级展示/调用树——仅多 agent 编排场景可见
- **结论**:暂缓,与 turn_id 同批

### 通用影响项(所有字段共享)
| 影响面 | 评估 |
|-------|------|
| 主链路延迟 | **零增加**:采集均为内存赋值/已持有数据,落库本就异步 |
| 失败面 | **零增加**:Recorder 异常仅 warn 的既有约束覆盖新字段 |
| 存储增量 | 每行约 +0.5KB(摘要 200 字符 + 数值列);观测表无清理策略,量大后需另议(非本次范围) |
| 兼容性 | 新列全 NULL 兼容,存量行不回填;接口自动带出,旧前端无感 |
| 测试 | LlmCallRecorderTest 补新字段断言;record 签名变更致 1 处测试机械修改 |

## 影响汇总(结论)
- **P0 三字段(output_summary / first_token_ms / cached_tokens)**:
  服务端改动收敛在 观测层(Observer/Recorder/record)+ 1 处流装配打点 +
  1 个 Flyway 迁移,主链路零影响,低风险
- **P1 两字段(turn_id / trace_id)**:动 ChatService 到工具回调的全链路
  上下文传递,影响面跨三入口,收益仅在多轮/多 agent 展示——建议另立 spec

## Impact
- Affected specs: add-web-ui-features(轨迹 tab 数据源)、add-tool-call-mcp-logging(观测写入链路)
- Affected code:
  - `server/.../db/migration/V20__llm_call_log_trace_columns.sql`(新)
  - `LlmCallLog`(record 加 3 字段)、`LlmCallObserver`(传递+首token参)、
    `LlmCallRecorder.doInsert`(3 列写入)、`AgentChatCaller`(流首 token 打点)、
    `GeneralAssistantAgent` / `LlmRouteJudge` / `UserProfileService`(record 调用点机械适配)
  - `web/.../TraceView.vue` + `api/types.ts`(消费新字段,可选)
  - 测试:`LlmCallRecorderTest`

## ADDED Requirements

### Requirement: LLM 输出摘要采集
llm_call_log SHALL 新增 output_summary 列,成功调用(流式/SYNC)落回复文本
前 200 字符,失败调用落 NULL;采集 SHALL 复用调用出口已持有的文本,不新增
主链路操作。

#### Scenario: 流式调用落摘要
- **WHEN** 一次流式聊天正常结束
- **THEN** llm_call_log 新行的 output_summary 为回复前 200 字符

### Requirement: 首 token 延迟采集
llm_call_log SHALL 新增 first_token_ms 列;流式成功调用 SHALL 在首个 token
到达时打点落库,SYNC 调用与失败调用为 NULL;打点 SHALL 仅为内存赋值。

#### Scenario: 流式调用落 TTFT
- **WHEN** 流式调用首个 token 到达
- **THEN** 该次记录的 first_token_ms 为 start 到首 token 的毫秒数

### Requirement: 缓存命中采集
llm_call_log SHALL 新增 cached_tokens 列;Recorder SHALL 从 Usage 原生结构
尽力解析缓存命中 token,解析失败 SHALL 静默落 NULL 且不抛错。

#### Scenario: 供应商不支持缓存信息
- **WHEN** 原生 usage 无缓存字段或结构不符
- **THEN** cached_tokens 落 NULL,该次调用记录其余字段正常

### Requirement: 观测零主链路影响
新字段的采集与落库 SHALL 遵循既有「观测永不影响主链路」约束:不阻塞、不抛错、
不新增网络/IO;单测 SHALL 覆盖新字段写入与截断。

#### Scenario: 落库失败不传播
- **WHEN** 新列写入因任何原因失败
- **THEN** 仅 warn 日志,主链路响应不受影响
