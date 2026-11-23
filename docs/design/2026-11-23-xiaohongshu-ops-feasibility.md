# 小红书自动运维（发笔记 / 回评论）可行性分析（2026-11-23）

> 状态：草案（待评审）　日期：2026-11-23　范围：可行性与架构选型分析，不含实施代码
>
> 结论先行：**技术上可行，但合规与账号风控是最大不确定性，不是技术难度。**
> 形态上回答三个问题：
> 1. **工作流还是定时任务**：都不是单选——**定时任务只负责"到点触发"，决策与执行交给 harness 的 Agent（ReAct/Graph）调用 MCP 工具**；即「定时触发 + 工作流执行」两层，且触发层尽量放在 harness 之外。
> 2. **渠道还是插件/扩展**：**不是渠道，是外部 MCP 扩展**。渠道是"入站会话 + 文本外发"，小红书运维是"对外部内容平台的主动副作用动作"，语义不同；塞进渠道会污染 `deerfect-api` 契约。
> 3. **如何保持纯净**：独立进程 + 标准 MCP + 配置/数据驱动。**harness 侧零业务代码**：不建 `xhs` 包、不进渠道模块、不进 fat jar；账号、Cookie、话术、发布节奏全部留在 adapter 与 prompt/knowledge 中。

---

## 一、外部可行性：两条路径，别走错

| | 官方开放平台 API | 无头浏览器 + MCP（推荐） |
| --- | --- | --- |
| 可用的能力 | 公开文档类目只有**电商**：一键搬家、商品优化、打单、订单管理、企业 ERP、商家后台系统（商品/订单/售后/库存/素材中心）；**公开类目里没有笔记发布、评论/回复权限包** | `xpzouying/xiaohongshu-mcp`（Go，Apache-2.0，约 16k star）：登录态、发图文/视频、搜索、看详情和评论、发评论、回评论、点赞、收藏等 11 类能力 / 13 个工具 |
| 主体门槛 | 企业/个体工商户资质或商家自研，创建应用 + 权限审核；第三方文章声称有 `/api/v1/note/publish`，但**官方文档公开类目未列出，须向平台确认**，不能作为方案基础 | 个人号即可；首次人工扫码登录，Cookie 存本机（`COOKIES_PATH`），请求不经第三方 |
| 接入形态 | OAuth2 授权码 + Token 管理 + QPS 限制 | 本地/Docker 进程，`http://127.0.0.1:18060/mcp` 标准 MCP；`AUTH_TOKEN` + `Authorization: Bearer` 可开鉴权 |
| 风险 | 资质拿不到就完全不可用；接口范围可能不含内容发布 | **平台规则灰色地带**：作者标注「仅供学习」，重度自动化有风控风险；Cookie 会过期、同账号不能多网页端同时登录、可能触发 `/website-login/captcha` 扫码验证 |
| 结论 | 不作为本方案基础；若公司有商家资质且平台明确给内容接口，可另起 adapter 替换 | **作为本次可行路径**：先做受控的半自动（人工确认发布），验证稳定后再谈无人值守 |

**必须如实告诉决策者的事实**：这不是官方支持的能力。社区项目的经验是"稳定跑一年多没封号，只遇到 Cookie 过期需重新登录"，但这是个人经验，不是平台承诺。平台对引流、纯搬运是重点打击对象。因此方案设计里要保留人工审批开关和低频策略，不能一上来就无人值守群发。

另外，二手资料提到过 `schedule_at` 定时发布（1 小时~14 天），但 GitHub README 当前文本未确认该参数，**以实测为准，不要写进契约**。

---

## 二、为什么不是渠道

| 维度 | 现有渠道（QQ / 飞书 / 邮件） | 小红书运维 |
| --- | --- | --- |
| 入站 | 用户消息 → `SessionService.createSession("qq:"+uid, ...)` → `ChatService.chat` | 没有"用户给 harness 发消息"；是读平台评论/笔记数据 |
| 出站 | `MessageSender.send(target, text)`：纯文本投递到会话目标 | 发笔记需要标题、正文、图片、标签；回评论需要 `feed_id` + `xsec_token` + `comment_id`——**结构化载荷，不是纯文本** |
| 状态 | 会话绑定表、会话生命周期 | 平台对象 ID、登录态 Cookie、发布排期 |
| 契约 | 4 个 SPI：`MessageSender` / `ReplyPostProcessor` / `SessionBindingCleaner` / `ChannelHealth` | 现有 SPI 装不下；做渠道就得在 `deerfect-api` 加新端口，**污染共享契约** |
| 构建 | server pom 里是 `runtime`，enforcer 显式禁止 compile | 做渠道要改 enforcer excludes 与 server pom |

