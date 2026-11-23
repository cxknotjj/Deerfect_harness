# AGENTS.md

本仓库是 Maven 多模块 Java 项目（Spring Boot 4 + Spring AI + AgentScope），核心是 `deerfect-server` 模块。

## 动手前必读

- **先读** [docs/overview.md](docs/overview.md)（项目总说明：定位、边界、技术栈、模块图、目录入口、不可违反的原则）
- 改代码前：读 [docs/principal.md](docs/principal.md)（架构与修复原则）
- 编码/命名/错误体/日志/测试约定：读 [docs/guides/coding-conventions.md](docs/guides/coding-conventions.md)
- 跑项目/测试前：读 [docs/dev.md](docs/dev.md)（本地工具、端口、已知坑）
- 目录与模块边界：见 [docs/project-structure.md](docs/project-structure.md)
- 文档索引与状态：见 [docs/index.md](docs/index.md)、[docs/design/README.md](docs/design/README.md)、[docs/task-checklist/readme.md](docs/task-checklist/readme.md)
- 做新功能/行为变更前：读 [specs/README.md](specs/README.md)（Spec Kit 变更流程：spec → plan → tasks → 归档）

## 硬规则

- Git 提交：功能完成且按项目规定跑完测试并通过后，可直接 commit 该功能的代码、测试与同批必需文档（细则见 [.trae/rules/git-workflow.md](.trae/rules/git-workflow.md)）；`git push` 仍需用户明确要求，禁止擅自推送。
- Maven 命令必须带 `-s .mvn/settings.xml`；单模块构建/测试必须带 `-am`。
- 重构期不留兼容层：旧构造器、旧重载、旧接口直接删，不做旧版本兼容。
- 逻辑层不做防御性兜底，兜底与校验只放在系统边界（HTTP 入参、外部 API/MCP/模型返回、反序列化）。
- 修 bug 优先结构化根治；只有上游缺陷且根治成本过高时才允许最小补丁，并注释根因。
- 数据库变更只新增 Flyway 迁移（编号取 `db/migration` 目录当前最大版本 +1，不在文档里写死当前版本），不改已发布的迁移文件；迁移若被 `@SpringBootTest` 提前应用到本地库，改脚本后须按 `flyway repair` 校正 checksum 再启动。
- 不提交 `.mvn-repo/`、`target/`、`.idea/`、`.vscode/`、`.trae/` 与本地密钥文件。
- 在修改dev.md新增知识之前，确保知识是可以复用的。
- 修改新增文档时，不要写死锚点。
- **变更流程**：新功能/行为变更先走 Spec Kit（specify → plan → tasks，命令前缀见 `specs/README.md`），产物只放 `specs/NNN-xxx/`；**不要再往 `docs/design/` 写计划**。实施完成后按 tasks 最后一阶段蒸馏（overview 能力面 / guides / ADR），目录归档到 `docs/archive/specs/`。
- **单点 bug**（<1 天、无契约变化）直接修 + 补行为测试，根因写进 FAQ 或 ADR；跨模块或改契约的 bug 用 Spec Kit 的 bug 扩展（`.specify/bugs/<slug>/`），归档后作为回归档案。
- `docs/task-checklist/` 只收跨功能 program；单功能任务进 `specs/NNN-xxx/tasks.md`，不要新建单功能清单。

细则以 [docs/principal.md](docs/principal.md) 为准；本文件与它冲突时，先改本文件再动手。
