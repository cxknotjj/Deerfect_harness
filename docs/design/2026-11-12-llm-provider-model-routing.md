# LLM 供应商 / 模型 / 路由与 Agent 绑定设计

> 状态：方案设计（未实施）
> 日期：2026-11-12
> 范围：`deerfect-server` 的模型解析、客户端构建、Agent 绑定、失败切换与观测
> 前提：按全新设计落地，不保留与现有 `model_provider` 表及按名查找接口的兼容

---

## 一、背景与问题

当前实现把"供应商凭证、模型名、端点地址、思考开关"混在一张 `model_provider` 表里，Agent 通过 `model_provider_id` 绑定。主链路方向正确，但仍有四类结构性问题：

1. **名字被当成 key 使用**：`model_provider.model` 没有唯一约束，但注册表仍保留"模型名 → id"的索引，供画像、摘要、路由判定、语义触发等内部调用使用。不同供应商存在同名模型时（如 deepseek 直连与腾讯代理都叫 `deepseek-v4-flash`），按名查找只能取先加载的一行，可能用错端点与凭证。
2. **缺少引用完整性**：`agent.model_provider_id` 没有外键，模型行被删除或禁用后，客户端查找失败会静默回退默认 DashScope 客户端，表现为"换了个供应商继续跑"。
3. **内部调用绕过 Agent 绑定**：路由判定、语义触发、画像提炼、会话摘要直接读配置里的模型名，与 Agent 的 id 绑定形成两套解析口径。
4. **没有显式失败链**：主模型不可用时只能整体报错，无法按数据配置切换到备用部署。

---

## 二、目标与非目标

### 目标

- Agent、内部系统调用、外部请求走**唯一一条**模型解析路径。
- 代码中的一切查找只认 id，名字仅用于展示。
- 主模型不可用时可**按数据配置**切换到备用部署，切换过程可观测。
- 配置错误在**启动期**暴露，不静默回退到未声明的默认供应商。
- 增加供应商 / 模型 / 路由只改数据，不改代码。

### 非目标

- 多租户、按用户隔离模型配额。
- 计费与成本分摊。
- 按权重灰度（预留 `strategy` 字段，本期只实现 `priority`）。
- 多模态编排（只做模型能力标记与调用前校验）。

---

## 三、核心概念

| 概念 | 表 | 回答的问题 | 谁维护 |
|---|---|---|---|
| 供应商 | `llm_provider` | 调哪个端点、用哪份凭证 | 运维 |
| 模型部署 | `llm_model` | 发哪个上游模型名、支持哪些能力、默认参数是什么 | 运维 |
| 路由 | `llm_route` | 这类用途现在优先用谁、坏了换谁 | 运维 |
| 路由成员 | `llm_route_member` | 具体候选模型及优先级 | 运维 |
| Agent | `agent` | 我是谁、什么提示词/工具、用哪条路由 | 开发 |

对应关系：

```text
agent.route_id ──> llm_route ──> llm_route_member ──> llm_model ──> llm_provider
                     (别名)        (有序候选)         (部署)        (凭证/端点)
```

名字（`display_name`、`agent.name`）只用于界面展示，不参与查询与缓存键。

---

## 四、数据模型

### 4.1 DDL

