# 轨迹页增强：回复时间 / 重试可见性 / token 分向总量 / 缓存命中率 Spec

## Why
轨迹页当前缺少四个观测维度:调用的发生时间、重试次数(后端未采集)、
token 输入/输出分向总量、缓存命中率。其中重试次数需要后端加字段,
其余为纯前端展示。

## What Changes

### 1. 回复时间展示(纯前端)
- **前端**:轨迹行 meta 尾部展示调用时间(后端 `createdAt` 已有),
  当日消息显示 `HH:mm:ss`,非当日显示 `MM-dd HH:mm`;与既有 `relativeTime`
  语义区分——轨迹是观测回看,用绝对时间
- **后端**:零改动

### 2. 重试次数与最大重试次数(后端加字段,复用三字段同模式)
**评估**:`LlmRetry` 已有 `AttemptListener.onAttempt(attempt, durationMs, error)`
每次尝试回调,且观测层确实每次尝试落一条 llm_call_log,但**第几次尝试**
与**最大尝试次数**没有落库——同会话多条记录无法区分「重试」还是「多轮调用」。

- **后端**:
  - 迁移:`llm_call_log` 加 `attempt INT` / `max_attempts INT`
    (V21;非重试通道 SYNC 调用记 1/1,路由判定与画像为 null)
  - `LlmCallLog` record 加 2 字段(17→19),签名扩散与三字段批次同模式:
    AgentChatCaller `CallContext.ok/error` 加参,3 处调用点取重试循环的
    attempt 计数与 `retry.maxAttempts()`;GeneralAssistantAgent(路径 A 无重试)
    记 1/1;LlmRouteJudge / UserProfileService 补 null
  - **stream 通道**:streamWithAssembly 自己的 for-attempt 循环内直接可取
- **前端**:`LlmCallItem` 加 2 字段;轨迹行 meta 显示「尝试 2/3」——
  仅当 attempt > 1 时显示(未发生重试的行不显示,避免噪声)
- **风险**:采集均为已持有数据,零主链路影响;迁移全 NULL 兼容

### 3. token 输入/输出总量 + 缓存命中率(纯前端)
- **前端**:轨迹统计 chips 拆分为「输入 X tok」「输出 Y tok」两个 chip
  (Σ promptTokens / Σ completionTokens),原合计 chip 移除或保留待定(取移除,
  分向已可心算合计);新增「缓存命中 Z%」chip——Σ cachedTokens / Σ promptTokens,
  分母为 0 或无缓存数据时不显示
- **后端**:零改动(数据已采集)

## 分期
- **P0**(纯前端):1 回复时间 + 3 token 分向总量/缓存命中率
- **P1**(后端加字段):2 重试可见性

## Impact
- Affected specs: add-trace-observability-columns(三字段批次,本批模式复用)
- Affected code:
  - P0:`web/src/components/TraceView.vue`、`api/types.ts`(无新字段)
  - P1:`V21__llm_call_log_attempt_columns.sql`(新)、`LlmCallLog`/`LlmCallLogEntity`、
    `LlmCallObserver`、`AgentChatCaller`、`GeneralAssistantAgent`、
    `LlmRouteJudge`/`UserProfileService`、`web` types + TraceView、`LlmCallRecorderTest`

## ADDED Requirements

### Requirement: 轨迹行展示调用时间
轨迹页每条调用记录 SHALL 展示其发生时间(当日 HH:mm:ss,跨日 MM-dd HH:mm)。

#### Scenario: 查看调用时间
- **WHEN** 轨迹页渲染任意调用行
- **THEN** meta 区展示该记录 createdAt 的绝对时间

### Requirement: 重试可见性
llm_call_log SHALL 记录每次调用的 attempt(第几次尝试)与 max_attempts(上限);
轨迹页 SHALL 仅对发生重试的调用(attempt > 1)展示「尝试 n/m」。

#### Scenario: 重试后的记录
- **WHEN** 某次调用第 2 次尝试成功(max 3)
- **THEN** 该条记录 attempt=2、max_attempts=3,轨迹行显示「尝试 2/3」

#### Scenario: 无重试的调用
- **WHEN** 调用一次成功(attempt=1)
- **THEN** 轨迹行不显示尝试标记

### Requirement: token 分向总量与缓存命中率
轨迹页统计区 SHALL 展示输入 token 总量、输出 token 总量与缓存命中率。
缓存命中率的口径 SHALL 为 Σ cachedTokens / Σ promptTokens(全量分母:
供应商未返回缓存信息的调用视为未命中摊入,数字偏低但反映真实成本节约);
 SHALL 在全流无任何一条非 NULL cachedTokens 时不显示(样本不足不展示)。

#### Scenario: 有缓存命中的会话
- **WHEN** 会话内多次调用累计 prompt 10000 tok、cached 6000 tok
- **THEN** 统计区显示「输入 X tok」「输出 Y tok」「缓存命中 60%」

#### Scenario: 无缓存数据的会话
- **WHEN** 会话内所有调用的 cachedTokens 均为 NULL
- **THEN** 统计区不显示缓存命中率 chip
