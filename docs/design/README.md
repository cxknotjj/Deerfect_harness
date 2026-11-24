# 设计文档索引（ADR）

> 本目录保存**设计、决策、评估、迁移与跨版本 program 总案**，只做索引与状态汇总，正文以各文档为准。
>
> **计划不在这里**：新功能/行为变更的计划、任务与验收写 `specs/NNN-xxx/`（Spec Kit 变更区）；
> 实施完成后只把「为什么这样定、否决了什么」提炼成本目录的一篇决策记录。已实施的历史计划见
> [archive/plans](../archive/plans/)。
>
> 状态口径：
>
> - **已实施**：代码/配置与文档结论一致，验收项已完成。
> - **部分实施**：主链路已落地，仍有真机/边界项未完成；具体待办见 `task-checklist/`。
> - **草案**：方向已写，未排期或未评审。
> - **已采纳**：决策记录，结论已进入代码；不一定对应一份完整实施清单。
> - **已否决**：方案未采纳；正文保留原因与替代方案。
>
> 维护规则：设计文档实施后，回写文档头部的「状态」，并在本表更新；设计状态只在本表与文档头维护，不要在 `docs/index.md` 或其它页面再写一份。任务完成数见 [task-checklist/readme.md](../task-checklist/readme.md)。

---

## 一、索引

| 文档 | 类型 | 状态 | 结论 / 替代方案 |
| --- | --- | --- | --- |
| [2026-08-25-chat-reactive-streaming-design.md](./2026-08-25-chat-reactive-streaming-design.md) | 设计 | 已实施 | 流式通道改为 MVC + `Flux<String>` 穿透；未全量迁 WebFlux |
| [2026-08-26-main-agent-route-judge-design.md](./2026-08-26-main-agent-route-judge-design.md) | 设计 | 已实施 | 规则短路 + 灰区 LLM 判定；后续升级置信度/工作流形态，未走全量 LLM 判定 |
| [2026-08-26-multi-agent-graph-design.md](./2026-08-26-multi-agent-graph-design.md) | 设计 | 已实施 | 初版基于 StateGraph；当前替换为自研 `ParallelOrchestrationEngine`，见 [tech-stack.md](../tech-stack.md) |
| [2026-11-08-delivery-reliability-feishu-email-plan.md](./2026-11-08-delivery-reliability-feishu-email-plan.md) | 程序总案 | 部分实施 | outbox / 探活已完成；邮件、飞书剩真机验证；未引入 MQ，走 outbox + 降级链 |
| [2026-11-11-cli-terminal-probe.md](./2026-11-11-cli-terminal-probe.md) | 决策 | 已采纳 | 先判断控制台/TERM 再进 JLine；未给 JLine 打补丁或全局降级 |
| [2026-11-11-external-tools.md](./2026-11-11-external-tools.md) | 决策 | 已采纳 | core / MCP + 声明式 HTTP / skill 三层；WMS 收回独立 adapter，删除重复 YAML |
| [2026-11-11-feishu-channel-design.md](./2026-11-11-feishu-channel-design.md) | 决策 | 部分实施 | webhook 出站 + 长连接入站两条独立链路；M3a 待真实群 webhook 验证 |
| [2026-11-11-session-serialization.md](./2026-11-11-session-serialization.md) | 决策 | 部分实施 | 按会话串行的锁加在「读上下文之前」；飞书已做，QQ 待跟 |
| [2026-11-12-llm-provider-model-routing.md](./2026-11-12-llm-provider-model-routing.md) | 设计 | 已实施 | Agent 只绑 `route_id`，路由按 priority 失败切换；不按模型名查找、不回退默认 |
| [2026-11-23-mem0-long-term-memory-design.md](./2026-11-23-mem0-long-term-memory-design.md) | 设计 | 已实施 | 作为第四路「跨会话自动提取」记忆；自托管 OSS REST，不替换画像/摘要/显式记忆 |
| [2026-11-24-plugin-mechanism.md](./2026-11-24-plugin-mechanism.md) | ADR | 已实施 | 插件注册表 + `MemoryPlugin` 扩展点；mem0 条件装配，注入 order 145、写在 `writeBackBoth` |
| [2026-11-23-xiaohongshu-ops-feasibility.md](./2026-11-23-xiaohongshu-ops-feasibility.md) | 评估 | 草案 | 技术可行但平台规则有风险；不做渠道，走独立 MCP 扩展 + 定时触发 + Agent 决策；core 零业务代码。替代方案：官方 API（资质门槛高）、core 内定时任务（侵入高） |
| [agentic-simple-path-feasibility.md](./agentic-simple-path-feasibility.md) | 评估 | 已实施 | 只引 `agentscope-core`，工具经 Spring AI 桥接；未引入 graph-core |
| [channel-core-module-split.md](./channel-core-module-split.md) | 设计 | 已实施 | 契约 / 内核 / 渠道三层；模块已改名：`harness-api`→`deerfect-api`，`server`→`deerfect-server`，enforcer 守护 |
| [spring-ai-2-migration.md](./spring-ai-2-migration.md) | 迁移 | 已完成 | 去 Spring AI Alibaba，迁 Spring AI 2.0；Boot 4 / Java 25 为被动必选 |
| [super-agent-ideas-adopted.md](./super-agent-ideas-adopted.md) | 决策 / 对照 | 已采纳（部分） | 只记录已落地思想；双层 Router / 常驻事件路由 / 全双工 ReAct / 端侧 daemon 未采纳 |
| [2026-11-23-spec-kit-adoption.md](./2026-11-23-spec-kit-adoption.md) | 决策 | 已实施 | Spec Kit 只做变更流水线：计划进 `specs/`，design 只留 ADR，checklist 只留 program；归档 `docs/archive/specs/` |

