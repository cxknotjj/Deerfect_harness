# Agent 设置与路由配置：模型绑定管理计划书

> 状态：待评审　日期：2026-11-19
>
> 结论先行：**需要新增两个同级页面**——「Agent 设置」和「路由配置」，都挂在设置页「配置」分组下。
> 现有架构是 `Agent → llm_route → llm_model`，后端已经支持绑定、路由 CRUD 与热更新，前端完全没有入口；
> 两个页面分别补「使用方」和「模型组合」两层配置，缺一不可。
>
> 已确认：**路由配置页与 Agent 设置同级，不放进「模型管理」子页。**

---

## 一、核查结论

### 1.1 后端已有能力

| 能力 | 现状 | 位置 |
| --- | --- | --- |
| Agent 绑定路由 | ✅ `PUT /api/admin/agents/{id}/route`，写库后热刷新 | `LlmAdminController.updateAgentRoute` |
| 路由列表 | ✅ `GET /api/admin/routes`，含成员与优先级 | `LlmAdminController.listRoutes` |
| 路由新建 / 编辑 | ✅ `POST /api/admin/routes`、`PUT /api/admin/routes/{id}` | `LlmAdminController.createRoute / updateRoute` |
| 路由连通性测试 | ✅ `POST /api/admin/routes/{id}/test` | `LlmAdminController.testRoute` |
| 配置热更新 | ✅ 清空路由解析、Agent 配置、客户端缓存 | `LlmConfigRefresher.refresh` |
| Agent 管理列表 | ⚠️ 只有 `GET /api/harness/agents` 返回 `{id, name}` | `AgentConfigProvider.listAgentItems` |
| Agent 路由详情 | ❌ 管理面没有接口 | — |

关键实现细节：

- `agent` 表字段为 `agent_id / agent_name / description / route_id / params / prompt / tools / knowledge / thinking / status / is_internal`；
- `route_id` 允许为空，实际调用时由 `ModelResolver.resolve(routeId)` 解析为有序成员；
- 绑定前后端会 `resolve` 一次，路由未启用、成员为空、成员引用禁用模型/供应商都会拒绝；注意 `updateAgentRoute` 目前直接抛 `IllegalStateException`，全局映射会落 500，计划顺手包装成 `IllegalArgumentException`，让页面拿到 400 + 可读原因；
- 写库后 `LlmConfigRefresher` 会失效路由缓存、Agent 配置缓存与客户端池，**改完立即生效，不用重启**；
- `GET /api/harness/agents` 只返回 `is_internal=0` 的行，且只有 id/name，不够管理页使用。

### 1.2 前端缺口

| 位置 | 现状 |
| --- | --- |
| 设置页左侧导航 | 只有「模型管理 / 工作区 / 语音合成 / 系统设置 / 外观 / 本机偏好 / 实例信息」 |
| 模型管理 | 只展示供应商卡片和模型列表，没有路由，也没有 Agent 绑定 |
| `api/admin.ts` | 只封装 providers/models 的 4 个方法，没有 routes/agents |
| 全局 API 聚合层 | `Api` 接口与 mock 同样没有路由/Agent 管理方法 |

结论：目前要改某个 Agent 的模型，只能手写 SQL 或 `curl`；要改一条路由的成员，也只能直接改库，改完都没有页面能确认实际生效的是哪条路由、哪个模型。

### 1.3 页面拆分结论

**需要两个页面，而不是一个。**

| 页面 | 负责 | 不负责 |
| --- | --- | --- |
| 模型管理（现有） | 供应商、模型、Base URL、凭证引用 | 路由组合、Agent 绑定 |
| **路由配置（新增）** | 路由 code、描述、启停、成员顺序、失败切换链、连通性测试 | 供应商/模型本身、Agent 绑定 |
| **Agent 设置（新增）** | 每个 Agent 绑定哪条路由，实际主模型与备选链 | 路由成员编辑、模型配置 |

三层职责刚好对应 `provider → route → agent` 的调用链；把路由塞进模型管理会让页面过长，也让「模型组合」这一层没有独立入口。

---

## 二、设计原则

1. **不新增 `agent.model_id`，不绕过路由。**
   Agent 只认 `route_id`，路由负责「主模型 + 失败切换顺序」。这是 `docs/design/2026-11-12-llm-provider-model-routing.md` 已确定的架构，不能为了页面好看退回直连模型。
