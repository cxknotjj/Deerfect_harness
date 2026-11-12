# LLM 供应商 / 模型 / 路由与 Agent 绑定

- 范围：`deerfect-server` 的 LLM 配置模型、解析链路、Agent 绑定、失败切换、观测与管理面
- 创建：2026-11-12
- 状态：共 29 项，已完成 24 项，待办 5 项
- 规划书：[docs/design/2026-11-12-llm-provider-model-routing.md](../design/2026-11-12-llm-provider-model-routing.md)
- 前提：按全新设计落地，不保留旧 `model_provider` 表与按名查找接口的兼容层

## 进度概览

| 分类 | 已完成 | 待办 | 小计 |
| --- | --- | --- | --- |
| 数据层 | 3 | 0 | 3 |
| 解析与客户端 | 6 | 0 | 6 |
| 参数与能力 | 3 | 1 | 4 |
| 内部调用收口 | 3 | 0 | 3 |
| 接入改造 | 4 | 0 | 4 |
| 管理面 | 3 | 0 | 3 |
| 观测 | 2 | 0 | 2 |
| 清理与验收 | 0 | 4 | 4 |
| 合计 | 24 | 5 | 29 |

## 背景

现状把供应商凭证、模型名、端点地址混在 `model_provider` 一张表里，内部调用仍按模型名解析，且缺少引用完整性与失败链。本清单按规划书实现三层模型（provider → model → route）与唯一调用入口，目标是：代码只按 id 查找、配置错误启动即暴露、主模型失败可按数据切换、所有调用可按 route/model 观测。

## 里程碑

| 里程碑 | 包含条目 | 可独立提交 |
| --- | --- | --- |
| M1 数据层与解析器 | A1-A3、B1-B6 | 是（暂不接入业务） |
| M2 主链路接入 | C1-C4、E1-E4 | 是 |
| M3 内部调用收口 | D1-D3 | 是 |
| M4 管理面、观测与清理 | F1-F3、G1-G2、H1-H4 | 是 |

## 条目

### A. 数据层

- [x] A1 V20 迁移：四张新表与 Agent 改造
  - 作用：建立 provider / model / route / route_member 的实体关系，Agent 改为绑定路由
  - 位置：`deerfect-server/src/main/resources/db/migration/V20__llm_provider_model_route.sql`
  - 做法：按规划书第 4 节 DDL 建表；`agent` 增加 `route_id`、`params`、索引与外键；`llm_model` 唯一键 `(provider_id, upstream_name)`，`llm_route_member` 唯一键 `(route_id, priority)`
  - 验收：空库从 V1 顺序跑到 V20 成功；`SHOW CREATE TABLE` 四张表的外键与唯一键齐备；删除仍被引用的 provider/model 被数据库拒绝
  - 依赖：无
  - 结果（2026-11-12）：V20 已应用（`flyway_schema_history` 记录 version=20 success=1），本地库产生 3 供应商 / 12 模型 / 4 路由 / 4 成员

- [x] A2 新表实体与 Mapper
  - 作用：给解析层与校验层提供持久化入口
  - 位置：`deerfect-server/src/main/java/com/dark/javaHarness/domain/entity/`、`mapper/`
  - 做法：新增 `LlmProviderEntity` / `LlmModelEntity` / `LlmRouteEntity` / `LlmRouteMemberEntity` 与对应 `BaseMapper`；`AgentEntity` 增加 `routeId`、`params`；纯 Mockito 单测下条件构造用字符串列名，不用 lambda
  - 验收：`mvn -pl deerfect-server -am compile` 通过；实体字段与 DDL 一一对应
  - 依赖：A1
  - 结果（2026-11-12）：4 个实体 + 4 个 Mapper；`AgentEntity` 增加 `routeId`/`params`

- [x] A3 现有数据回填与种子脚本
  - 作用：把当前 12 条模型部署、13 个 Agent 迁移到新模型，保证切换后行为一致
  - 位置：V20 迁移内数据段或单独 `V20_1__llm_routing_seed.sql`
  - 做法：按供应商拆出行；为现有 Agent 建立 `main` / `fast` / `judge` 路由映射；内部功能建成 `is_system=1` 的系统 Agent；同名模型按 `(provider_id, upstream_name)` 各自成行
  - 验收：回填后每个 Agent 的 `route_id` 非空；`llm_model` 中不存在因重名覆盖导致的行丢失；回填脚本可重复执行或明确只执行一次
  - 依赖：A1、A2
  - 结果（2026-11-12）：13 个 Agent 全部绑定路由（main 9 / fast 2 / judge 2 / multi 1）；旧模型 id 保留，8/9 重名两行各自成行

