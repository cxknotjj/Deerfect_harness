<!-- GENERATED FILE — DO NOT EDIT -->
<!-- Source: docs/principal.md（1-8）+ AGENTS.md（硬规则） -->
<!-- Regenerate: node scripts/sync-constitution.mjs -->
<!-- Verify:     node scripts/sync-constitution.mjs --check -->

# javaHarness Constitution

> 本文件由脚本生成，**不要手改**。改规则请改 `docs/principal.md` 或 `AGENTS.md`，然后重跑
> `node scripts/sync-constitution.mjs`；CI 用 `--check` 阻止漂移。

## Core Principles

### 1. 代码精简

- 行数只作排查信号，不作验收指标：单文件超过约 300 行、单方法超过约 50 行时，必须在提交说明或类注释中说明职责边界与不拆理由。
- 新增抽象（接口、基类、工具类、装饰器）前，必须已有 ≥3 处真实复用点；只有一处调用时直接内联。
- 新增第三方依赖前，先说明为什么不能手写（给出替代实现的大致规模）。
- 不为「将来可能用到」预留参数、泛型、开关。
- 注释只写「为什么」，不复述「做了什么」。

### 2. 防御性兜底只在边界

- `try/catch` 与空值校验只允许出现在系统边界：
  - HTTP 入参与请求体（`validation` 包）
  - 外部系统返回：模型调用、MCP 工具、网页抓取、DB 中的外部数据
  - 反序列化与异步任务入口
- 内部调用之间不写兜底：信任类型系统、框架保证与上游校验结果。逻辑层出现 `if (x == null) return null;` 这类静默兜底，视为 bug。
- 校验规则必须是纯函数：无 Spring 上下文、无 Mapper、无 IO；失败即抛出带明确信息的异常。

### 3. 分层与依赖方向

- `controller`：只做参数接收、调用 service、组装响应；不写业务分支、不直连 Mapper。
- `service` 编排逻辑；`mapper` / `domain.entity` 只做数据读写与映射。
- 运行时包依赖单向：
  - `graph` → `agent` / `state`
  - `agent` 不得 import `graph`（共用常量下沉到 `agent` 包，如 `SubtaskResults`）
  - `validation` 不依赖任何运行时包；观测表只允许经 `observability` 查询服务访问
- core（`deerfect-server`）编译期不得依赖 `channel-qq`（由 [deerfect-server/pom.xml](../../deerfect-server/pom.xml) 的 `maven-enforcer-plugin` 守护）；渠道新增输出控制走 `deerfect-api` 的 `ReplyPostProcessor`，不改 core。

### 4. 重构期不留兼容层

- 旧构造器、旧重载、旧接口、旧字段、旧开关直接删除；不做旧版本兼容，不写 deprecated 桥接。
- 结构调整后与新结构不符的旧测试，直接删除或重写，不为「保持测试通过」保留兼容代码。
- 移动/重命名包时，同一次提交内更新文档中的路径引用（`project-structure.md` 等）。

### 5. 测试

- 以行为、契约与集成测试为主；不为私有方法或实现细节写单测。
- 修 bug 必须补一条能复现该 bug 的行为级测试，并在提交说明中指向它。
- 重构允许删除过时测试，但覆盖面不得下降：删除前确认同等行为已被其它测试覆盖。
- 验证命令统一 `mvn -s .mvn/settings.xml -pl <module> -am test`；跨模块或 SQL 变更需全量 `install` 通过后才能声明完成。

### 6. bug 修复

- 默认路径是根治：先定位根因（调用链、数据来源、契约），再改结构或契约，不叠加临时判断。
- 逃生阀：仅当根因在上游（第三方库缺陷、外部协议怪癖、平台限制）且根治成本明显高于收益时，允许最小补丁，且必须同时满足：注释写明根因与补丁理由、留 issue 或文档链接、提交说明标注「补丁」。
- 禁止「加个 if 先让测试过」式修复；禁止为绕过单个异常而静默吞掉异常。

### 7. 单一事实来源

- 上下文预算数值只在 yaml（`app.context.*`）维护，代码里不写第二套数字。
- token 估算统一用 `TokenEstimator`；新增估算逻辑须说明与现有口径的差异。
- 工具装饰器只在 `DefaultToolDecorators` 登记（新装饰器 = 新类 + 此处一行）。
- 数据库变更只新增 Flyway 迁移（**编号取 `db/migration` 目录当前最大版本 +1，不在文档里写死当前版本**），已发布迁移不改，不手工在生产库执行 DDL；迁移若被 `@SpringBootTest` 提前应用到本地库，改脚本后须按 `flyway repair` 校正 checksum 再启动。

### 8. 变更纪律

- 未经用户明确要求，不执行 `git commit` / `git push`（规范见 [commit-convention.md](../../../docs/guides/commit-convention.md)；本机细则另见 `.trae/rules/git-workflow.md`）。
- 不提交 `.mvn-repo/`、`target/`、`.idea/`、`.vscode/`、`.trae/` 与本地密钥文件。
- 结构与接口变化必须同一次提交更新对应文档；文档只做必要的表述更新，不重写历史记录。

## Additional Constraints

- Git 提交：功能完成且按项目规定跑完测试并通过后，可直接 commit 该功能的代码、测试与同批必需文档（细则见 [.trae/rules/git-workflow.md](../../.trae/rules/git-workflow.md)）；`git push` 仍需用户明确要求，禁止擅自推送。
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

## Governance

- 本文件与 `docs/principal.md`、`AGENTS.md` 冲突时，以源文件为准，并重跑生成脚本。
- 修订流程：先改源文件 → 重跑脚本 → 提交生成物；三者必须在同一次提交里。
- 不单独维护版本号：规则随源文件变更而更新，历史见 git。