2. **路由配置是独立一级配置项。**
   与 Agent 设置同级，不再作为「模型管理」的子页；模型管理只保留供应商与模型。
3. **页面只做自己那一层。**
   路由页不编辑模型参数，Agent 页不编辑路由成员；跨页只做跳转与高亮，不复制表单。
4. **写操作以后端校验为准。**
   前端只做最小防呆（code 非空、至少一个启用成员、模型不重复），最终以 `LlmAdminService.validateRoute` 的 400 原因为准。
5. **不做乐观更新。**
   保存成功后重新拉取列表；后端热刷新已经同步，页面显示的必须是服务端真实状态。
6. **对话 Agent 与内部角色分开。**
   `is_internal=1` 的 `multi-agent / lead / aggregator / route-judge / profile / summary / trigger-judge` 也会真实调用模型（编排、摘要、判定），需要可见；但改动会影响后台行为，默认折叠并标注风险。
   其中 `multi-agent` 是编排体，本身不直接按 `route_id` 发起单次模型调用（实际由 `lead` / `aggregator` 行承担），它的路由行照实展示，但页面不承诺修改后一定改变编排结果。

---

## 三、信息架构

```text
设置
├─ 配置
│  ├─ 模型管理          （现有：供应商 + 模型）
│  ├─ Agent 设置        （新增：Agent → 路由绑定）
│  ├─ 路由配置          （新增：路由 → 有序模型成员）
│  ├─ 工作区
│  └─ 语音合成
├─ 界面
│  ├─ 系统设置
│  ├─ 外观
│  └─ 本机偏好
└─ 实例
   └─ 实例信息
```

导航顺序说明：

- 「模型管理 → Agent 设置 → 路由配置」是从底层资源到使用方的阅读顺序；
- Agent 设置依赖路由配置提供选项，但两者都依赖模型管理里的模型；
- 两个新增项都放在「配置」组，不进入「界面」或「实例」。

---

## 四、页面设计：路由配置

### 4.1 页头

```text
路由配置
管理模型路由：主模型、备选顺序与失败切换              [刷新] [+ 新建路由]
```

页头下方统计胶囊：

```text
4 条路由   5 个成员   8 个 Agent 绑定
```

> 数字以实际 `llm_route` / `llm_route_member` / `agent` 表为准；Agent 绑定数由 `GET /api/admin/agents` 按 `routeId` 汇总。

### 4.2 路由列表

单列卡片，不挤在表格里；每条路由的成员链长度不固定，卡片比表格更容易读。

```text
┌────────────────────────────────────────────────────────────────────┐
│ main  主对话模型                              [启用]  [8 个 Agent]  │
│ ① deepseek-v4.1-flash-1017   deepseek · 主模型                    │
│ ② qwen-max                   dashscope · 备选 1                    │
│ ③ qwen-plus                  dashscope · 备选 2                    │
│                                              [测试] [编辑] [停用]   │
├────────────────────────────────────────────────────────────────────┤
│ fast  轻量内部调用                            [启用]  [3 个 Agent]  │
│ ① qwen3.8-flash              dashscope · 主模型                    │
│                                              [测试] [编辑] [停用]   │
└────────────────────────────────────────────────────────────────────┘
```

每张卡片字段：

| 字段 | 说明 |
| --- | --- |
| `code` | 路由稳定别名，如 `main`；等宽字体 |
| 描述 | `description`，为空显示「未填写描述」 |
| 状态 | `enabled=1` 启用 / `0` 停用；停用路由不能绑定 |
| Agent 数 | 当前绑定该路由的 Agent 数量；读不到时显示 `—`。停用判断只看其中 `status=1` 的绑定，全部禁用时不阻塞 |
| 成员链 | 按 `priority` 升序，序号 ①②③ 由前端生成，不写死；每条显示模型显示名、供应商、上游名 |
| 成员状态 | 路由成员被禁用、模型被禁用、供应商被禁用时标「不可用」；全部不可用显示「无可用成员」 |
| 操作 | 测试 / 编辑 / 停用（启用）；不提供删除 |

健康状态规则（前端用 routes + models + providers 三份数据本地计算，不新增后端字段）：