### B. 解析与客户端

- [x] B1 AgentCatalog：按 Agent 名读取配置
  - 作用：所有调用从 Agent 出发，成为唯一身份入口
  - 位置：`llm/catalog/AgentCatalog.java`
  - 做法：缓存 `agent` 表（含系统 Agent），提供 `get(agentName)`；启动与热加载时刷新；找不到 Agent 直接报错，不回退默认
  - 验收：按名命中、未命中抛错、禁用 Agent 不可用；单测覆盖
  - 依赖：A2
  - 结果（2026-11-12）：懒加载 + `reload()`；2 条单测

- [x] B2 ProviderCatalog：provider / model 读取与凭证解析
  - 作用：把端点与凭证解析从调用链中剥离，集中处理
  - 位置：`llm/catalog/ProviderCatalog.java`
  - 做法：读取 `llm_provider` / `llm_model`；按 `credential_ref` 从环境或密钥源解析；凭证缺失记录明确错误
  - 验收：凭证存在时返回、缺失时抛配置错误；密钥不出现在日志与异常文本中
  - 依赖：A2
  - 结果（2026-11-12）：`Environment.getProperty(credentialRef)` 解析；2 条单测

- [x] B3 ModelResolver：route → 有序可用成员
  - 作用：唯一的路由解析实现，替代所有按名查找
  - 位置：`llm/routing/ModelResolver.java`
  - 做法：按 `route_id` 取启用成员，按 `priority` 升序；过滤 `enabled=0` 的成员、模型与供应商；结果为空抛错；解析结果按 route 缓存，热加载时失效
  - 验收：顺序、禁用过滤、空路由、路由不存在四类用例；不出现任何按模型名查询
  - 依赖：A2、B2
  - 结果（2026-11-12）：代码内二次排序保证顺序；5 条单测

- [x] B4 ClientFactory：provider + model → 客户端
  - 作用：按部署行构建 OpenAI 兼容客户端，参数不再散落在调用方
  - 位置：`llm/client/ClientFactory.java`
  - 做法：按 `base_url` + 凭证构建；应用 `default_params`；处理超时、连接池、思考能力开关；构建失败返回明确错误
  - 验收：同一 model 重复构建结果稳定；缺凭证/非法 URL 构建失败；单测覆盖参数映射
  - 依赖：B2
  - 结果（2026-11-12）：`LlmClientFactory` 复用既有 `ChatClientFactory` 的端点/凭证解析；`default_params` 合并留给 C2

- [x] B5 ClientRegistry：按 model_id 缓存与失效
  - 作用：连接池复用与失败重建，彻底移除按名索引
  - 位置：`llm/client/ClientRegistry.java`
  - 做法：`Map<Long, ChatClient>`；`get(modelId)` 未命中时构建；`invalidate(modelId)` 按 id 重建；热加载整体替换
  - 验收：按 id 命中/失效；传入不存在的 id 报错而非回退默认；并发失效仅重建一次
  - 依赖：B4
  - 结果（2026-11-12）：`invalidate` 移除缓存、下次 `get` 重建；4 条单测

- [x] B6 RouteExecutor：失败链、重试与首 token 锁定
  - 作用：把失败切换策略集中在一处，调用方不再各自处理
  - 位置：`llm/routing/RouteExecutor.java`
  - 做法：按规划书第六节矩阵实现；首帧前可切换，首 token 后锁定当前成员；4xx（除 408/429）不切；429 同成员退避；取消立即终止；每次尝试回调观测
  - 验收：超时/5xx/429/4xx/取消/首 token 后失败六类用例；成员耗尽错误可读；切换次数与观测字段一致
  - 依赖：B3、B5
  - 结果（2026-11-12）：`FailurePolicy` + `RouteExecutor`；12 条单测覆盖矩阵；流式首 token 锁定在 M2 接入时补

### C. 参数与能力