```sql
CREATE TABLE llm_provider (
  id             BIGINT       NOT NULL AUTO_INCREMENT,
  code           VARCHAR(32)  NOT NULL COMMENT '供应商代码，如 dashscope / deepseek / tecent',
  base_url       VARCHAR(255) NOT NULL COMMENT 'OpenAI 兼容端点根地址',
  credential_ref VARCHAR(128) NOT NULL COMMENT '凭证引用（环境变量名或密钥别名），不落明文',
  protocol       VARCHAR(32)  NOT NULL DEFAULT 'openai' COMMENT '协议类型，当前仅 openai',
  enabled        TINYINT      NOT NULL DEFAULT 1,
  created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME     NULL ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_provider_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='LLM 供应商：端点与凭证';

CREATE TABLE llm_model (
  id             BIGINT       NOT NULL AUTO_INCREMENT,
  provider_id    BIGINT       NOT NULL,
  upstream_name  VARCHAR(128) NOT NULL COMMENT '请求体中下发的模型名',
  display_name   VARCHAR(128) NOT NULL COMMENT '界面展示名，不参与查找',
  capabilities   JSON         NOT NULL COMMENT '{tool_calling,vision,thinking,json_mode,context_window}',
  default_params JSON         NULL COMMENT '{temperature,max_tokens,top_p,...}',
  enabled        TINYINT      NOT NULL DEFAULT 1,
  created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME     NULL ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_model_provider_name (provider_id, upstream_name),
  KEY idx_model_provider (provider_id),
  CONSTRAINT fk_model_provider FOREIGN KEY (provider_id) REFERENCES llm_provider (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型部署：一个供应商下的一个可调用模型';

CREATE TABLE llm_route (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  code        VARCHAR(64)  NOT NULL COMMENT '稳定别名：main / fast / judge / vision ...',
  description VARCHAR(255) NULL,
  strategy    VARCHAR(16)  NOT NULL DEFAULT 'priority' COMMENT '当前仅 priority',
  enabled     TINYINT      NOT NULL DEFAULT 1,
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME     NULL ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_route_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型路由：Agent 唯一绑定的稳定别名';

CREATE TABLE llm_route_member (
  route_id   BIGINT  NOT NULL,
  model_id   BIGINT  NOT NULL,
  priority   INT     NOT NULL COMMENT '越小越优先',
  enabled    TINYINT NOT NULL DEFAULT 1,
  PRIMARY KEY (route_id, model_id),
  UNIQUE KEY uk_route_priority (route_id, priority),
  KEY idx_member_model (model_id),
  CONSTRAINT fk_member_route FOREIGN KEY (route_id) REFERENCES llm_route (id),
  CONSTRAINT fk_member_model FOREIGN KEY (model_id) REFERENCES llm_model (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='路由成员：有序候选与失败链';

-- Agent 绑定改为路由
ALTER TABLE agent
  ADD COLUMN route_id BIGINT NULL AFTER description,
  ADD COLUMN params JSON NULL COMMENT 'Agent 级参数覆盖 {temperature,max_tokens,thinking_mode,show_thinking}',
  ADD KEY idx_agent_route (route_id),
  ADD CONSTRAINT fk_agent_route FOREIGN KEY (route_id) REFERENCES llm_route (id);
```

### 4.2 不变量

1. `llm_provider.code`、`llm_route.code`、`agent.name` 全局唯一。
2. `llm_model` 唯一键是 `(provider_id, upstream_name)`；同名模型允许存在于不同供应商，各自有独立 id。
3. 一条路由至少有一个 `enabled=1` 的成员；同一路由内 `priority` 唯一。
4. `agent.route_id` 非空；系统 Agent（`is_system=1`）同样必须绑定路由。
5. 任何被引用的 model / provider 不得物理删除，只能禁用。
6. 路由成员的能力必须覆盖其绑定 Agent 的需求；不满足时启动失败（见第七节）。

---

## 五、解析与调用链

### 5.1 唯一入口

```text
LlmCaller.call(agentCode, messages, options)
LlmCaller.stream(agentCode, messages, options)
        │
        ├─ AgentCatalog.get(agentCode)            // 按 agent.name，唯一
        ├─ ModelResolver.resolve(agent.routeId)   // 返回有序可用成员
        ├─ ClientRegistry.get(modelId)            // 按 model id 缓存客户端
        ├─ RouteExecutor.execute(members, request) // 失败切换、重试、观测
        └─ CallRecorder.record(...)               // 每次尝试一条记录
```

约束：

- 除 `LlmCaller` 外，任何类不得直接注入 `ChatClient`、不得直接查 `llm_model` / `llm_provider`。
- `ClientRegistry` 的缓存键、失效键一律是 `model_id`，禁止以模型名或供应商标识为键。
- 路由解析结果可缓存（按 `route_id` + 版本号），配置热更新时整体替换。

### 5.2 解析规则