- 路由 `enabled=0` → 「已停用」
- 路由成员全部 `enabled=0` → 「无可用成员」
- 成员引用的模型或供应商 `enabled=0` → 「部分不可用」
- 其余 → 「可用」

### 4.3 新建 / 编辑抽屉

从右侧展开，不打断列表上下文：

```text
编辑路由 main

code          [main                    ]
描述          [主对话模型              ]
策略          priority（当前仅支持这一种）
状态          [启用]

成员（按顺序尝试）
① [deepseek-v4.1-flash-1017 v]  [上移] [下移] [删除]
② [qwen-max v]                  [上移] [下移] [删除]
[+ 添加成员]

[测试当前路由]                         [取消] [保存]
```

字段与规则：

- `code`：必填、唯一；编辑时允许改，但提示「改 code 不影响已绑定的 Agent（绑的是 route_id）；后续日志/文档按新 code，历史 `llm_call_log.route_code` 不会回填」；
- `description`：可选，最多 255 字符；
- `strategy`：只读 `priority`，后端目前只支持这一种；
- `enabled`：启用 / 停用；**停用仍有启用中 Agent 绑定的路由会被后端拒绝**——`BindingValidator.validateAll()` 会在热刷新时校验每个启用 Agent，路由停用后解析失败，整个保存事务回滚。UI 先统计绑定数，`> 0` 时禁用停用按钮并提示「先到 Agent 设置改绑」（绑定 Agent 全部为 `status=0` 时可停用）；
- 成员：只列启用中的模型；同一模型不能重复；至少保留一个成员；
- 编辑已有路由时，如果某成员引用的模型已被禁用，下拉里没有这一项：该行以只读占位显示「模型已禁用」，保存前必须替换或删除，不能静默保留；
- **至少一个启用成员**：后端允许全禁用成员保存，但解析时会失败；前端保存前拦一道，避免制造坏路由；
- **保存会触发全量绑定校验**：路由成员必须满足所有绑定 Agent 的工具/思考/上下文要求（例如 Agent 配了工具，成员模型必须支持 tool calling）；不满足时后端回滚并返回原因，页面原样展示，不静默吞掉；
- 顺序：UI 显示 1..N，提交时按 10/20/30… 生成 `priority`，给以后插入留空隙；上移/下移按钮而非拖拽，键盘可用；
- 保存：新建 `POST /api/admin/routes`，编辑 `PUT /api/admin/routes/{id}`；
- **测试**：`POST /api/admin/routes/{id}/test` 只对已保存版本生效；新建路由需先保存再测试，编辑时测试的是服务端当前版本，未保存改动不会被测到。按钮旁注明这一点；
- **删除**：后端没有 DELETE，且删除会打断已绑定 Agent；需要下线时先改绑，再把 `enabled` 置 0。

### 4.4 测试结果

点击「测试」后在卡片内展开，不弹窗：

```text
测试结果 · 刚刚
① deepseek-v4.1-flash-1017   820ms   成功
② qwen-max                   1.2s    HTTP 429 · 限流
③ qwen-plus                  超时     connection
```

- 逐成员显示 `latencyMs / httpStatus / errorClass / error`；
- 测试不写会话、不写 `llm_call_log`，只做最小请求；
- 失败不改变路由配置，仅展示；
- 测试进行中按钮 loading，禁止重复点击。

### 4.5 空态与错误

| 状态 | 展示 |
| --- | --- |
| 加载中 | 卡片骨架 |
| 接口失败 | 页头红色公告 + 「重试」 |
| 没有路由 | 「还没有路由」+「新建路由」按钮；提示 Agent 未绑定时无法调用模型 |
| 没有可用模型 | 新建按钮禁用，提示先去模型管理启用模型 |
| 成员为空 | 卡片标「无可用成员」，编辑抽屉保存时拦截 |
| 并发修改 | 无版本号，最后写入生效；当前单管理员本机工具可接受 |

---

## 五、页面设计：Agent 设置

### 5.1 页头

```text
Agent 设置
为每个 Agent 绑定模型路由，查看实际使用的模型与失败切换顺序        [刷新]
```

统计胶囊：

```text
8 个对话 Agent   7 个内部角色   4 条路由   2 个未绑定
```

> 数字实时计算；这里只是示意。

### 5.2 列表

