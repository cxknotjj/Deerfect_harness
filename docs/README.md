# javaHarness 文档导航

> 项目主入口在仓库根 [README](../README.md)；本页是 `docs/` 全量索引。
> 目录约定：核心架构文档在根层；使用/部署/协议类在 `guides/`；设计文档在 `design/`（按日期存档）；报告在 `reports/`；历史计划与 spec 在 `superpowers/`。

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
| [architecture.md](./architecture.md) | 架构总览（两路径数据流 + 架构图） |
| [data-flow.md](./data-flow.md) | 数据流详解（路由 / 组装 / 调用 / 记账 / 观测） |
| [project-structure.md](./project-structure.md) | 服务端目录树（[EN](./project-structure_EN.md)） |
| [TECH_STACK.md](./TECH_STACK.md) | 技术栈明细与扩展方向 |
| [HARNESS_TODO.md](./HARNESS_TODO.md) | 落地 TODO / 路线图（未完成 + 已完成存档） |
| [design/](./design/) | 设计文档存档（流式改造 / 路由判定 / 多 Agent 图等，按日期） |

## 功能指南

| 文档 | 内容 |
|---|---|
| [guides/api.md](./guides/api.md) | REST 接口全表与知识库管理页 |
| [guides/cli.md](./guides/cli.md) | CLI 命令（/agent、/goal、/resume 等） |
| [guides/knowledge-rag.md](./guides/knowledge-rag.md) | RAG 知识库配置（[EN](./guides/knowledge-rag_EN.md)） |
| [guides/mcp-tools.md](./guides/mcp-tools.md) | MCP 工具接入（多 server、mcp-config.json） |
| [guides/qq-channel.md](./guides/qq-channel.md) | QQ 机器人接入（NapCat HTTP 模式） |
| [guides/resume.md](./guides/resume.md) | 复杂编排断点续跑 |

## 协议与规范

| 文档 | 内容 |
|---|---|
| [guides/sse-protocol.md](./guides/sse-protocol.md) | SSE 流式协议（事件名 / 转义 / [DONE] / meta）（[EN](./guides/sse-protocol_EN.md)） |
| [guides/commit-convention.md](./guides/commit-convention.md) | 提交规范（Conventional Commits 中文适配） |
| [guides/context-optimization.md](./guides/context-optimization.md) | 上下文优化与 Token 预算策略 |

## 测试与报告

| 文档 | 内容 |
|---|---|
| [guides/functional-testing.md](./guides/functional-testing.md) | 测试全景（类清单 / 场景 / 运行方式） |
| [reports/](./reports/) | 阶段报告存档（CLI 输出优化 / 沙箱接入 / token 预算评审） |
| [superpowers/](./superpowers/) | 历史实现计划与设计 spec 存档（时点记录，不随代码回溯更新） |