```text
resolve(routeId):
  1. route.enabled = 1                   否则报错
  2. 取 route_member.enabled = 1，按 priority 升序
  3. 过滤 llm_model.enabled = 1 且 llm_provider.enabled = 1
  4. 结果为空                          报错（不兜底默认客户端）
```

### 5.3 客户端构建

- 一个 `model_id` 对应一个客户端实例（连接池、超时、协议封装）。
- 凭证按 `provider.credential_ref` 在启动/热加载时解析，密钥不落库。
- 构建失败（凭证缺失、URL 非法）在启动校验阶段直接失败。
- 客户端失效（连接黑洞、OOM、端点重启）按 `model_id` 重建，与路由成员无关。

---

## 六、失败切换策略

### 6.1 判定矩阵

| 情况 | 行为 |
|---|---|
| 连接失败、DNS 失败、连接超时、读超时、首帧超时 | 尝试下一成员 |
| HTTP 5xx | 尝试下一成员 |
| HTTP 429 | 同一成员指数退避重试（默认 2 次），仍失败则下一成员 |
| HTTP 4xx（除 408 / 429） | 不重试、不切换，直接失败 |
| HTTP 408 | 尝试下一成员 |
| 用户取消 / 客户端断连 | 立即终止，不切换 |
| 流式响应已输出首个 token 后失败 | 不切换，错误上抛 |
| 所有成员耗尽 | 错误上抛，不静默回退 |

**流式切换边界**：只有"尚未输出任何内容"时允许切换；一旦有 token 到达浏览器，切换会造成两段不同模型的回答拼接，因此禁止。实现上由 `RouteExecutor` 在首个内容帧到达时锁定当前成员。

### 6.2 重试预算

- 单个成员内：网络类错误重试 1 次（可配置），429 退避最多 2 次。
- 整条路由：最多遍历全部启用成员一次，不循环。
- 整体受请求级超时与编排预算约束（沿用现有 `ChatTimeoutProperties` 与 `BudgetLedger`）。

### 6.3 降级语义

- 默认**不允许能力降级**：成员能力不满足 Agent 需求时，该成员不能加入路由（启动校验失败）。
- 如确需降级，在路由上显式配置 `allow_capability_degrade=1`，切换时禁用缺失能力（例如关闭工具）并记录一条降级观测事件。

---

## 七、参数与能力

### 7.1 能力字段

```json
{
  "tool_calling": true,
  "vision": false,
  "thinking": true,
  "json_mode": true,
  "context_window": 131072
}
```

使用规则：

- `agent.tools` 非空 → 路由所有启用成员必须 `tool_calling=true`；
- 请求携带图片 → 成员必须 `vision=true`；
- Agent 要求关闭思考 → 成员必须 `thinking=true`，否则配置错误；
- `context_window` 用于启动期校验"模型上下文 + 输出上限是否装得下 Agent 预算"。

### 7.2 参数合并

```text
model.default_params  <  agent.params
```

- 模型层放"这个端点的默认值"（temperature、max_tokens、top_p）；
- Agent 层只覆盖差异（例如 coder 要更大 max_tokens、judge 要 temperature=0）；
- 请求组装时合并后显式下发，不再散落代码常量。

### 7.3 思考配置

| 配置 | 层 | 含义 |
|---|---|---|
| `capabilities.thinking` | 模型 | 该端点是否支持思考开关/预算 |
| `agent.params.thinking_mode` | Agent | `off` / `auto` / 预算值，决定请求参数 |
| `agent.params.show_thinking` | Agent | 是否把思考内容透传给前端 |

三层语义分开，避免现在"端点关思考"和"前端是否展示思考"共用相近命名的混淆。

---

## 八、校验规则

### 8.1 启动校验（失败即启动失败）

1. 每个 `agent.route_id` 存在且 `enabled=1`。
2. 每条启用路由至少有一个可用成员（模型与供应商均启用）。
3. 每个被引用 `provider.credential_ref` 能解析到非空凭证。
4. `provider.base_url` 合法；`model.upstream_name` 非空。
5. 成员能力覆盖 Agent 需求：工具、视觉、思考、上下文预算。
6. 不存在指向禁用模型/供应商的启用成员。