```text
[搜索 Agent 名称或描述…]                         [只看未绑定 / 异常]

对话 Agent
┌──────────────────────────────────────────────────────────────────┐
│ G  general            通用 AI 助手（默认）          [启用]        │
│    路由 main · deepseek-v4.1-flash-1017 · +2 备选                │
│                                              [查看路由] [更换]   │
├──────────────────────────────────────────────────────────────────┤
│ D  deepseek           深度推理助手                  [启用]        │
│    路由 main · deepseek-v4.1-flash-1017 · 无备选                 │
│                                              [查看路由] [更换]   │
└──────────────────────────────────────────────────────────────────┘

内部角色（7）                                                   [展开]
> multi-agent / lead / aggregator / route-judge / profile / summary / trigger-judge
```

每行字段：

| 字段 | 说明 |
| --- | --- |
| 字母标 | 取 `agent_name` 首字母，与工作区卡片一致 |
| 名称 / 描述 | `agent_name` 加 `description`，描述单行省略 |
| 状态 | `status=1` 显示「启用」，`0` 显示「已禁用」 |
| 内部角色徽标 | `is_internal=1` 显示「内部」，悬浮说明影响范围 |
| 当前路由 | `routeCode` 胶囊；`routeId=null` 显示「未绑定」 |
| 实际主模型 | `members[0].displayName`，附 `providerCode`；无成员显示「无可用模型」 |
| 备选 | `members.length - 1`，为 0 时显示「无备选」 |
| 异常 | `routeError` 非空时显示红色「路由不可用」，`title` 放完整原因 |
| 操作 | 「查看路由」跳到路由配置页并高亮该路由；「更换」打开路由选择器 |

### 5.3 路由选择器

点击「更换」后在行下方展开（或右侧浮层）：

```text
选择路由
(•) main    deepseek-v4.1-flash-1017 · 2 个备选     [可用]
( ) fast    qwen3.8-flash · 无备选                 [可用]
( ) judge   qwen3.8-27b · 无备选                   [可用]
( ) legacy  —                                     [不可用：路由已停用/无可用成员]

[管理路由]                                      [取消]
```

交互：

- 单选项是原生 `radio` 语义，键盘 `↑ / ↓` 移动、`Enter` 选中、`Esc` 取消并回焦「更换」；
- 停用或无可用成员的路由仍展示但不可选，原因放在第二行；
- 这里的「可用」只代表路由结构可用（启停、成员、模型/供应商状态）；与当前 Agent 的工具/思考/上下文是否兼容，最终由后端 `BindingValidator` 判定，失败原因原样展示。若以后要在选择器里预判，需要让 `GET /agents` 返回 `tools/params`，本期不做；
- 「管理路由」跳到同级的「路由配置」页，返回后 Agent 页重新拉取；
- 选中并保存时，该行进入 loading，其他行禁用；
- 成功后在页头下方显示 note：`general 已切换到 fast（qwen3.8-flash）`；
- 失败显示后端原因，例如 `配置校验失败：llm_route 不存在或未启用: id=7`。

### 5.4 两页之间的跳转

- Agent 行「查看路由」→ `SettingsView` 切到 `routes`，并传入 `focusRouteId`；路由配置页滚动到对应卡片并加 2 秒高亮。
- 反向不做跨页跳转：路由配置页不提供「跳到某个 Agent」，避免把两个页面的状态耦合在一起。
- 面包屑/返回不需要：左侧导航本身就是同级切换，保留当前滚动位置即可。

### 5.5 空态与异常

| 状态 | 展示 |
| --- | --- |
| 没有 Agent | 「当前实例没有 Agent 行」+ 说明 |
| 搜索无结果 | 「没有匹配的 Agent」 |
| 未绑定 | 路由列「未绑定」，主模型「—」，仍可点「更换」 |
| 路由不可用 | 红色胶囊，完整错误在 `title` 与展开明细中 |
| Agent 禁用 | 行整体降透明度，提示「启用后生效」 |
| 没有可选路由 | 选择器显示空态 + 「去新建路由」跳路由配置页 |

---

## 六、接口设计

### 6.1 新增 `GET /api/admin/agents`

返回全部 Agent（含内部角色），按 `is_internal`、`agent_id` 升序。

