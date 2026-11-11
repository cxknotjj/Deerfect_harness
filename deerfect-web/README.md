# web

Vue 3 + TypeScript + Vite 前端控制台：会话管理、流式对话、思考折叠、轨迹观测、监控页、登录与 PWA 离线。

**不在 Maven 反应堆里**——它不进 `server` 的 fat jar，独立构建、独立部署（Nginx / 静态托管 / `vite preview`）。
和后端的唯一约定是 HTTP：开发态由 Vite 把 `/api` 代理到 `localhost:8080`。

## 目录结构

```text
deerfect-web/
├── index.html
├── vite.config.ts          # 开发端口 5173，/api → localhost:8080，含 PWA 插件
├── vitest.config.ts        # 单元测试
├── playwright.config.ts    # 端到端
├── TODO.md
├── public/                 # 原样拷贝的静态资源（图标等）
├── e2e/                    # Playwright：chat.spec.ts / pwa.spec.ts
├── dist/                   # 构建产物，不入库
└── src/
    ├── main.ts / App.vue
    ├── api/       (14)     # HTTP 与 SSE 客户端
    ├── components/ (11+8)  # 视图与组件
    ├── composables/ (9)    # 组合式状态
    ├── styles/    (15)     # 按区域拆分的 CSS
    └── utils/     (8)      # 纯函数与格式化
```

### `src/api/` —— 与后端说话

| 文件 | 作用 |
| --- | --- |
| `client.ts` / `http.ts` | 基础请求封装：baseURL、token、统一错误 |
| `auth.ts` | 登录态（配合 `LoginView` 与后端 `config/security`） |
| `chatStream.ts` | `POST /api/chat/stream` 的 SSE 解析：`data:` 帧 → 进度 / token / meta 事件 |
| `observability.ts` | 轨迹与调用日志查询 |
| `endpoints.ts` | 路径常量集中管理，避免散落的字符串 |
| `mock/` | 无后端时的假数据，供纯前端开发与组件测试 |

每个 `*.ts` 旁边基本都有 `*.test.ts`——这一层是纯函数，适合单测。

### `src/components/` —— 视图与组件

| 分组 | 内容 |
| --- | --- |
| 聊天主界面 | `ChatWindow`、`Composer`、`MessageBubble`、`SessionList`、`AgentSelect`、`RoutingSelect`、`ContextUsageRing` |
| `chat/` | `ChatMessageList`（消息列表与折叠）、`ChatStatusBar`（阶段/耗时状态条） |
| `monitor/` + `MonitorView` | `MonitorSummary`、`MonitorBreakdown`、`MonitorRecentCalls`、`MonitorSessions`——对应后端 `/api/monitor` 与观测接口 |
| `trace/` + `TraceRow` | 单次执行的轨迹行（工具调用、耗时、token） |
| 其他 | `LoginView`（登录）、`OfflineView`（PWA 离线兜底） |

### `src/composables/` —— 状态与副作用

`useChat`（一轮对话的发送/流式接收/中断）、`useSessions`（会话列表与切换）、
`useAgents`（Agent 下拉）、`chatCache`（会话消息缓存）、`globalError`（全局错误提示）、
`pwa`（Service Worker 注册与更新提示）。

### `src/styles/` 与 `src/utils/`

样式按区域拆分（`chat.css` / `monitor.css` / `login.css` / `motion.css` …），入口 `main.css` 汇总。
`utils/` 是纯函数：相对时间、会话排序、监控数值格式化、轨迹格式化——同样带单测。

## 开发与验证

```bash
cd deerfect-web
npm install
npm run dev        # 5173，/api 代理到 8080
npm run typecheck  # vue-tsc --noEmit
npm run lint
npm run test       # vitest
npm run test:e2e   # playwright（需要先起后端，或走 mock）
npm run build      # 产物在 dist/
```

## 运行时行为

**请求超时**：普通 REST 统一走 `fetchWithTimeout`（默认 30s，`VITE_API_TIMEOUT_MS` 覆盖）：
后端挂起时抛 `请求超时（30s）：/api/...` 并带原始 `cause`，不会无限等待。`login` 虽然单独
`fetch`（为了不触发 401 全局钩子），也走同一条超时封装。

**全局错误提示**：三处异常入口收敛到 `composables/globalError.ts`——`app.config.errorHandler`
（根组件/渲染）、`unhandledrejection`（异步未捕获）、根组件 `onErrorCaptured`（子树，返回 false
阻止向上）。顶部红色横幅显示「类型: 首行消息」，8s 自动消失、可手动关闭；完整堆栈仍打 console。
有横幅时页面其余部分照常可用，不再整页白屏。

## 边界

- **不含业务逻辑**：路由判断、会话存储、工具调用都在 `server`；前端只负责呈现与交互。
- **不参与 Maven 构建**：根 `pom.xml` 没有 `web` 模块，`mvn verify` 不会跑前端检查。
  改了前端要单独跑 `npm run typecheck && npm run test`。
- **`dist/` 不入库**：由构建流水线或部署时生成。
- **PWA 有缓存**：改完前端如果看不到变化，先确认 Service Worker 是否还在用旧缓存
  （`pwa.ts` 里有更新提示逻辑）。