- [x] C1 能力解析与校验
  - 作用：防止"配了工具但模型不支持"在线上静默失效
  - 位置：`llm/validation/BindingValidator.java`
  - 做法：解析 `capabilities` JSON；校验 Agent 工具、图片、思考、上下文预算；不满足时阻止路由成员生效
  - 验收：工具/视觉/思考/上下文四类不满足用例均被拦截；错误信息指出 Agent、路由、成员 id
  - 依赖：B1-B3
  - 结果（2026-11-12）：`ModelCapabilities` 解析 + 校验；4 条单测

- [x] C2 参数合并
  - 作用：模型默认参数与 Agent 覆盖有唯一口径
  - 位置：`llm/call/CallOptions.java`
  - 做法：`model.default_params < agent.params`；合并后显式下发；未配置项不下发，保持端点默认
  - 验收：覆盖优先级、空值、非法值三类用例；请求体断言只包含合并后的键
  - 依赖：B1、B2
  - 结果（2026-11-12）：`LlmCallOptions.merge`；3 条单测

- [x] C3 thinking 三语义拆分
  - 作用：区分"模型是否支持思考""请求是否开启""前端是否展示"
  - 位置：`llm/call/CallOptions.java`、请求组装处
  - 做法：`capabilities.thinking` 控制可用性；`agent.params.thinking_mode` 生成请求参数；`agent.params.show_thinking` 只影响流式展示
  - 验收：关闭思考不发思考参数；展示开关不影响模型调用；不支持思考的模型配 `thinking_mode` 时启动失败
  - 依赖：C1、C2
  - 结果（2026-11-12）：`ThinkingMode.AUTO/OFF` 与 `showThinking` 分离；校验器覆盖能力不足场景

- [ ] C4 BindingValidator 启动与热加载校验
  - 作用：配置错误在启动期暴露，不静默回退默认客户端
  - 位置：`llm/validation/BindingValidator.java`
  - 做法：启动时校验 Agent 路由、路由成员、凭证、能力、上下文预算；热加载先校验后原子替换，失败保留旧配置
  - 验收：路由缺失、成员为空、凭证缺失、能力不足、引用禁用模型五类用例均启动失败；热加载失败时旧配置继续服务
  - 依赖：C1
  - 结果（2026-11-12，部分）：启动校验已接入 `@PostConstruct` 并通过全量测试；热加载校验待 F2 完成

### D. 内部调用收口

- [x] D1 route-judge 改为系统 Agent + 路由
  - 作用：路由判定不再读配置里的模型名
  - 位置：`service/impl/route/LlmRouteJudge.java`、Agent 种子数据
  - 做法：读取 `route-judge` Agent 的 `route_id`；轻量超时参数保留；失败兜底语义不变
  - 验收：判定使用 `judge` 路由；改路由成员后判定模型随之改变，Agent 配置零改动；单测覆盖
  - 依赖：B3、B5
  - 结果（2026-11-12）：`settings()` 经 `AgentCatalog.require("route-judge")` → `ModelResolver` 首选成员取 modelId/模型名，prompt 仍取 agent 行；轻量客户端按 modelId 获取、失败按 modelId 丢池；模型常量已删除，路由缺失直接兜底 Agentic

- [x] D2 summary / profile / trigger-judge 改为系统 Agent + 路由
  - 作用：消除最后三处按模型名解析
  - 位置：`memory/UserProfileService.java`、`memory/SessionSummaryService.java`、`service/impl/route/LlmSemanticTriggerJudge.java`
  - 做法：各自读取对应系统 Agent 的 `route_id`；删除 `@Value("...model")` 注入口径；提示词保留在各自代码或 Agent 行
  - 验收：三个功能的模型来源均为路由；配置 `summary-model` / `trigger-judge.model` 不再被读取；单测覆盖
  - 依赖：B3、B5、A3
  - 结果（2026-11-12）：V21 注册 profile / summary（fast）与 trigger-judge（judge）系统 Agent；三处均改为 `AgentCatalog → ModelResolver → LlmClientRegistry`，观测模型名取实际路由成员；`@Value` 模型配置已删除

- [x] D3 删除按名查找 API
  - 作用：从结构上消除重名歧义
  - 位置：旧 `ChatClientRegistry`
  - 做法：删除 `idsByName`、`getByModel`、`getLightweightByModel`、`invalidateLightweight`；确认全仓库无引用
  - 验收：`grep` 零命中；全量测试通过；ArchUnit 禁止新增按名解析
  - 依赖：D1、D2
  - 结果（2026-11-12）：四个按名 API 已删除，源码零引用（仅 checklist 历史记录保留字样）；全量 1121 测试通过；ArchUnit 守卫在 H1 补齐

