# specs —— 变更区（Spec Kit）

本目录只放**在途变更**。一个变更一个目录：`NNN-slug/`，编号三位、与分支同名（如 `003-agent-route-picker`）。

```text
specs/003-agent-route-picker/
├── spec.md          # 做什么、为什么、验收标准（success criteria）
├── plan.md          # 怎么做、影响哪些模块、分几步
├── tasks.md         # 可执行任务与依赖（完成后归档）
├── research.md      # 选型对比与否决理由（按需）
├── contracts/       # API / 事件契约（按需）
└── checklists/      # 需求质量自查（按需，不是任务清单）
```

## 流程

命令前缀随集成变化（Spec Kit 1.x 为 `/speckit-*`，旧版为 `/specify` 等），以 `specify init --integration <agent>` 生成的为准。

1. `specify`（建 spec）：只写“做什么、为什么、怎么算完成”，不写实现细节
2. `plan` 写方案：必须写「影响文档」一节（哪些 `docs/` 要同批更新）
3. `tasks` 拆任务：**最后一个阶段固定是「文档沉淀」**（见下）
4. 实现、测试、合并
5. 归档：目录移到 `docs/archive/specs/NNN-slug/`，在 [归档索引](../docs/archive/specs/README.md) 加一行

另外两个入口（按需装扩展）：

- **单点 bug**：不走 SDD，直接修；跨模块/改契约的 bug 用 `specify extension add bug` → `.specify/bugs/<slug>/`（assess → fix → test，归档后当回归档案）
- **可行性评估**：`specify extension add assess` → `.specify/assessments/<slug>/`（go / needs-clarification / kill）；结论再决定要不要进 spec

## 硬规则

- **一个变更一个 spec**；同一块改动必须在新 spec 里写 `supersedes: specs/NNN`，不允许悄悄重做
- **计划不再进 `docs/design/`**：design 只收实施后提炼的决策（ADR）
- **单功能任务不进 `docs/task-checklist/`**：那里只放跨功能 program
- **不把 spec 塞进 `docs/`**：文档站只打包 `docs/` 下五个目录；spec 多了会撑大首屏包体
- 单点 bug（<1 天、无契约变化）不开 spec，直接修 + 补行为测试；跨模块或改契约的 bug 开 spec，归档后当回归档案
- **不再维护长期候选清单**：想法先走 `specify extension add assess`（go / needs-clarification / kill），决定要做再开 spec；历史候选见 `docs/archive/roadmap-2026-11.md`

## 文档沉淀（tasks 的最后一阶段）

合并前必须完成，缺一项视为未完成：

1. 验收标准落成 e2e / 单测断言，测试里带 `spec:NNN` 注释
2. `docs/overview.md` 的「能力面」加一行（只写能力，不写状态、不写 spec id）
3. `docs/guides/` 补使用与配置（`quickstart.md` 内容并进来）
4. 有决策 → `docs/design/` 写 ADR，带 `derived-from: specs/NNN`
5. 目录归档到 `docs/archive/specs/`，归档索引加一行；program 关闭时同步 `task-checklist/readme.md`

## 初始化状态与本机切换

已用 `specify 1.4.3` 初始化：`--integration none`（代理中立，提交进 git）、脚本类型 `ps`、
已有的 `constitution.md` 被保留。各人本机按自己的 agent 切换，**不要提交**：

```bash
specify integration switch codebuddy   # WorkBuddy
specify integration switch trae        # Trae
specify integration switch zcode       # ZCode
specify integration status             # 确认当前生效
```

`constitution` 由 `scripts/sync-constitution.mjs` 从 `docs/principal.md` + `AGENTS.md` 生成，不要手改；
改规则后跑 `node scripts/sync-constitution.mjs`，`--check` 用于 CI。

## 本项目的模板定制

`.specify/templates/` 已按项目约定改过，升级 CLI 或重跑 `specify init` 后要核对：

| 模板 | 定制点 |
| --- | --- |
| `spec-template.md` | 头部增加 `**Supersedes**`，改同一块功能必须链接上一份 spec |
| `plan-template.md` | 增加 `## Documentation Impact *(mandatory)*`，强制列同批要更新的 `docs/` 文件 |
| `tasks-template.md` | 增加 `Phase N+1: 文档沉淀`（验收→测试、能力面、guides、ADR、归档、program 汇总） |

被覆盖时从 git 历史恢复这几个文件；模板是项目资产，不是 CLI 的只读产物。