### 8.2 运行时校验

- 配置热更新先跑完整校验，通过后原子替换；失败保留旧配置并记录错误。
- 请求侧只做轻量断言（Agent 存在、路由已解析），不重复查库。

---

## 九、观测

`llm_call_log` 增加/明确以下字段：

| 字段 | 含义 |
|---|---|
| `agent_id` / `agent_name` | 发起调用的 Agent |
| `route_id` / `route_code` | 命中的路由 |
| `model_id` | 实际使用的模型部署 |
| `provider_code` | 实际供应商 |
| `upstream_name` | 实际请求的模型名 |
| `attempt` | 该路由内的第几次尝试 |
| `fallback_from_model_id` | 从哪个模型切换而来（无则为空） |
| `error_class` | 超时 / 连接 / 5xx / 4xx / 取消 |
| `first_token_ms`、`duration_ms`、token 用量 | 沿用现有口径 |

指标：

- `llm_route_fallback_total{route, from_model, to_model}`；
- `llm_model_error_total{model_id, error_class}`；
- `llm_model_latency{model_id}`（首 token 与总耗时）。

---

## 十、管理接口

```text
GET    /api/admin/providers                 供应商列表
POST   /api/admin/providers                 新增供应商（只存 credential_ref）
PUT    /api/admin/providers/{id}            修改端点/启用状态
GET    /api/admin/models                    模型列表（含能力与默认参数）
POST   /api/admin/models                    新增模型部署
PUT    /api/admin/models/{id}               修改模型/能力/启用状态
GET    /api/admin/routes                    路由及成员
POST   /api/admin/routes                    新增路由
PUT    /api/admin/routes/{id}               修改路由/成员/优先级
POST   /api/admin/routes/{id}/test          按成员顺序做一次真实连通性测试
PUT    /api/admin/agents/{id}/route         调整 Agent 绑定
```

所有写操作：先校验，再落库，再触发配置热加载。响应中返回校验失败原因，不允许"部分成功"。

---

## 十一、代码结构

```text
llm/
├── catalog/
│   ├── AgentCatalog.java          # agent 配置读取（按 name 唯一）
│   └── ProviderCatalog.java       # provider/model 读取与凭证解析
├── routing/
│   ├── ModelResolver.java         # route -> 有序可用成员
│   ├── RouteExecutor.java         # 失败切换、重试、首 token 锁定
│   └── RoutePlan.java             # 解析结果值对象
├── client/
│   ├── ClientFactory.java         # provider + model -> ChatClient
│   └── ClientRegistry.java        # Map<modelId, ChatClient>，按 id 失效
├── call/
│   ├── LlmCaller.java             # 唯一对外门面（同步/流式）
│   └── CallOptions.java           # 合并后的请求参数
├── validation/
│   └── BindingValidator.java      # 启动与热更新校验
└── admin/
    └── LlmAdminService.java       # 管理接口的业务实现
```

ArchUnit 守卫：

1. `ChatClient` 只允许出现在 `llm.client` 与 `llm.call` 包；
2. `llm_provider` / `llm_model` 的 Mapper 只允许 `catalog`、`admin`、`validation` 使用；
3. 除 `LlmCaller` 外，不允许其他类调用 `ClientRegistry`。

---

## 十二、示例数据

