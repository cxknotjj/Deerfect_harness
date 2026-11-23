# 编码与目录约定

> 目的：把 [AGENTS.md](../../AGENTS.md) 的硬规则、[principal.md](../principal.md) 的架构约束、[commit-convention.md](./commit-convention.md) 的提交规范，落成可执行的命名、错误、日志、测试与前端约定。
>
> 优先级：`AGENTS.md` / `principal.md` 的硬规则 > 本文 > 代码现状。本文与硬规则冲突时，以硬规则为准并修本文；代码与本文冲突时，视为代码待修。
>
> 本文只写能一眼判断“是否违反”的规则，不解释架构原理。原理见 [principal.md](../principal.md) 与 [data-flow.md](../data-flow.md)。

---

## 一、目录与命名

### 1.1 仓库与模块

```text
deerfect-api       契约层：contract.api / contract.spi / contract.model / view
deerfect-server    core 实现 + Spring Boot 宿主
channel-qq         QQ / NapCat 渠道
channel-email      邮件渠道（只出不进）
channel-feishu     飞书渠道（webhook 出站 + 长连接入站）
deerfect-cli       终端客户端（HTTP + SSE）
wms-mcp-adapter    独立 MCP 适配
deerfect-web       Vue 3 控制台
docs               文档（本目录）
```

规则：

- **模块名与目录名一致**，全小写、连字符分隔；不要新建 `common` / `utils` 大杂烩模块。
- core（`deerfect-server`）**编译期不得依赖 `channel-*`**；渠道能力通过 `deerfect-api` 的 SPI 接入。
- 跨模块 DTO 放 `deerfect-api` 的 `view` 包；只在模块内部使用的 DTO 才放模块内。

### 1.2 后端 Java

**包名**

- 全小写，按领域分包：`controller`、`service` / `service.impl`、`domain.entity`、`mapper`、`agent`、`graph`、`llm.*`、`config.*`、`observability`、`validation`、`tool`、`prompt`、`memory`、`knowledge`、`message`。
- 不按技术层新建 `util` 包；工具类放到使用它的领域包，跨模块契约放 `deerfect-api`。
- 包内实现类与接口同包；实现类放 `impl` 子包（`service.impl`、`llm.admin` 等按现有结构）。

**类名后缀**

| 后缀 | 用途 |
| --- | --- |
| `XxxController` | REST / SSE 入口，只做参数接收、调用 service、组装响应 |
| `XxxService` / `XxxServiceImpl` | 业务接口 / 实现 |
| `XxxMapper` | MyBatis-Plus Mapper |
| `XxxEntity` | 数据库行实体，字段与表列对应 |
| `XxxProperties` | `@ConfigurationProperties` 配置载体 |
| `XxxConfig` | 装配 / Bean 声明 |
| `XxxView` / `XxxRequest` / `XxxUpsert` | 对外 DTO / 请求体，优先用 `record` |
| `XxxTest` / `XxxIntegrationTest` | 单测 / 需要 Spring 上下文的集成测试 |

**常量与字符串**

- 跨类复用的协议字面量放常量类（如 `SseProtocol`、`AgentConstants`），不要在多个文件里重复写同一字符串。
- 只在单文件使用的私有常量留在本类，不上提。
- 状态 / 枚举值用枚举或常量，不用裸 `int` / 字符串散落。

**方法**

- 一个方法只做一件事；超过约 50 行时，在类注释或提交说明里写明职责边界与不拆理由（见 principal）。
- 公开方法参数不超过 4 个时优先直接传；超过时用 record / 参数对象，避免长参数列表。
- 内部调用不写防御性空值兜底；边界校验集中在 controller / validation / 外部返回解析。

### 1.3 前端

**目录**

```text
src/api/          HTTP/SSE 客户端，按领域拆文件（admin.ts / chatStream.ts / docs.ts …）
src/components/   视图与组件；按领域建子目录（chat/、monitor/、settings/、resource/）
src/composables/  可复用组合式逻辑，useXxx.ts
src/utils/        纯函数与格式化，xxx.ts
src/styles/       全局样式，按领域拆 xxx.css
src/docs/         文档中心数据源与注册表
```

**组件命名**

