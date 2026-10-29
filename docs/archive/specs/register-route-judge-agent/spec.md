# route-judge 注册进 agent 表（表驱动化）Spec

## Why
route-judge 是每条聊天请求的必经环节（SIMPLE/COMPLEX 判定），但它的模型与提示词硬编码在
`LlmRouteJudge` 常量里，与项目「agent 表配置、改库即生效」的既有理念脱节；agent 表已有
`is_internal` 内部角色机制（V9：multi-agent/lead/aggregator），route-judge 作为同级别的
基础设施角色理应同轨管理。

## What Changes
- **V22 迁移**：agent 表插入 `route-judge` 内部行（`is_internal=1`），model_provider_id
  子查询引用 `model_provider.model='qwen3.8-27b'`，prompt 搬运现 `SYSTEM_PROMPT` 常量
- **LlmRouteJudge 表驱动化**：注入 `AgentConfigProvider`，judge 时查 `route-judge` 行取
  model + prompt，替换两个硬编码常量的「生效值」来源
- **常量保留为回退默认**：查表无行/异常时回退常量，judge「失败兜底 SIMPLE」语义不变
- **token 估算修正**：`record()` 的 promptTokens 估算改用实际生效的 prompt（原用常量，
  表驱动后常量可能不代表实际值）
- 无 **BREAKING** 变更：接口 `RouteJudge.judge(String, String)` 签名不变；行为默认等价
  （迁移插入的行与常量内容一致）

## Impact
- Affected specs: 无直接依赖；`add-trace-observability-columns` 的 route-judge 观测口径
  （agent_name='route-judge' 落 llm_call_log）保持不变
- Affected code:
  - `server/src/main/resources/db/migration/V22__register_route_judge_agent.sql`（新增）
  - `server/src/main/java/com/dark/javaHarness/service/impl/LlmRouteJudge.java`（表驱动化）
  - `server/src/test/java/com/dark/javaHarness/service/impl/LlmRouteJudgeTest.java`（适配 + 新用例）
- 不受影响：AgentRegistry（is_internal=1 不注册对话 Agent）、AgentSelect 下拉
  （is_internal=0 过滤）、轻量客户端丢池逻辑、ChatServiceImpl.resolveAgent

## ADDED Requirements

### Requirement: route-judge 内部行注册
系统 SHALL 通过 V22 迁移在 agent 表注册 `route-judge` 行：`agent_name='route-judge'`、
`is_internal=1`、`status=1`、`prompt` 为现 `SYSTEM_PROMPT` 常量原文、`model_provider_id`
为 `model_provider` 表中 `model='qwen3.8-27b'` 行的主键（子查询实现，与 V1 种子数据依赖
自洽）；迁移 SHALL 幂等（INSERT IGNORE，依赖 uk_agent_name 唯一键）。

#### Scenario: 迁移后行为不变
- **WHEN** Flyway 执行 V22 且应用启动完成
- **THEN** agent 表多出 route-judge 内部行，聊天路由判定行为与迁移前完全一致
  （同模型、同提示词），web AgentSelect 下拉不出现 route-judge

#### Scenario: 幂等重放
- **WHEN** 迁移在已有 route-judge 行的库上重复执行（INSERT IGNORE）
- **THEN** 不报错、不产生重复行

### Requirement: LlmRouteJudge 表驱动化
`LlmRouteJudge` SHALL 注入 `AgentConfigProvider`，每次 judge 时查询
`getAgentConfig("route-judge")`，以配置中的 model 作为轻量客户端的模型 key、配置中的
prompt 作为判定提示词；配置缺失（无行/字段空白）或查询异常时 SHALL 回退现
`ROUTE_MODEL` / `SYSTEM_PROMPT` 常量。接口 `RouteJudge.judge(String, String)` 签名与
「判定失败兜底 SIMPLE」语义不变。

#### Scenario: 配置命中走库
- **WHEN** agent 表 route-judge 行存在且字段有效（如 model_provider_id 指向
  qwen3.8-max）
- **THEN** judge 用该模型发起轻量调用、用该行 prompt 判定（改库即生效）

#### Scenario: 无行/异常回退常量
- **WHEN** agent 表无 route-judge 行，或查询抛异常，或 model 解析为空
- **THEN** 回退 `ROUTE_MODEL` / `SYSTEM_PROMPT` 常量继续判定，不抛出、不阻塞请求

### Requirement: token 估算与观测口径一致
`LlmRouteJudge.record()` 的 promptTokens 估算 SHALL 基于本次实际生效的 prompt（表取或
回退常量）；llm_call_log 落库口径（agent_name='route-judge'、stream=false、
attempt/max_attempts 为 NULL）不变。

#### Scenario: 估算跟随实际 prompt
- **WHEN** route-judge 行的 prompt 被库中修改（长度变化）
- **THEN** 后续 judge 落库的 promptTokens 估算反映新 prompt 长度
