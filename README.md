<div align="center">

[简体中文](README.md) | [English](README_EN.md)

</div>

<div align="center">

<img src="web/public/deer_logo.png" width="88" alt="Deerfect Harness" />

# Deerfect Harness

**基于 Spring AI 的 AI Agent 编排框架 —— 目标驱动的多 Agent 执行外壳**

[![Java](https://img.shields.io/badge/Java-17-007396?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/17/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.x-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-1.1.x-6DB33F?logo=spring&logoColor=white)](https://spring.io/projects/spring-ai)
[![Graph](https://img.shields.io/badge/graph--core-1.1.2.2-orange)](https://github.com/alibaba/spring-ai-alibaba)
[![MySQL](https://img.shields.io/badge/MySQL-Flyway%20Managed-4479A1?logo=mysql&logoColor=white)](https://www.mysql.com/)
[![Tests](https://img.shields.io/badge/tests-JUnit%205%20passing-brightgreen?logo=junit5&logoColor=white)](#-运行测试)
[![Docker](https://img.shields.io/badge/sandbox-Docker%20Isolated-2496ED?logo=docker&logoColor=white)](./docs/docker-deploy.md)

*简单问题直接答 · 复杂任务多 Agent 编排 · 全程流式可视化*

</div>

---

## 这是什么

输入一个目标，框架自动判断简单 / 复杂：**简单问题单次调用直接答**；**复杂任务自动编排** —— Lead 拆解子任务 → researcher / coder / analyst / writer 四类专家并行执行 → 聚合汇总，全程逐 token 流式输出。模型生成的代码在 Docker 沙箱内隔离执行，会话记忆、用户偏好画像、RAG 知识库、断点续跑、LLM 调用观测开箱即用。

- 🔌 **三个入口**：REST API（同步 / SSE 流式）、Claude Code 风格 CLI、QQ 机器人（NapCat）
- 🧩 **数据库驱动的多 Agent / 多模型**：`agent` 与 `model_provider` 两张表配置，新增服务商零代码
- 🔧 **工具生态**：MCP 接入（懒连接 + 失败隔离）+ 按专家最小权限分配

## 🚀 快速开始

**环境**：JDK 17+、Maven 3.8+、MySQL 8.x；可选 Docker（沙箱）、PostgreSQL + pgvector（RAG）、LLM API Key（DashScope / DeepSeek，不配置可启动，调用模型返回 401）。

```sql
CREATE DATABASE IF NOT EXISTS harness DEFAULT CHARACTER SET utf8mb4;
```

```bash
# 打包并启动主服务（Flyway 自动建表）
mvn -DskipTests package
java -jar server/target/javaHarness-server-0.0.1-SNAPSHOT.jar

# 另开终端，进入 CLI 聊天
mvn -pl cli -Pcli compile exec:exec
```

或直接 REST：

```bash
curl -s -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"你好"}'
```

> [!TIP]
> - 不想装环境？Docker 三件套一键起：**[`docs/docker-deploy.md`](./docs/docker-deploy.md)**
> - API Key 配置、一键启动脚本、agent 服务商核对等部署细节：**[`docs/setup.md`](./docs/setup.md)**
> - 国内网络可追加 `-s .mvn/settings.xml` 走阿里云镜像加速（会改用项目内 `.mvn-repo/` 仓库，首次全量重新下载）

## 📖 文档

| 文档 | 内容 |
|---|---|
| [`docs/architecture.md`](./docs/architecture.md) | 架构总览与数据流 |
| [`docs/TECH_STACK.md`](./docs/TECH_STACK.md) | 技术栈明细与扩展方向 |
| [`docs/setup.md`](./docs/setup.md) | 本地部署详解（数据库 / API Key / 启动脚本） |
| [`docs/docker-deploy.md`](./docs/docker-deploy.md) | Docker 三件套部署 |
| [`docs/api.md`](./docs/api.md) | REST 接口全表与知识库管理页 |
| [`docs/cli.md`](./docs/cli.md) | CLI 命令 |
| [`docs/resume.md`](./docs/resume.md) | 断点续跑 |
| [`docs/models.md`](./docs/models.md) | 多模型与多服务商接入 |
| [`docs/knowledge-rag.md`](./docs/knowledge-rag.md) | RAG 知识库配置 |
| [`docs/sse-protocol.md`](./docs/sse-protocol.md) | SSE 流式协议 |
| [`docs/qq-channel.md`](./docs/qq-channel.md) | QQ 机器人接入 |
| [`docs/mcp-tools.md`](./docs/mcp-tools.md) | MCP 工具接入 |
| [`docs/project-structure.md`](./docs/project-structure.md) | 服务端目录树 |
| [`docs/data-flow.md`](./docs/data-flow.md) | 数据流详解 |
| [`docs/functional-testing.md`](./docs/functional-testing.md) | 测试全景 |
| [`docs/HARNESS_TODO.md`](./docs/HARNESS_TODO.md) | 落地 TODO |

## 🧪 运行测试

单元测试基于 JUnit 5 + Mockito，**不依赖真实数据库 / 网络 / API Key**：

```bash
mvn test
```

> [!NOTE]
> `JavaHarnessApplicationTests` 是 `@SpringBootTest`，会尝试连接本机 MySQL；无数据库环境下单独运行该类可能因连接失败报错（其余业务测试不受影响）。测试全景见 [`docs/functional-testing.md`](./docs/functional-testing.md)。

## 🙏 参考与致谢

- 🦌 [Deer-Flow](https://github.com/bytedance/deer-flow)（字节跳动）— 多 Agent 编排范式与专家角色划分
- ⌨️ [Claude Code](https://github.com/anthropics/claude-code)（Anthropic）— CLI 终端交互设计
- 🐳 [DeepSeek](https://github.com/deepseek-ai) — Agent 工具库设计与最小权限思路

---

<div align="center">

**⭐ 如果这个项目对你有帮助，欢迎点个 Star！**

Made with ☕ and ❤️ by Deerfect Harness contributors

</div>