结论：渠道模块的抽象是围绕"会话"建的，小红书运维是"动作执行器"。把它做成渠道，等于用会话模型硬套内容平台，既别扭又要改 core 契约。

---

## 三、推荐架构：三层，harness 只当大脑

```text
┌──────────────────────────────────────────────────────────────┐
│ 触发层（harness 之外，或现有 ScheduleService 仅提交目标）      │
│   系统 cron / CI scheduled / 独立小服务                       │
│   → POST /api/harness/submit  { agent, objective }            │
│   只说"去巡检并回复评论"，不含任何小红书细节                    │
└───────────────────────────┬──────────────────────────────────┘
                            ▼
┌──────────────────────────────────────────────────────────────┐
│ 决策层 = harness（core 零业务代码）                            │
│   AgentService.submit → Goal → AgentScope ReAct               │
│   工具来自 mcp-config.json（MCP 白名单授权给该 agent）          │
│   平台知识/话术/发布规范 → knowledge 目录（RAG）或 system prompt │
└───────────────────────────┬──────────────────────────────────┘
                            ▼  MCP over HTTP (18060/mcp)
┌──────────────────────────────────────────────────────────────┐
│ 执行层 = 独立 xiaohongshu-mcp 进程（外部，非本仓库代码）        │
│   无头浏览器 + 本地 Cookie + 13 个工具                        │
│   账号/登录态/平台页面细节/验证码提示，全部封在这里             │
└──────────────────────────────────────────────────────────────┘
```

这正好复用项目已有的**外部工具三层模型**（`docs/design/2026-11-11-external-tools.md`）：core 只放需要运行时上下文的能力，外部系统走 MCP；`wms-mcp-adapter` 已经证明"独立进程 + MCP + 配置接入"可以做到 harness 侧零代码改动。

**工作流 vs 定时任务的明确回答**：

- 用**定时任务**做"什么时候跑"：优先外部调度器调 `POST /api/harness/submit`；也可复用现有 `ScheduleService`（`agent_schedule` 表 + 6 段 cron + 10s tick → `AgentService.submit`），这只加一行数据，不改 core 代码。注意它是**单实例假设、无分布式锁、失败也推进 `next_run_at`**，多实例部署会重复触发。
- 用**工作流/Agent**做"跑什么"：harness 没有通用工作流引擎（项目文档明确"不是通用工作流平台"，只有 Goal + 固定的 lead → 子任务 → 聚合）。"读评论 → 判断 → 生成回复 → 发布"是一个 ReAct 循环，用现有 AgentScope 路径即可；只有"选题 → 写稿 → 配图 → 发布 → 回评"这种多阶段才需要 Graph 编排，且那是复用现有能力，不是为小红书新建。
- **不要把发帖动作本身塞进 `@Scheduled` 方法**：副作用动作放在 Agent 的工具调用里，才能被观测、重试、人工审批与审计覆盖。

---

## 四、落地清单：harness 侧到底改什么

| 项 | 改动 | 说明 |
| --- | --- | --- |
| MCP 配置 | `mcp-config.json` 加一项 `xiaohongshu: { url: "http://host:18060/mcp" }` | 与 wms 同级 |
| **MCP headers 缺口** | `tool/mcp/McpConfigParser.java:53-68`、`McpToolProvider.java:73` 的 `ServerSpec` 目前只解析 `command/args/url`，**不支持 headers**；而 xiaohongshu-mcp 开启鉴权后要求 `Authorization: Bearer <token>` | 需补一个通用 `headers` 字段（这是通用能力，不是小红书耦合）；或把 adapter 放内网且不开 `AUTH_TOKEN`（不推荐） |
| agent 授权 | 新增/更新 agent 行的 `tools` 列，列出需要的 MCP 工具名 | 与 V13 给 memory 加组名同一机制；当前 Flyway 最大 V26，新增从 V27 起 |
| 平台知识 | 话术、违禁词、发布规范放 `knowledge/` 走 RAG，或写进 agent prompt | **数据驱动，零代码** |
| 触发 | 外部 cron 调 `/api/harness/submit`，或 `agent_schedule` 加一行 | 不改 core |
| 编译边界 | **不建 `channel-xhs` 模块**、不在 server pom 加依赖、不改 enforcer | 与 WMS 先例一致 |
| 凭证 | Cookie / `AUTH_TOKEN` 只在 xiaohongshu-mcp 侧的环境变量与文件里 | 不进 harness 的 `.env.local`、不进数据库、不进日志 |
| 幂等 | "已回复的评论 ID"需要有去重记录 | adapter 侧自己做，或 V27 建独立小表；**不要复用 `delivery_outbox`**——它没有 `(source, ref_id)` 唯一键，且 `SENT` 只保留 7 天 |
| 审计 | 复用 `tool_call_log`（含 server_name）+ `HarnessMetrics`；长期追溯另建表 | 现有观测是 LLM/工具维度，没有"笔记 ID / 评论 ID"维度 |
| 降级 | 工具失败返回结构化文本（SPI/边界原则），由 Agent 决定是否重试 | core/service 层禁止静默兜底 |

