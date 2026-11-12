# AGENTS.md

本仓库是 Maven 多模块 Java 项目（Spring Boot 4 + Spring AI + AgentScope），核心是 `deerfect-server` 模块。

## 动手前必读

- 改代码前：读 [docs/principal.md](docs/principal.md)（架构与修复原则）
- 跑项目/测试前：读 [docs/dev.md](docs/dev.md)（本地工具、端口、已知坑）
- 目录与模块边界：见 [docs/project-structure.md](docs/project-structure.md)

## 硬规则

- Git 提交：功能完成且按项目规定跑完测试并通过后，可直接 commit 该功能的代码、测试与同批必需文档（细则见 [.trae/rules/git-workflow.md](.trae/rules/git-workflow.md)）；`git push` 仍需用户明确要求，禁止擅自推送。
- Maven 命令必须带 `-s .mvn/settings.xml`；单模块构建/测试必须带 `-am`。
- 重构期不留兼容层：旧构造器、旧重载、旧接口直接删，不做旧版本兼容。
- 逻辑层不做防御性兜底，兜底与校验只放在系统边界（HTTP 入参、外部 API/MCP/模型返回、反序列化）。
- 修 bug 优先结构化根治；只有上游缺陷且根治成本过高时才允许最小补丁，并注释根因。
- 数据库变更只新增 Flyway 迁移（2026-10-30 由 V1~V43 压平为 V1 基线；），不改已发布的迁移文件；迁移若被 `@SpringBootTest` 提前应用到本地库，改脚本后须按 `flyway repair` 校正 checksum 再启动。
- 不提交 `.mvn-repo/`、`target/`、`.idea/`、`.vscode/`、`.trae/` 与本地密钥文件。
- 在修改dev.md新增知识之前，确保知识是可以复用的。
- 修改新增文档时，不要写死锚点。

细则以 [docs/principal.md](docs/principal.md) 为准；本文件与它冲突时，先改本文件再动手。