```json
[
  {
    "agentId": 1,
    "agentName": "general",
    "description": "通用 AI 助手（默认）",
    "status": 1,
    "internal": false,
    "routeId": 1,
    "routeCode": "main",
    "routeEnabled": 1,
    "routeError": null,
    "members": [
      {
        "modelId": 12,
        "displayName": "deepseek-v4.1-flash-1017",
        "upstreamName": "deepseek-v4.1-flash-1017",
        "providerCode": "deepseek",
        "priority": 10
      }
    ]
  }
]
```

约定：

- `members` 是 `ModelResolver.resolve` 过滤后的**可用**成员链，已按 `priority` 升序；
- 某条路由解析失败时，**不要让整个列表 500**：该行 `members=[]`、`routeError` 写原因，其余行照常返回；
- `routeId=null` 时 `routeCode=null`、`members=[]`、`routeError="未绑定路由"`；
- 不返回 `prompt` / `tools` / `knowledge`，避免管理列表过重；这些字段留给后续 Agent 详情页；
- `displayName` 直接取 `RoutePlan.Member.displayName`，不再让前端二次 join 模型表；
- 路由配置页的「Agent 数」直接按 `routeId` 汇总这个响应，不新增 `RouteView.agentCount`。

### 6.2 复用的现有接口

| 方法 | 用途 |
| --- | --- |
| `PUT /api/admin/agents/{id}/route` | 绑定 Agent 到路由，body `{ "routeId": 1 }` |
| `GET /api/admin/routes` | 路由列表（路由配置页 + Agent 选择器） |
| `POST /api/admin/routes` | 新建路由 |
| `PUT /api/admin/routes/{id}` | 编辑路由（整行提交成员） |
| `POST /api/admin/routes/{id}/test` | 逐成员测试延迟与错误分类 |
| `GET /api/admin/models` | 路由编辑器的模型下拉 |
| `GET /api/admin/providers` | 路由健康判断（供应商是否启用） |

### 6.3 不建议本期扩展

- `RouteView.agentCount`：前端已有 Agent 列表，join 一次即可；避免再改路由响应。
- `RouteMemberView.displayName`：路由页本来就要加载 models 做编辑器，join 成本可忽略。
- `DELETE /api/admin/routes/{id}`：删除会打断绑定，先不做。

---

## 七、代码改动清单

### 7.1 后端（`deerfect-server`）

1. `LlmAdminModels.java`
   - 新增 `AgentAdminView`、`AgentRouteMemberView` 两个 record；
   - 不动现有的 `RouteView` / `RouteMemberView`。
2. `LlmAdminService.java`
   - 新增 `listAgents()`：一次读 `agent` 表 + 按 `routeId` 缓存 `RoutePlan`；
   - 解析失败的 route 缓存错误信息，避免同一坏路由反复查库；
   - 按 `is_internal`、`agent_id` 排序；
   - 复用现有 `updateAgentRoute`，不改写库逻辑。
3. `LlmAdminController.java`
   - 新增 `GET /agents`。
4. `LlmAdminService.updateAgentRoute`
   - 把 `ModelResolver.resolve` 抛出的 `IllegalStateException` 包装为 `IllegalArgumentException`（带原原因），
     让「路由停用 / 无可用成员」以 400 返回，而不是 500；不改变写库逻辑。
5. 测试 `LlmAdminServiceTest`
   - 正常列表（含内部角色、成员顺序）；
   - `routeId=null`；
   - 坏路由不拖垮整个列表；
   - 绑定到停用/空成员路由返回 400（而不是 500）；
   - 现有 `updateAgentRoute` 用例保持通过。
6. **不需要数据库迁移；路由 CRUD / 测试接口已存在。**

### 7.2 前端（`deerfect-web`）

1. `src/api/admin.ts`
   - 新增类型：`LlmRouteView / LlmRouteMemberView / LlmRouteUpsert / LlmAgentAdminView / LlmAgentRouteMemberView / LlmRouteTestResult`；
   - 新增函数：`listLlmAgents / listLlmRoutes / createLlmRoute / updateLlmRoute / testLlmRoute / updateAgentRoute`。
2. `src/api/client.ts`、`src/api/index.ts`
   - 把上述方法挂到 `Api` 接口与 `realApi`，并导出类型。
