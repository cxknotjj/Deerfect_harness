# 自包含 web 端 MVP Spec

## Why
server 目前只有 CLI 与 QQ 渠道两个客户端,缺少浏览器访问形态;且多模块拆分后已具备为独立前端铺路的条件。目标是在顶层新增完全自包含的 `web/` 目录(Vue3 + Vite + TS),作为 server 的第二个客户端,不进 Maven 反应堆、不依赖项目任何文件、整目录可拷走独立部署。

## What Changes
- 新增顶层 `web/` 前端工程(Vue3 + Vite + TypeScript),`npm install && npm run dev` 即跑,与 Maven/其他项目文件零耦合
- TS 类型完全独立定义,不参照 shared 源码,以 server HTTP JSON 实际响应为准
- 内置 mock 模式(`VITE_USE_MOCK`),server 不启动也能开发调试 UI(mock 含 SSE 流式模拟)
- MVP 页面:左侧会话列表 + 右侧聊天窗(SSE 流式打字机渲染 markdown)+ agent 切换 + 新建会话
- API Key 鉴权沿用 server 既有机制(header 方式,经 dev proxy 同域传递)
- 依赖最小集:运行时仅 vue + marked + dompurify;不引 pinia/vue-router/组件库/UI 框架
- server 侧零改动(纯新增客户端)
- 明确不做:provider/知识库管理后台、多用户登录、Goal 异步任务页(submit/goals)、WebSocket

## Impact
- Affected specs: 无新增 server 能力;消费既有 `/api/chat`、`/api/harness` 契约
- Affected code: 仅新增 `web/` 目录;不触碰 `shared/`、`server/`、`cli/`、`docker/`

## 已核实的 server 契约面(web 端消费依据)
- 流式聊天:`POST /api/chat/stream`,body 为 ChatRequest 形状 JSON;响应 chunked 文本,SSE 帧格式:`event:` + `data:`,事件类型 meta / error / progress / token,token 块以 `[DONE]` 结束,换行经转义编码(解析逻辑与 server 实际响应逐字段对齐,实施时以 curl 实测响应为准,不以 shared 源码为准)
- 会话管理:`GET /api/harness/sessions`(分页)、`POST /api/harness/sessions`(新建)
- agent:`GET /api/harness/agents`(列表)、`POST /api/harness/sessions/{sessionId}/agent`(切换)
- 鉴权:server 既有 API-Key 机制,header 名以 server 鉴权过滤器实测为准

## ADDED Requirements

### Requirement: 自包含前端工程
`web/` 目录 SHALL 为完全自包含的 Vue3 + Vite + TypeScript 工程:构建与 dev server 不依赖 Maven 与项目其他任何文件;`npm run build` 产物为纯静态文件,可由任意静态服务器(如 nginx)直接托管。

#### Scenario: 独立启动
- **WHEN** 用户仅携带 `web/` 目录到任意机器并执行 `npm install && npm run dev`
- **THEN** dev server 正常启动,浏览器可访问页面(mock 模式默认可用)

#### Scenario: 纯静态产物
- **WHEN** 执行 `npm run build`
- **THEN** `dist/` 为纯静态文件,静态服务器托管后页面可加载

### Requirement: mock 模式
前端 SHALL 内置 mock 模式,由环境变量 `VITE_USE_MOCK` 整体开关:开启时全部 API(会话 CRUD、agent 列表、流式聊天)由内嵌 mock 数据与定时分片模拟实现,无需 server 运行。

#### Scenario: 无 server 开发
- **WHEN** `VITE_USE_MOCK=true` 且 server 未启动
- **THEN** 页面可完成会话列表、新建会话、agent 切换与一轮流式对话演示

### Requirement: 流式聊天
聊天窗 SHALL 通过 `POST /api/chat/stream` 发送消息,并以 fetch + ReadableStream 解析 SSE 帧:token 事件实时追加渲染(打字机效果),progress 事件展示阶段反馈,error 事件展示错误提示,`[DONE]` 结束本轮;markdown 渲染须经 XSS 净化。

#### Scenario: 流式回复
- **WHEN** 用户在输入框发送一条消息
- **THEN** 用户消息立即上屏,助手回复以打字机效果逐 token 渲染,完成后可继续输入

#### Scenario: 阶段反馈
- **WHEN** 流中出现 progress 事件(多 Agent 编排阶段)
- **THEN** 界面展示阶段徽标,不影响 token 追加渲染

### Requirement: 会话管理
页面 SHALL 提供会话列表(分页加载)、新建会话、选中切换;切换 agent 经 `POST /api/harness/sessions/{sessionId}/agent` 生效并影响后续对话。

#### Scenario: 新建并选择会话
- **WHEN** 用户点击新建会话并在列表中选中它
- **THEN** 聊天窗加载该会话上下文(空历史),后续消息发往该会话

### Requirement: agent 切换
聊天窗 SHALL 提供 agent 下拉选择,数据来自 `GET /api/harness/agents`,切换即调用绑定接口。

#### Scenario: 切换 agent
- **WHEN** 用户在当前会话选择另一 agent
- **THEN** 绑定接口调用成功,后续消息由新 agent 处理

### Requirement: dev 代理与鉴权
开发模式 SHALL 通过 vite `server.proxy` 将 `/api` 转发至 `http://localhost:8080` 实现同域;API Key 经环境变量配置,由代理请求统一携带,不出现在页面代码路径中。

#### Scenario: 真实模式联调
- **WHEN** `VITE_USE_MOCK=false` 且本地 server 运行于 8080
- **THEN** 页面完成真实流式对话,鉴权 header 生效
