# 项目约束（Principal）

> 本文件只写**约束**，不解释原理。原理见 [architecture.md](./architecture.md)、[data-flow.md](./data-flow.md)；目录树见 [project-structure.md](./project-structure.md)；环境与命令见 [dev.md](./dev.md)。
>
> 判定标准：每条都应能一眼判断是否被违反；不可判定的描述不进本文件。
>
> 代码现状与本文件冲突时，视为**代码待修**，而不是本文件作废。

## 1. 代码精简

- 行数只作排查信号，不作验收指标：单文件超过约 300 行、单方法超过约 50 行时，必须在提交说明或类注释中说明职责边界与不拆理由。
- 新增抽象（接口、基类、工具类、装饰器）前，必须已有 ≥3 处真实复用点；只有一处调用时直接内联。
- 新增第三方依赖前，先说明为什么不能手写（给出替代实现的大致规模）。
- 不为「将来可能用到」预留参数、泛型、开关。
- 注释只写「为什么」，不复述「做了什么」。

## 2. 防御性兜底只在边界

- `try/catch` 与空值校验只允许出现在系统边界：
  - HTTP 入参与请求体（`validation` 包）
  - 外部系统返回：模型调用、MCP 工具、网页抓取、DB 中的外部数据
  - 反序列化与异步任务入口
- 内部调用之间不写兜底：信任类型系统、框架保证与上游校验结果。逻辑层出现 `if (x == null) return null;` 这类静默兜底，视为 bug。
- 校验规则必须是纯函数：无 Spring 上下文、无 Mapper、无 IO；失败即抛出带明确信息的异常。

## 3. 分层与依赖方向

- `controller`：只做参数接收、调用 service、组装响应；不写业务分支、不直连 Mapper。
- `service` 编排逻辑；`mapper` / `domain.entity` 只做数据读写与映射。
- 运行时包依赖单向：
  - `graph` → `agent` / `state`
  - `agent` 不得 import `graph`（共用常量下沉到 `agent` 包，如 `SubtaskResults`）
  - `validation` 不依赖任何运行时包；观测表只允许经 `observability` 查询服务访问
- core（`server`）编译期不得依赖 `channel-qq`（由 [server/pom.xml](../server/pom.xml) 的 `maven-enforcer-plugin` 守护）；渠道新增输出控制走 `harness-api` 的 `ReplyPostProcessor`，不改 core。

## 4. 重构期不留兼容层

- 旧构造器、旧重载、旧接口、旧字段、旧开关直接删除；不做旧版本兼容，不写 deprecated 桥接。
- 结构调整后与新结构不符的旧测试，直接删除或重写，不为「保持测试通过」保留兼容代码。
- 移动/重命名包时，同一次提交内更新文档中的路径引用（`project-structure.md` 等）。

## 5. 测试

- 以行为、契约与集成测试为主；不为私有方法或实现细节写单测。
- 修 bug 必须补一条能复现该 bug 的行为级测试，并在提交说明中指向它。
- 重构允许删除过时测试，但覆盖面不得下降：删除前确认同等行为已被其它测试覆盖。
- 验证命令统一 `mvn -s .mvn/settings.xml -pl <module> -am test`；跨模块或 SQL 变更需全量 `install` 通过后才能声明完成。

## 6. bug 修复

- 默认路径是根治：先定位根因（调用链、数据来源、契约），再改结构或契约，不叠加临时判断。
- 逃生阀：仅当根因在上游（第三方库缺陷、外部协议怪癖、平台限制）且根治成本明显高于收益时，允许最小补丁，且必须同时满足：注释写明根因与补丁理由、留 issue 或文档链接、提交说明标注「补丁」。
- 禁止「加个 if 先让测试过」式修复；禁止为绕过单个异常而静默吞掉异常。

## 7. 单一事实来源

- 上下文预算数值只在 yaml（`app.context.*`）维护，代码里不写第二套数字。
- token 估算统一用 `TokenEstimator`；新增估算逻辑须说明与现有口径的差异。
- 工具装饰器只在 `DefaultToolDecorators` 登记（新装饰器 = 新类 + 此处一行）。
- 数据库变更只新增 Flyway 迁移（当前基线 V1，2026-10-30 压平；之后从 V2 编号），已发布迁移不改，不手工在生产库执行 DDL。

## 8. 变更纪律

- 未经用户明确要求，不执行 `git commit` / `git push`（规范见 [commit-convention.md](./guides/commit-convention.md)；本机细则另见 `.trae/rules/git-workflow.md`）。
- 不提交 `.mvn-repo/`、`target/`、`.idea/`、`.vscode/`、`.trae/` 与本地密钥文件。
- 结构与接口变化必须同一次提交更新对应文档；文档只做必要的表述更新，不重写历史记录。