---

## 二、已归档计划

实施完成后，计划正文移到 [archive/plans](../archive/plans/)，本表只留一行结论；以后同类变更新写 `specs/NNN/plan.md`，不再回到本目录。

| 计划 | 归档位置 | 结论 |
| --- | --- | --- |
| 响应式流式改造计划 | [2026-08-25-chat-reactive-streaming-plan.md](../archive/plans/2026-08-25-chat-reactive-streaming-plan.md) | 保留 `/api/chat` 同步端点；SSE 走 MVC + `Flux<String>` |
| Agent 模型绑定设置计划 | [2026-11-19-agent-model-binding-settings-plan.md](../archive/plans/2026-11-19-agent-model-binding-settings-plan.md) | 原计划两个同级页，实际合并为单页「路由与 Agent」 |
| 上下文窗口滑块计划 | [2026-11-19-context-window-slider-plan.md](../archive/plans/2026-11-19-context-window-slider-plan.md) | 通过 `PUT /api/admin/models/{id}/context-window` 单键更新 |
| NapCat 接入计划 | [napcat-deerfect-harness-integration-plan.md](../archive/plans/napcat-deerfect-harness-integration-plan.md) | HTTP POST 上报 + HTTP API 下行，未用 WebSocket |

> 另有 2 份 IDE 侧的开发计划（prompt 动态加载、超长类拆分）也归档在 `archive/plans/trae-*.md`，仅作历史记录。

---

## 三、按主题分组

| 主题 | 文档 |
| --- | --- |
| 执行路径 / 编排 | [route-judge 设计](./2026-08-26-main-agent-route-judge-design.md)、[multi-agent graph 设计](./2026-08-26-multi-agent-graph-design.md)、[Agentic 可行性](./agentic-simple-path-feasibility.md) |
| 流式 / 协议 | [响应式流式设计](./2026-08-25-chat-reactive-streaming-design.md)、[流式改造计划（归档）](../archive/plans/2026-08-25-chat-reactive-streaming-plan.md) |
| 模型 / 路由 | [LLM provider/model/route 设计](./2026-11-12-llm-provider-model-routing.md)、[Agent 绑定计划（归档）](../archive/plans/2026-11-19-agent-model-binding-settings-plan.md)、[上下文滑块计划（归档）](../archive/plans/2026-11-19-context-window-slider-plan.md) |
| 渠道 / 投递 | [飞书渠道设计](./2026-11-11-feishu-channel-design.md)、[会话串行](./2026-11-11-session-serialization.md)、[投递可靠性程序总案](./2026-11-08-delivery-reliability-feishu-email-plan.md)、[NapCat 接入（归档）](../archive/plans/napcat-deerfect-harness-integration-plan.md) |
| 工具 / 外部系统 | [外部工具三层模型](./2026-11-11-external-tools.md)、[小红书自动运维可行性](./2026-11-23-xiaohongshu-ops-feasibility.md) |
| 模块 / 迁移 | [Channel/Core 拆分](./channel-core-module-split.md)、[Spring AI 2 迁移](./spring-ai-2-migration.md) |
| 记忆 | [mem0 长期记忆](./2026-11-23-mem0-long-term-memory-design.md) |
| 工程 / 决策方法 | [CLI 终端探测](./2026-11-11-cli-terminal-probe.md)、[Super-agent 落地对照](./super-agent-ideas-adopted.md)、[Spec Kit 文档体系](./2026-11-23-spec-kit-adoption.md) |

---

## 四、新增或更新设计文档

1. **先定类型**：决策（为什么这样做/不那样做）、设计（系统怎么搭）、评估（可行性）、迁移（版本切换）、程序总案（跨多次发布的总体方案）。**计划不在本目录**。
2. **头部写四件事**：状态、日期、范围、结论先行。状态用本页的五个口径之一。
3. **计划去 `specs/`**：新功能/行为变更先走 Spec Kit（specify → plan → tasks，命令见 `specs/README.md`），计划、任务、验收都放 `specs/NNN-xxx/`；实施完成后只把决策提炼进本目录，并在 `spec` 字段标注 `derived-from: specs/NNN`。
4. **记录替代方案**：至少写“考虑过什么、为什么没选”；只写最终方案不算决策记录。
5. **实施后回写**：更新文档头部状态 + 本索引；如果方案有调整，写清“实际落地与本文的差异”，不要假装原方案就是最终方案。
6. **不重复维护状态**：`docs/index.md` 只链接，不写「待实施/已完成」；任务完成数见 `task-checklist/readme.md`，计划状态见 `specs/`。

---

## 五、当前已知差异

| 文档 | 差异 |
| --- | --- |
| [Agent 绑定设置计划（归档）](../archive/plans/2026-11-19-agent-model-binding-settings-plan.md) | 原计划两个同级页面（Agent 设置 + 路由配置）；实际落地为设置页单页「路由与 Agent」，包含路由卡片与 Agent 配置弹窗。文档保留为历史方案 |
| [multi-agent graph 设计](./2026-08-26-multi-agent-graph-design.md) | 初版描述 StateGraph；当前编排引擎为自研 `ParallelOrchestrationEngine`。阅读时以 [tech-stack.md](../tech-stack.md) 与 `deerfect-server/.../graph/` 为准 |
| [Channel/Core 拆分](./channel-core-module-split.md) | 文中模块名 `harness-api` / `server` 为旧名；当前为 `deerfect-api` / `deerfect-server` |
| [投递可靠性程序总案](./2026-11-08-delivery-reliability-feishu-email-plan.md) | 历史版本头部曾写「待实施」；现已改为「部分实施」。实际 outbox / 探活已完成，邮件与飞书待真机验证，进度以 task-checklist 为准 |