| 后缀 | 用途 | 示例 |
| --- | --- | --- |
| `XxxView.vue` | 页面级视图 | `SettingsView.vue`、`MonitorView.vue` |
| `XxxPanel.vue` | 设置页内的面板 | `RouteAgentPanel.vue`、`ModelManagementPanel.vue` |
| `XxxModal.vue` | 弹窗 / 抽屉 | `AgentConfigModal.vue`、`RouteConfigModal.vue` |
| `XxxSelect.vue` | 下拉选择器 | `AgentSelect.vue`、`RoutingSelect.vue` |
| `XxxCard.vue` / `XxxList.vue` / `XxxBar.vue` | 展示型组件 | `StatCard.vue`、`ChatMessageList.vue`、`ChatStatusBar.vue` |

- 组件文件用 **PascalCase**；组合式函数用 **camelCase** 的 `useXxx.ts`；普通工具用 **camelCase** 的 `xxx.ts`。
- 页面私有、不可复用的子组件放页面同目录的子文件夹（`monitor/`、`settings/`），不堆在 `components/` 根层。
- API 调用只走 `src/api/`；组件里不直接 `fetch`。

**样式**

- 全局样式放 `src/styles/<domain>.css`，在 `src/styles/main.css` 的 `@import` 清单里登记；不要在每个 `.vue` 里复制主题变量。
- 颜色、背景、边框、圆角优先用 `tokens.css` 的 CSS 变量（`--bg-card`、`--ink`、`--line-soft`、`--accent` 等）；不要硬编码主题色。
- 明暗主题用 `html[data-theme='dark']` 覆盖；不在 JS 里分支主题色。
- 类名用「领域前缀 + kebab-case」（`.provider-card`、`.route-card`、`.docs-item`）；状态类统一 `is-active` / `is-open` / `is-disabled`。
- 静态样式进 CSS；只有运行时计算的宽度、位置、进度用 `:style`。
- 组件私有且无法复用的样式才用 `<style scoped>`（当前仅 `ContextUsageRing.vue`）；新增前先确认不能放领域 CSS。

**测试文件**

- 单测与被测文件同目录：`Subject.test.ts`（如 `workspaces.test.ts`、`ChatMessageList.test.ts`）。
- e2e 放 `e2e/<domain>.spec.ts`（`chat.spec.ts`、`resources.spec.ts`、`pwa.spec.ts`）。
- 选择器优先 `getByTestId` / `data-testid`，其次语义角色；不要依赖易变的 DOM 层级。

### 1.4 文档

| 目录 | 放什么 | 命名 |
| --- | --- | --- |
| `docs/` 根层 | 总览、约束、架构、数据流、技术栈、目录、开发、运维、路线图 | 固定主题名，如 `overview.md`、`principal.md` |
| `docs/guides/` | 使用、部署、协议、测试、编码约定 | `<主题>.md`，英文变体 `<主题>.en.md` |
| `docs/design/` | 设计、决策、计划、可行性 | `YYYY-MM-DD-<主题>.md`；索引 `README.md` |
| `docs/task-checklist/` | 可执行清单 | `<主题>.md`；汇总 `readme.md` |
| `docs/reports/` | 报告、审计、演示材料 | `YYYY-MM-DD-<主题>.md` |
| `docs/archive/` | 历史存档 | 保持原路径，不随代码回填 |

规则：

- 概览组文档用 front-matter `title` / `order`；`order` 只影响概览排序与序号。
- 设计文档开头写 `状态` / `日期` / `范围` / `结论先行`；实施后回写状态。
- 计划文档必须链接对应 `task-checklist` 验收清单；清单完成后在 [task-checklist/readme.md](../task-checklist/readme.md) 汇总。
- 文档内链接用相对路径 `.md`；不写死行号/锚点（结构变化会失效）。
- 新增或改名文档时，同一次提交更新 [docs/index.md](../index.md)（如果它是入口）。

---

## 二、错误处理与响应

### 2.1 边界原则

- 校验与兜底只放在系统边界：HTTP 入参、外部 API / MCP / 模型返回、反序列化、异步任务入口。
- 内部调用信任类型系统与上游校验；逻辑层不写 `if (x == null) return null` 这类静默兜底。
- 校验规则尽量纯函数化：无 Spring 上下文、无 Mapper、无 IO；失败抛带明确信息的异常。

### 2.2 REST 错误体

统一由 `GlobalExceptionHandler` 输出：

```json
{
  "code": 400,
  "message": "可读的中文原因"
}
```

- `code` = HTTP 状态码，`message` = 面向调用方的可执行原因；不返回堆栈、SQL、密钥。
- Controller 不写 `try/catch` 包业务异常；交给全局处理器。
- 业务校验失败抛 `IllegalArgumentException`；服务未就绪/依赖缺失抛 `IllegalStateException`；不要让它们变成 500。

