---
title: "文档导航"
order: 1
updated: 2026-11-23
---

# 文档导航

> 项目主入口在仓库根 [README](../README.md)；第一次接触建议先读 [overview.md](./overview.md)，本页是 `docs/` 全量索引。
> 目录约定：核心架构文档在根层；使用/部署/协议类在 `guides/`；设计文档在 `design/`（只收决策/设计/评估，[README](./design/README.md) 是 ADR 索引；**在途计划在仓库根 `specs/`**）；报告在 `reports/`；历史实现计划与设计 spec 在 `archive/specs/`，一次性开发/运维历史在 `archive/`。
> 历史设计文档正文里出现的 `docs/superpowers/...` 是 2026-10-30 文档压平前的旧目录，现已合并到 `archive/specs/`；这些存档按「时点记录」保留，不再逐个回填路径。
> **非文档数据**：`knowledge/**` 是 RAG 知识库语料（含演示错误码，被测试套件引用）、`user-profile/user-profile.md` 是运行时画像数据，两者不是项目文档，不随代码基线更新。

## 总览

| 文档 | 内容 |
|---|---|
| [overview.md](./overview.md) | 项目总说明：定位、边界、技术栈、模块图、目录入口、不可违反的原则 |
| [README](../README.md) | 仓库首页：亮点与快速开始（[EN](../README_EN.md)） |

## 项目约束

| 文档 | 内容 |
|---|---|
| [principal.md](./principal.md) | 架构与修复原则（代码精简 / 兜底边界 / 分层依赖 / 重构纪律 / 测试口径） |
| [dev.md](./dev.md) | 本地开发环境，只收三类：环境事实 / 本机操作 / FAQ（设计决策见 `design/`） |
| [ops.md](./ops.md) | 运行观测与运维：指标、上下文快照、保留期、探活告警 |

## 快速开始

| 文档 | 内容 |
|---|---|
| [guides/setup.md](./guides/setup.md) | 本地部署详解（数据库 / API Key / 启动脚本 / agent 服务商核对） |
| [guides/docker-deploy.md](./guides/docker-deploy.md) | Docker 三件套部署（构建 / ACR 拉取 / 离线部署） |
| [guides/models.md](./guides/models.md) | 多模型与多服务商接入（含 thinking 开关） |
| [guides/first-run-audit.md](./guides/first-run-audit.md) | 首次使用流程规则与走查记录 |

## 架构与设计

| 文档 | 内容 |
|---|---|
| [design/agentic-simple-path-feasibility.md](./design/agentic-simple-path-feasibility.md) | Agentic 路径可行性（AgentScope 评估、落地结果与依赖冲突） |
| [architecture.md](./architecture.md) | 架构总览（两路径数据流 + 架构图） |
| [data-flow.md](./data-flow.md) | 数据流详解（路由 / 组装 / 调用 / 记账 / 观测） |
| [project-structure.md](./project-structure.md) | 服务端目录树（[EN](./project-structure.en.md)） |
| [tech-stack.md](./tech-stack.md) | 技术栈明细与扩展方向 |
| [design/super-agent-ideas-adopted.md](./design/super-agent-ideas-adopted.md) | Super-agent 文章中已在本项目落地的思想对照（Fast/Slow Path / 投机执行 / Inline & Spill / 链式召回 / 状态分离 / 多信道 / 事件入口 / Watchdog） |
| [design/2026-11-08-delivery-reliability-feishu-email-plan.md](./design/2026-11-08-delivery-reliability-feishu-email-plan.md) | 主动投递可靠性规划书（outbox 重投 + 通道降级链 + 探活告警 + 飞书/邮件渠道） |
| [design/2026-11-11-feishu-channel-design.md](./design/2026-11-11-feishu-channel-design.md) | 飞书渠道设计决策（长连接 / 入站 / 指令 / 表情 / 回复形态 / 语音 / 文件下发） |
| [design/2026-11-11-session-serialization.md](./design/2026-11-11-session-serialization.md) | 渠道侧按会话串行：锁为什么必须加在读上下文之前 |
| [design/2026-11-11-external-tools.md](./design/2026-11-11-external-tools.md) | 外部工具三层模型（core / MCP + 声明式 HTTP / skill）与 WMS 收敛 |
| [design/2026-11-11-cli-terminal-probe.md](./design/2026-11-11-cli-terminal-probe.md) | CLI 终端探测 40 秒阻塞的根因与最终取舍 |
| [design/2026-11-12-llm-provider-model-routing.md](./design/2026-11-12-llm-provider-model-routing.md) | LLM 供应商 / 模型 / 路由三层模型与 Agent 绑定设计 |
| [design/2026-11-23-mem0-long-term-memory-design.md](./design/2026-11-23-mem0-long-term-memory-design.md) | mem0 长期记忆扩展设计（与现有三套记忆的边界、自托管 API 对接、身份映射与写入钩子） |
| [design/2026-11-23-xiaohongshu-ops-feasibility.md](./design/2026-11-23-xiaohongshu-ops-feasibility.md) | 小红书自动运维可行性（官方 API vs 浏览器 MCP、为何不是渠道、定时触发 + Agent 执行 + 独立 adapter、core 零业务耦合） |
| [design/README.md](./design/README.md) | 设计文档索引（ADR）：类型、状态、结论与替代方案 |

## 功能指南

| 文档 | 内容 |
|---|---|
| [guides/api.md](./guides/api.md) | REST 接口全表与知识库管理页 |
| [guides/cli.md](./guides/cli.md) | CLI 命令（/agent、/goal、/resume 等） |
| [guides/knowledge-rag.md](./guides/knowledge-rag.md) | RAG 知识库配置（[EN](./guides/knowledge-rag.en.md)） |
| [guides/mcp-tools.md](./guides/mcp-tools.md) | MCP 工具接入（多 server、mcp-config.json） |
| [guides/wms-mcp.md](./guides/wms-mcp.md) | WMS 仓储查询 MCP 适配（独立 adapter、只读工具集、双侧鉴权） |
| [guides/qq-channel.md](./guides/qq-channel.md) | QQ 机器人接入（NapCat HTTP 模式） |
| [guides/resume.md](./guides/resume.md) | 复杂编排断点续跑 |

## 协议与规范

| 文档 | 内容 |
|---|---|
| [guides/sse-protocol.md](./guides/sse-protocol.md) | SSE 流式协议（事件名 / 转义 / [DONE] / meta）（[EN](./guides/sse-protocol.en.md)） |
| [guides/coding-conventions.md](./guides/coding-conventions.md) | 编码与目录约定（命名 / 错误体 / 日志 / 测试 / 前端样式） |
| [guides/commit-convention.md](./guides/commit-convention.md) | 提交规范（Conventional Commits 中文适配） |
| [guides/context-optimization.md](./guides/context-optimization.md) | 上下文优化与 Token 预算策略 |

## 测试与报告

| 文档 | 内容 |
|---|---|
| [guides/functional-testing.md](./guides/functional-testing.md) | 测试全景（类清单 / 场景 / 运行方式） |
| [reports/](./reports/) | 阶段报告存档（CLI 输出优化 / 沙箱接入 / token 预算评审） |
| [archive/specs/](./archive/specs/) | 历史实现计划与设计 spec 存档（时点记录，不随代码回溯更新） |
| [archive/2026-11-11-dev-history.md](./archive/2026-11-11-dev-history.md) | dev 历史记录（Flyway 压平、飞书八项加固；时点记录） |
| [task-checklist/readme.md](./task-checklist/readme.md) | 可执行清单汇总：当前进度、已完成清单与记录约定（任务状态唯一汇总入口） |