3. `src/api/mock/mock.ts`
   - 增加 `llmRoutes`、`llmAgents` 两组内存数据（结构对齐真实接口）；
   - 实现 list/create/update/test/bind，便于 e2e 与本地 mock 预览；
   - 绑定后同步更新内存里的 `routeId / routeCode / members`；
   - 路由成员的健康状态与 mock models/providers 的 `enabled` 保持一致。
4. 新增 `src/components/settings/RouteSettingsPanel.vue`
   - 路由卡片列表、健康状态、测试结果、编辑抽屉；
   - 新建/编辑/启停；成员上下移、添加/删除；
   - 不实现删除路由。
5. 新增 `src/components/settings/AgentSettingsPanel.vue`
   - 列表、搜索、分组折叠、路由选择器、保存与错误提示；
   - `emit('open-route', routeId)` 让 `SettingsView` 切到路由配置并高亮。
6. `src/components/SettingsView.vue`
   - `TabKey` 增加 `'agents'`、`'routes'`；
   - 左侧「配置」组在「模型管理」后依次插入「Agent 设置」「路由配置」；
   - `TABS` 增加标题/副标题；
   - 渲染两个面板，接 `open-route` 事件并传递 `focusRouteId`；
   - 支持面板间内部跳转：`Agent 设置 → 路由配置`（带 `focusRouteId`）；
   - **模型管理保持原样，不增加路由子页。**
7. 新增 `src/styles/routing-settings.css`，并在 `src/styles/main.css` 引入
   - 两个页面共用：卡片、成员链、选择器、抽屉、测试结果、窄屏样式。
8. 新增 `src/utils/routingSettings.ts`
   - `routeHealth(route, models, providers)`；
   - `routeMemberLabel(member, models)`；
   - `groupAgents(agents)`；
   - `nextPriorities(count)`；
   - 配 `vitest` 单测，覆盖健康判断与边界。
9. `e2e/resources.spec.ts`
   - 设置导航增加两个新项断言；
   - 路由页：列表数量、新建路由、编辑成员、测试结果、停用警告；
   - Agent 页：切换路由、行内模型更新、禁用路由不可选、「查看路由」跳转；
   - 原有模型管理、工作区、文档页用例不回归。

---

## 八、边界与风险

| 场景 | 预期行为 |
| --- | --- |
| `route_id` 为空 | Agent 行显示「未绑定」，允许选择路由；不阻塞页面 |
| 路由被停用 | 选择器里标「不可用」；已绑定的 Agent 显示「路由不可用」，但页面照常打开 |
| 路由成员全禁用 | 卡片标「无可用成员」；编辑保存时前端拦截；后端最终也会在调用/绑定时报错 |
| 成员引用禁用模型/供应商 | 卡片标「部分不可用」；编辑时可替换；不自动删除成员 |
| 停用仍有启用 Agent 绑定的路由 | 后端绑定校验会拒绝并回滚；UI 提前禁用停用按钮，列出绑定数量并引导先改绑；绑定 Agent 全为 `status=0` 时可停用 |
| 修改路由 code | 允许；提示只影响按 code 的外部引用，不影响 `agent.route_id` |
| 修改路由成员顺序 | 保存后立即热刷新；新顺序从下一次调用开始生效 |
| 保存路由导致已有 Agent 能力不满足 | 例如新成员不支持工具调用、思考开关或上下文窗口不足：`BindingValidator` 回滚整次保存并返回具体 Agent/成员原因；页面原样展示 |
| 测试未保存的改动 | 测试只对已保存版本生效；UI 明确提示，避免误解 |
| 并发修改 | 无版本号，最后写入生效；当前单管理员本机工具可接受；后续多人协作再加 `updated_at` 乐观锁 |
| 模型被禁用/删除 | 路由与 Agent 两页都显示异常；到路由页替换成员 |
| 权限 | `/api/admin/**` 受现有 `ApiTokenFilter` 保护；未配置口令时本机开放（沿用现状） |
| 页面宽度 | 1440 / 960 / 390 三档；路由成员链在窄屏换行，操作按钮不溢出 |