**对 core 的侵入**：仅 `McpConfigParser` / `ServerSpec` 的 headers 支持这一处通用改动（约 1 个字段 + 解析逻辑）；其余全是配置与数据。若选择不开 adapter 鉴权（内网隔离），则 **core 源码零改动**。

---

## 五、风险清单

1. **平台风控（最高）**：自动化处于规则灰色地带；可能被要求扫码验证、Cookie 失效、限流甚至封号。缓解：人工审批开关、低频、禁用引流/搬运、先实名。
2. **登录态冲突**：同一小红书账号不能在多个网页端同时登录；harness 侧不要同时开自己的沙箱浏览器去登同一账号。
3. **现有沙箱浏览器不够用**：`SandboxBrowserTools` 只有导航/快照/点击/输入/按键/关闭 6 个工具，无图片上传、无 Cookie 持久化、无验证码处理——**不要试图用它实现小红书发帖**，直接用现成 MCP adapter。
4. **声明式 HTTP 不够用**：`JdkHttpTransport` 只实现 GET，无 POST/JSON/multipart，不能发笔记。
5. **重复动作**：`ScheduleService` 单实例假设 + 失败也推进时间点；副作用动作必须有幂等键（评论 ID / 笔记草稿 ID）。
6. **MCP headers 缺口**：不补就只能在 adapter 上关鉴权，内网隔离必须做实。
7. **内容合规**：标题 ≤20 字、正文 ≤1000 字、图文至少 1 张图、违禁词与引流检查——这些要进 agent prompt/校验，不能指望平台接受后再报错。
8. **凭证安全**：Cookie 文件等价于账号密码，不能进 git、不能进日志、不能随 fat jar 分发。
9. **长期审计缺失**：`tool_call_log` 90 天、`outbox SENT` 7 天；运营追溯要自建表。

---

## 六、建议分期与待拍板问题

| 阶段 | 内容 | 门槛 |
| --- | --- | --- |
| S0 | 人确认账号与合规边界；部署 xiaohongshu-mcp（内网、开 `AUTH_TOKEN`）、扫码登录；用 MCP Inspector 验证 13 个工具 | **人前置** |
| S1 | 补 MCP headers 支持；配置接入；建"只读巡检" agent（搜索/读评论，不写） | AI |
| S2 | 加"生成回复草稿" agent，输出人工审批（不自动发） | AI |
| S3 | 人工审批后调用发评论/发笔记工具；补幂等与审计表 | 人决策后 AI |
| S4 | 评估是否放开定时无人值守、是否多账号 | 人决策 |

待拍板：

- 用个人号还是企业号？能否接受灰色地带的账号风险？
- 先只读/草稿，还是直接自动发布？（建议从 S1/S2 起步）
- 触发用外部 cron 还是 harness `ScheduleService`？部署是否多实例？
- MCP headers 是补通用能力，还是内网裸连？
- 幂等/审计表是否新增 V27？还是只靠日志？
- 是否允许 harness 读取平台数据（搜索、用户信息）进入 RAG 或画像？（数据合规）

---

## 七、参考

- 项目内：`docs/design/2026-11-11-external-tools.md`、`docs/design/channel-core-module-split.md`、`docs/principal.md`、`docs/reports/2026-11-14-test-data-dependency-audit.md`
- 小红书开放平台公开文档（应用类目与权限）：https://xiaohongshu.apifox.cn/
- 开源 MCP adapter：https://github.com/xpzouying/xiaohongshu-mcp
- 第三方生态对比（仅参考）：https://omnigoai.com/zh/blog/omnipost-vs-xiaohongshu-mcp