| 异常 | HTTP | 说明 |
| --- | --- | --- |
| `IllegalArgumentException` | 400 | 入参 / 业务校验失败 |
| `MethodArgumentNotValidException` | 400 | Bean Validation 失败 |
| `MissingServletRequestParameterException` | 400 | 缺少必填参数 |
| `HttpMessageNotReadableException` | 400 | 请求体 JSON 非法 |
| `NoResourceFoundException` | 404 | 资源不存在 |
| `ResumeConflictException` | 409 | 断点续跑冲突 |
| `DuplicateKeyException` | 409 | 唯一键冲突 |
| `ConcurrentRequestException` | 429 | 流式并发超限 |
| `ModelQuotaException` / `ModelAuthException` | 502 | 上游模型配额 / 鉴权失败 |
| `IllegalStateException` | 503 | 服务未就绪 / 依赖未装配 |
| `AsyncRequestNotUsableException` | 500 | 客户端断开（仅 warn 单行，不刷堆栈） |
| 其它 `Exception` | 500 | 兜底，返回「服务器内部错误」 |

### 2.3 SSE 错误

- 事件名常量在 `SseProtocol`：`token` / `progress` / `error` / `meta`。
- 错误事件格式：`event: error` + `data: <可读原因>`；随后跟 `meta`，其中 `status=FAILED`、`error` 同文案。
- 流内错误不改变已发送的 token；客户端以 `meta` 为回合终态。
- 不要新增“无 event 的 data 行”；SSE 的 event 字段是粘滞的，会吞掉后续 token（见 `SseProtocol` 注释）。

### 2.4 前端错误

- `src/api/http.ts` 的 `request` 把非 2xx 转成带 message 的 `Error`；组件展示 `error.message`，不吞异常。
- 401 统一走 `setUnauthorizedHandler`，由 App 切登录视图；不要在每個 API 里单独处理 401。
- 表单校验失败在提交前给出字段级提示；后端 400 的原因原样展示，不二次翻译成“操作失败”。

---

## 三、日志与观测

### 3.1 日志格式

- 后端使用 SLF4J，参数化写法：`log.info("... {}", value)`；不要字符串拼接。
- 控制台是 logstash JSON（`logging.structured.format.console: logstash`）；本地要看文本日志时启动加 `--logging.structured.format.console=`。
- 请求级 `traceId` 由 `TraceIdFilter` 写入 MDC：优先复用请求头 `X-Trace-Id`，否则生成 32 位 hex，并回写响应头。
- 日志前缀用 `[模块]`（如 `[llm]`、`[agent注册]`、`[api-auth]`），便于 grep；新增日志保持同一风格。

### 3.2 级别与内容

- 预期内的客户端断开：`warn` 单行，不打堆栈（`GlobalExceptionHandler` 已处理）。
- 业务校验失败：`info` / `debug`，不要 `error`。
- 未处理异常：`error` + 堆栈；不要吞掉后返回“成功”。
- **绝不打印**：API Key、Cookie、密码、完整请求头、`credentialRef` 对应的值。prompt 与消息正文默认不打印；排障用 `traceId` + 观测表。
- 观测数据写表，不靠日志统计：`llm_call_log` / `tool_call_log` / `kb_retrieval_log` / `mcp_server_log`，分别经 `LlmCallRecorder` 等 recorder 落库。

### 3.3 观测口径

- 成本 / token：统一 `TokenEstimator`，不要新增第二套估算。
- 路由：`route_id` / `route_code` / 实际成员 / `fallback_from_model_id` 由 `RouteAttempt` 记录。
- 上下文快照：`ContextCompositionRecorder`；口径见 [ops.md](../ops.md) 的「会话上下文构成快照」。
- 新增指标前先看 `/actuator/prometheus` 现有指标，避免重复计数。

---

## 四、测试

### 4.1 后端

- JUnit 5 + Mockito；测试包与被测类同包。
- 类名：`XxxTest`；需要 Spring 上下文/真实 DB 的用 `XxxIntegrationTest`。
- 方法名：推荐 `method_scenario_expected`（如 `updateAgentRoute_unknownAgent_rejected`），与现有测试一致。
- 只测行为与契约，不为私有方法写单测；重构可以删过时测试，但覆盖面不得下降。
- 修 bug 必须补一条能复现的行为级测试，并在提交说明里指向它。
- 测试不得依赖本地库额外数据；全新空库应能跑通（见 `reports/2026-11-14-test-data-dependency-audit.md`）。
- 命令见 [dev.md](../dev.md) 的「Maven 常用命令」；跨模块或 SQL 变更需全量 `install`。