**相关发现（不在本计划范围）**：`AgentConfigProvider.listAgentItems` 只过滤 `is_internal=0`，没有过滤 `status=1`；
被禁用的 Agent 仍会出现在对话页的 Agent 选择器里，选中后调用会被 `AgentCatalog.require` 拒绝。
这是现有接口的口径问题，建议另开一个小修（选择器过滤禁用行或在 UI 标注禁用），不要和本次模型绑定混在一起。

---

## 九、分阶段计划

### M0：共用底座

交付：

- 后端 `GET /api/admin/agents`；
- 前端 API 类型与函数、`Api` 聚合层、mock 数据；
- `routingSettings.ts` 纯函数与单测；
- 两个页面的共用样式。

验收：

- `GET /api/admin/agents` 能返回全部 Agent，坏路由不拖垮列表；
- mock 模式下 Agent/路由数据与真实结构一致；
- `vue-tsc` / `eslint` / `vitest` 通过。

### M1：路由配置页

交付：

- 路由卡片列表、健康状态、Agent 绑定数；
- 新建 / 编辑 / 启停、成员顺序、连通性测试；
- 空态、错误、窄屏与明暗主题。

验收：

1. 设置页「配置」组出现「路由配置」，与 Agent 设置同级；
2. 能看到 4 条内置路由及其成员顺序、Agent 绑定数；
3. 新建 `qwen-max-only`，只放 `qwen-max`，保存后出现在列表；
4. 编辑成员顺序并保存，刷新后顺序保留；
5. 测试按钮逐成员返回延迟/错误分类；
6. 停用仍有启用 Agent 绑定的路由时按钮禁用并提示先改绑；无绑定或绑定 Agent 全禁用时可停用；
7. 全禁用成员的保存被拦截，提示原因；保存触发的能力校验失败时错误可见。

### M2：Agent 设置页

交付：

- Agent 列表、搜索、对话/内部分组；
- 当前路由、主模型、备选数、异常状态；
- 路由选择器与保存；
- 「查看路由」跳转与高亮。

验收：

1. 设置页出现「Agent 设置」，能列出全部 Agent（含内部角色）；
2. 每行显示 `routeCode + 主模型 + 备选数`；
3. 把 `general` 从 `main` 切到 `fast`，页面立即显示 `fast / qwen3.8-flash`；
4. 刷新页面后仍是 `fast`（服务端持久化）；
5. 未绑定/坏路由的行有明确标记，不阻塞其他行；
6. 点「查看路由」跳到路由配置页并高亮对应卡片。

### M3：Agent 级参数（可选，另开计划）

- `params.temperature / max_tokens / thinking_mode / show_thinking`；
- `prompt / tools / knowledge` 编辑；
- 不在本计划范围内，避免一次改动过大。

---

## 十、测试计划

### 后端

- `LlmAdminServiceTest` 新增：
  - 列表包含内部角色与对话 Agent；
  - 成员按 priority 升序；
  - `routeId=null` 不抛错；
  - 一条坏路由不影响其他行；
  - 绑定到停用/无可用成员路由返回 400（异常包装后）；
  - 现有绑定成功/未知 Agent/非法路由用例保持通过。
- `mvn -s .mvn/settings.xml -pl deerfect-server -am test`。

### 前端

- `vue-tsc`、`eslint`；
- `vitest`：`routeHealth`（可用/停用/无成员/部分不可用）、`groupAgents`、`nextPriorities`；
- `playwright`：
  - 设置导航出现「Agent 设置」「路由配置」，两者同级；
  - 路由页：4 条 mock 路由、创建、编辑成员、测试、停用拦截与无绑定停用；
  - Agent 页：切换路由、行内模型变化、禁用路由不可选、查看路由跳转；
  - 原有模型管理、工作区、文档页用例不回归。

### 手工验证（真实后端 + MySQL）

1. 启动后端，打开 `/settings` → 路由配置；
2. 新建一条单模型路由，绑定到 `general`；
3. 发一条消息，在监控 → 明细里确认 `route_id` 与模型已变化；
4. 重启后端，确认路由与绑定仍在；
5. `general` 仍绑定时，路由页的停用按钮应禁用并提示绑定数；直接用 `curl` 调 `PUT /api/admin/routes/{id}` 停用，后端也应拒绝并返回绑定 Agent 原因；
6. 把 `general` 改回 `main` 后停用新路由：应成功，状态变「已停用」；
7. 新建一条使用「不支持工具调用」模型的路由，绑定配了工具的 `general`：应被拒绝，错误可见；
8. 恢复路由/成员，确认可重新绑定。