```sql
INSERT INTO llm_provider (id, code, base_url, credential_ref) VALUES
  (1, 'dashscope', 'https://dashscope.aliyuncs.com/compatible-mode', 'DASHSCOPE_API_KEY'),
  (2, 'deepseek',  'https://api.deepseek.com',                      'DEEPSEEK_API_KEY'),
  (3, 'tecent',    'https://chatapi.weixin.qq.com/openai',          'TENCENT_API_KEY');

INSERT INTO llm_model (id, provider_id, upstream_name, display_name, capabilities, default_params) VALUES
  (11, 1, 'qwen3.8-27b',                 'Qwen 3.8 27B（轻量）', '{"tool_calling":true,"thinking":true,"context_window":131072}', '{"temperature":0.3}'),
  (12, 1, 'deepseek-v4.1-flash-1017',    'DeepSeek V4.1 Flash（DashScope）', '{"tool_calling":true,"thinking":true,"context_window":131072}', '{"max_tokens":4096}'),
  (13, 2, 'deepseek-v4-flash',           'DeepSeek V4 Flash（官方）', '{"tool_calling":true,"context_window":65536}', '{"max_tokens":4096}'),
  (14, 3, 'deepseek-v4-flash',           'DeepSeek V4 Flash（腾讯代理）', '{"tool_calling":true,"context_window":32768}', NULL);

INSERT INTO llm_route (id, code, description) VALUES
  (1, 'main',  '主对话模型：dashscope 优先，腾讯代理备用'),
  (2, 'fast',  '轻量内部调用'),
  (3, 'judge', '路由判定');

INSERT INTO llm_route_member (route_id, model_id, priority) VALUES
  (1, 12, 10),
  (1, 14, 20),
  (2, 11, 10),
  (3, 11, 10);
```

对应 Agent：

```sql
UPDATE agent SET route_id = 1 WHERE agent_name IN ('general', 'nailong');
UPDATE agent SET route_id = 3 WHERE agent_name = 'route-judge';
-- summary / profile / trigger-judge 为按本方案新建的系统 Agent（is_system=1）
UPDATE agent SET route_id = 2 WHERE agent_name IN ('summary', 'profile', 'trigger-judge');
```

注意 14 若实际不支持工具调用（部分代理只透传文本），则不能进入带工具的 `main` 路由，启动校验会失败；上面的示例按"代理支持工具"填写，落地时以真实能力为准。

---

## 十三、落地步骤（不保留兼容）

1. **V20 迁移**：创建四张新表，`agent` 增加 `route_id` / `params`；回填现有 12 条模型、13 个 Agent 的路由映射。
2. **代码替换**：实现 `llm` 包，`LlmCaller` 接管同步/流式调用；删除 `ChatClientRegistry` 的按名接口与旧 `model_provider` 读写路径。
3. **内部调用收口**：路由判定、语义触发、画像、摘要改为系统 Agent + 路由。
4. **管理接口**：供应商/模型/路由/Agent 绑定四个管理面，带校验与热加载。
5. **删除旧表**：代码切换并验证后，V21 删除 `model_provider` 与 `agent.model_provider_id`。
6. **文档与观测**：更新多模型接入指南、排障手册、指标看板。

---

## 十四、测试策略

- **单元**：`ModelResolver` 的顺序、禁用过滤、空路由；`RouteExecutor` 的失败矩阵（含首 token 前后边界、取消、429）；参数合并优先级；能力校验。
- **集成**：Flyway 建表 + 管理接口 + MySQL 外键约束。
- **架构**：ArchUnit 三条守卫。
- **故障注入**：Mock 端点分别返回超时、5xx、4xx、429，断言切换次数与观测字段。
- **回归**：全量测试保持现有用例数不下降。

---

## 十五、风险与取舍

| 风险 | 说明 | 应对 |
|---|---|---|
| 流式中途不可切换 | 首个 token 后换模型会拼接两段回答 | 只允许首 token 前切换，错误上抛 |
| 备用模型回答质量差异 | 切换后用户可能感知风格变化 | 备用成员尽量同族；观测记录切换事件 |
| 多两张表 | 解析链路变长 | 路由解析结果缓存 + 原子热更新 |
| 能力矩阵维护成本 | 新模型需填能力 | 管理接口新增模型时给出校验与默认值 |
| 凭证轮换 | 客户端持有旧凭证 | 热加载时按 modelId 重建客户端 |

---

## 十六、待决问题

1. 429 是否计入"成员失败"：当前设计为同成员退避后仍失败才切换，需确认退避次数与总预算。
2. 是否允许路由成员级参数覆盖（例如备用成员限制 max_tokens），还是只保留模型级与 Agent 级两层。
3. 灰度路由（按权重/按用户）是否纳入下一期，当前只实现 `priority`。
4. 管理接口的权限模型：沿用现有 `X-API-Token` / 登录口令，还是单独的管理员口令。
