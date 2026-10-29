# R1 敏感信息默认值 env 化 Spec

## Why
优化审查报告 [高] 项：application.yaml 硬编码弱凭据（napcat token `napcat123`、
pgvector 密码 `postgresql`、MySQL root 空密码、QQ self-id 硬编码、event-secret 空）。
公网部署即裸奔。收口方式：全部改为环境变量注入、保留现值为回退默认——本地行为
零变化，部署时改 env 即得安全配置。

## What Changes
- datasource.password → `${MYSQL_PASSWORD:}`（本地 root 空密码语义不变）
- napcat.api-token → `${NAPCAT_API_TOKEN:napcat123}`
- napcat.event-secret → `${NAPCAT_EVENT_SECRET:}`（空 = 不验签，现状语义保留并注释风险）
- napcat.self-id → `${QQ_SELF_ID:}`（原硬编码 QQ 号移入回退默认，见实现处确认）
- pgvector 密码 → `${PGVECTOR_PASSWORD:postgresql}`
- 每处带注释：部署时应通过环境变量注入强值

## 非目标
- 不强制改本地默认值（NapCat 侧 token 需与 NapCat 服务器配置一致，改默认会断本地 QQ）
- 不引入 spring-security / 配置加密（超范围）

## Impact
- 仅 server/src/main/resources/application.yaml（config-only，无 Java 改动）
- 验证：server 全量测试（yaml 变更经 JavaHarnessApplicationTests 上下文加载兜底）
- 独立 commit