---

## 十一、验收标准

**功能**

1. 设置页「配置」组有「Agent 设置」和「路由配置」两个同级项；
2. 路由配置页能列出全部路由、成员顺序、健康状态、Agent 绑定数；
3. 路由配置页能新建/编辑/启停路由，能测试逐成员连通性；
4. Agent 设置页能列出全部 Agent（含内部角色），显示当前路由、主模型、备选数；
5. 可在 Agent 页切换路由，保存后无需重启，下一次调用生效；
6. 路由停用/无可用成员时不能绑定，错误原因可见；
7. 刷新页面配置仍在；
8. 「查看路由」能跨页跳转并高亮。

**质量**

9. 1440 / 960 / 390px、明暗主题均无溢出或遮挡；
10. 键盘可完成选择、编辑、保存与取消；
11. `vue-tsc` / `eslint` / `vitest` / `playwright` / `npm run build` 全绿；
12. 无数据库迁移、无密钥落库、无后端兼容层。

---

## 十二、备选方案与否决理由

### 方案 A：Agent 直接选模型（新增 `agent.model_id`）

- 优点：交互最直观；
- 否决：破坏 `Agent → 路由 → 模型` 单一解析链，失败切换、路由测试、缓存失效都要另写一套；
  与 `docs/design/2026-11-12-llm-provider-model-routing.md` 的硬约束冲突。

### 方案 B：Agent 页选模型，后台自动建 `agent-<name>` 专属路由

- 优点：用户不用理解路由；
- 风险：路由行随 Agent 数量膨胀；共享路由（main/fast）的备选更新不会作用到已专属化的 Agent；
  需要额外的命名冲突、清理、迁移规则；
- 结论：可作为后续的「快捷入口」，不作为默认模型；默认仍让用户看到并选择路由。

### 方案 C：只做只读展示，改配置走 SQL

- 否决：用户明确要「配置」，只读不解决问题；且容易改错 `route_id`。

### 方案 D：把路由配置放进「模型管理」子页

- 优点：少一个导航项；
- 否决：用户已明确要求与 Agent 设置同级；路由是独立一层，塞进模型管理会让页面过长，
  也让「路由」在信息架构里没有名字。

### 方案 E：把 Agent 设置与路由配置合成一个页面

- 优点：少一个导航项，跨页操作更少；
- 否决：两个页面面向不同对象（Agent vs 路由），合并后表单会变成「左路由右 Agent」的复杂布局；
  后续 Agent 参数、提示词也没有扩展空间。

---

## 十三、待确认

1. **路由配置页的 `code` 是否允许编辑**：推荐允许但二次确认；如果希望更稳，可以改成只读，新建后不可改。
2. **停用仍有 Agent 绑定的路由**：推荐允许 + 二次确认（临时下线场景），还是直接禁止停用？
3. **Agent 页交互口径**：只选「路由」（推荐），还是允许选「模型」并由后台自动建专属路由？
4. **内部角色**：允许修改（推荐，带二次确认），还是只读展示？
5. **Agent 级参数**：`temperature / max_tokens / thinking` 是否本期一起做？建议拆到 M3。

---

## 附：现状证据

- 路由解析：`deerfect-server/src/main/java/com/dark/javaHarness/llm/routing/ModelResolver.java`
- 路由执行与失败切换：`deerfect-server/src/main/java/com/dark/javaHarness/llm/routing/RouteExecutor.java`
- 绑定写库：`deerfect-server/src/main/java/com/dark/javaHarness/llm/admin/LlmAdminService.java` 的 `updateAgentRoute`
- 管理接口：`deerfect-server/src/main/java/com/dark/javaHarness/llm/admin/LlmAdminController.java`
- 热刷新：`deerfect-server/src/main/java/com/dark/javaHarness/llm/catalog/LlmConfigRefresher.java`
- Agent 表结构：`deerfect-server/src/main/resources/db/migration/V20__llm_provider_model_route.sql`
- 前端设置页：`deerfect-web/src/components/SettingsView.vue`
- 前端管理 API：`deerfect-web/src/api/admin.ts`
- 现有设计约束：`docs/design/2026-11-12-llm-provider-model-routing.md`