### E. 接入改造

- [x] E1 GeneralAssistantAgent 接入 LlmCaller
  - 作用：Agentic 直答路径成为新门面的第一个消费者
  - 位置：`agent/GeneralAssistantAgent.java`
  - 做法：改为 `LlmCaller.stream(agentName, ...)`；删除本地客户端获取与丢池逻辑（由 RouteExecutor 承接）；观测字段改由门面写入
  - 验收：流式 token、取消、重试语义与现网一致；`llm_call_log` 出现 route/model 字段；单测覆盖
  - 依赖：B6、C2
  - 结果（2026-11-12）：改为 `chatCaller.streamWithRoute`，按路由成员重建 spec 并在首 token 前切换

- [x] E2 AgentChatPipeline / AgentChatCaller 接入
  - 作用：阻塞收集路径与流式路径共用同一失败策略
  - 位置：`agent/AgentChatPipeline.java`、`agent/AgentChatCaller.java`
  - 做法：客户端获取与失败丢池移入 RouteExecutor；保留现有看门狗与观测钩子；删除按 modelName 传参
  - 验收：看门狗超时触发成员切换（首帧前）；首 token 后超时不切换；现有测试改按 route/model 断言
  - 依赖：B6、E1
  - 结果（2026-11-12）：`streamCore` 按成员循环，首 token 后错误不再切换；丢池按 `model_id`；全量测试通过

- [x] E3 Graph 编排路径接入
  - 作用：lead / 子任务 / aggregator 与直答使用同一解析链
  - 位置：`graph/OrchestrationNodes.java`、`graph/engine/*`
  - 做法：每个角色通过 Agent 名调用 LlmCaller；子任务失败占位语义不变；预算账本不变
  - 验收：编排全链路可跑通；单子任务模型失败可按路由切换；观测能区分 lead 与各专家
  - 依赖：E2
  - 结果（2026-11-12）：Graph 经共享 AgentChatCaller 自动走新链路；真实请求验证通过

- [x] E4 AgentScope 构建改为路由解析
  - 作用：Agentic ReAct 路径不再按名兜底
  - 位置：`agent/agentic/AgentScopeModelFactory.java`
  - 做法：删除 `modelProviderId == null` 时按名查库的兜底；统一从 `ModelResolver` 取 model 行；构建参数与 ClientFactory 同源
  - 验收：无绑定 Agent 启动失败；AgentScope 路径模型与 ChatClient 路径一致；单测覆盖
  - 依赖：B3、C4
  - 结果（2026-11-12）：`resolveRow(agentName)` 经 Agent → 路由 → 首选成员；按名兜底已删除；真实请求走 AgentScope 路径成功

### F. 管理面

- [x] F1 供应商 / 模型 / 路由 / Agent 绑定管理接口
  - 作用：新增或调整模型只改数据，不改代码
  - 位置：`llm/admin/LlmAdminService.java`、`controller/LlmAdminController.java`
  - 做法：实现规划书第十节接口；写操作先校验再落库；返回结构化校验错误
  - 验收：四类资源 CRUD 可用；非法外键/优先级冲突/空路由被拒绝
  - 依赖：A2、C4
  - 结果（2026-11-12）：`LlmAdminController` 暴露 `/api/admin/providers|models|routes|agents/{id}/route`；写操作事务内落库并触发热更新，校验失败转 400；11 条单测覆盖非法 URL/凭证缺失/重名/空路由/优先级冲突/禁用成员

- [x] F2 热加载：校验后原子替换
  - 作用：改配置不重启，且失败不破坏现有服务
  - 位置：`llm/catalog/*`、`llm/routing/ModelResolver.java`
  - 做法：变更后重建 catalog、路由缓存与客户端映射；先完整校验，通过后一次性替换引用；失败记录错误并保留旧配置
  - 验收：修改成员优先级后下一次调用生效；提交非法配置时旧配置继续服务
  - 依赖：F1
  - 结果（2026-11-12）：`LlmConfigRefresher.refresh()` 重读目录 → 清路由缓存 → 全量校验 → 清客户端缓存；校验失败恢复旧快照并重新清空缓存，异常上抛使事务回滚；3 条单测覆盖成功/校验失败/重读失败