### 4.2 前端

- 单测：Vitest，`Subject.test.ts` 与被测文件同目录；描述写行为，不写实现细节。
- e2e：Playwright，`e2e/<domain>.spec.ts`；默认 `VITE_USE_MOCK=true`，不依赖后端。
- 组件测试优先断言可见文本、`data-testid`、可访问角色；不要断言内部 class 链。
- 改样式/布局时至少跑相关 e2e；改 API 类型时跑 `vue-tsc`。

### 4.3 提交前最小验证

命令行统一写在 [dev.md](../dev.md) 的「Maven 常用命令」「前端开发与验证」，本节只定
**改什么必须跑什么**：

| 改动范围 | 必须通过 |
| --- | --- |
| 后端模块 | 该模块 `-am test`；跨模块或 SQL/迁移变更跑全量 `install` / `verify` |
| 接口 / DTO | 后端 `verify` + 前端 `npm run typecheck` |
| 前端逻辑 / 样式 | `npm run typecheck`、`npm run lint`、`npm test` |
| 交互 / 布局 | 追加 `npm run test:e2e` |
| 构建 / 资源 / 文档打包 | 追加 `npm run build`（改 Pages 产物时跑 `build:pages`） |

不在两处维护命令行：命令变了只改 `dev.md`，这里只改「跑什么」。

---

## 五、前端 UI 约定

- **主题**：颜色只用 `tokens.css` 变量；明暗主题用 `html[data-theme='dark']` 覆盖；不要在组件里写死 `#fff` / `#000`。
- **响应式**：复用现有断点（`1200` / `900` / `720` / `360`）；手机端 `height: 100dvh`，底部用 `env(safe-area-inset-bottom)`。
- **点击目标**：移动端图标按钮不小于 32×32；关键操作（发送、关闭、确认）不要小于 34×34。
- **可访问性**：优先原生 `button` / `input`；展开态用 `aria-expanded`，当前项用 `aria-current`；键盘 `Esc` 关闭浮层并回焦触发按钮；弹窗内 `Tab` 循环。
- **焦点**：可交互元素保留 `:focus-visible` 轮廓；不要全局 `outline: none`。
- **富文本**：`v-html` 只允许渲染已经 `DOMPurify.sanitize` 的内容（现有 `DocsView`、`MessageBubble` 两处都先净化）；新入口必须同样净化，并加 `eslint-disable-next-line vue/no-v-html` 说明原因。
- **数据**：主题/字号/密度等本机偏好走 `utils/uiPrefs.ts`；页面私有 localStorage 键集中在模块顶部的 `KEYS` / `STORAGE_KEY` 常量，不散落字符串。历史键（`web-theme`、`harness-chat-cache` 等）保持兼容，不做无收益改名。
- **文案**：界面用中文；错误提示写“发生了什么 + 下一步怎么做”，不要只写“失败”。

---

## 六、提交与文档

- 提交格式、type/scope、双远程、`git add` 纪律见 [commit-convention.md](./commit-convention.md)。
- 一个 commit 只承载一个功能或修复；不 `git add -A` / `git add .` 盲加。
- 结构与接口变化同一次提交更新文档；新增文档更新 [docs/index.md](../index.md)。
- 设计文档实施后回写状态，并在 [design/README.md](../design/README.md) 的 ADR 索引更新；不要在多个页面重复维护状态。
- 任务完成数只在 [task-checklist/readme.md](../task-checklist/readme.md) 汇总；设计文档状态只在 [design/README.md](../design/README.md) 汇总；其它页面只链接，不写“待实施/已完成”。

---

## 七、快速检查清单

- [ ] 新类/组件/样式文件的命名符合上表，放在正确目录
- [ ] 边界校验只在 controller / validation / 外部返回解析
- [ ] 错误走 `ErrorResponse`，Controller 没有吞异常
- [ ] 日志没有密钥/凭证，traceId 能串起请求
- [ ] 修 bug 补了行为级测试；新测试不依赖本地库数据
- [ ] 前端只用 tokens 变量，明暗主题都看过
- [ ] `v-html` 内容已净化
- [ ] 文档与代码同批更新，状态没有写第二份