- [x] F3 路由连通性测试端点
  - 作用：上线前确认每个成员真的可用
  - 位置：`LlmAdminController` 的 `POST /api/admin/routes/{id}/test`
  - 做法：按优先级逐个发一条最小请求，返回每个成员的延迟、HTTP 状态、错误分类
  - 验收：可用/超时/4xx/5xx 四类结果可区分；不写入会话与业务表
  - 依赖：B5、B6
  - 结果（2026-11-12）：`LlmAdminService.testRoute` 用轻量客户端（15s 读超时）逐成员探测，返回 ok/latency/httpStatus/errorClass（timeout/connection/4xx/5xx/other）；不挂 advisor，不写 llm_call_log 与会话表

### G. 观测

- [x] G1 `llm_call_log` 扩展
  - 作用：明确"这次到底是谁答的、第几个成员答的"
  - 位置：新迁移 + `observability/LlmCallRecorder.java`
  - 做法：增加 `route_id`、`route_code`、`model_id`、`provider_code`、`upstream_name`、`fallback_from_model_id`、`attempt`、`error_class`；保留现有字段
  - 验收：一次切换产生两条记录，字段可完整还原切换路径；旧查询接口兼容返回
  - 依赖：B6
  - 结果（2026-11-12）：V22 增加 7 列（attempt 已存在）；`LlmCallLog`/实体/落库链路透传；新增 `RouteAttempt` 在阻塞、响应式、AgentScope、judge/画像/摘要调用中回写；error_class 由 `FailurePolicy.errorClass` 统一分类

- [x] G2 路由与模型指标
  - 作用：能看降级频率与各模型健康度
  - 位置：`observability/HarnessMetrics.java`
  - 做法：新增 `llm_route_fallback_total`、`llm_model_error_total`、`llm_model_latency`；标签维度控制基数
  - 验收：触发一次切换后指标 +1；Prometheus 端点可见；标签不超过规划书范围
  - 依赖：G1
  - 结果（2026-11-12）：新增 `harness.llm.route.fallbacks{route,from_model,to_model}`、`harness.llm.model.errors{model_id,error_class}`、`harness.llm.model.latency{model_id,kind}`；阻塞与响应式切换路径均埋点；单测断言计数与 timer

### H. 清理与验收

- [ ] H1 ArchUnit 守卫
  - 作用：防止新代码重新引入按名解析或绕过门面
  - 位置：`deerfect-server/src/test/java/.../architecture/ArchitectureTest.java`
  - 做法：禁止 `ChatClient` 出现在 `llm.client` / `llm.call` 之外；禁止 Mapper 被 catalog/admin/validation 之外的包使用；禁止除 `LlmCaller` 外依赖 `ClientRegistry`
  - 验收：三条规则在现有代码上通过；故意违规样例能被拦下
  - 依赖：E1-E4

- [ ] H2 删除旧模型配置路径与旧表
  - 作用：完成切换，不留双写与双读
  - 位置：V21 迁移 + 旧 `ChatClientRegistry` / `AgentConfigProvider.resolveModelName`
  - 做法：删除 `model_provider` 表、`agent.model_provider_id`、`idsByName` 相关代码与配置项；确认无引用后提交
  - 验收：全仓库无 `model_provider` 读取；V21 在已回填库执行成功
  - 依赖：D3、E1-E4

- [ ] H3 文档同步
  - 作用：使用与排障文档与新模型一致
  - 位置：`README.md`、`docs/guides/models.md`、`docs/architecture.md`、`docs/project-structure.md`、`docs/features.md`
  - 做法：更新"新增模型/供应商"步骤、路由配置说明、观测字段与目录树；删除按名配置示例
  - 验收：按文档从零加一个供应商 + 模型 + 路由可跑通；文档内无旧表名与旧配置键
  - 依赖：F1、G1

- [ ] H4 全量测试与故障注入
  - 作用：确认替换没有回归
  - 位置：`mvn -s .mvn/settings.xml test`
  - 做法：全量单测 + MySQL 集成测试；补超时/5xx/429/4xx/取消/首 token 后失败六类故障注入；核对用例数不低于当前 1474
  - 验收：全量测试全绿；故障注入覆盖矩阵无遗漏；`llm_call_log` 与指标断言通过
  - 依赖：H1-H3
