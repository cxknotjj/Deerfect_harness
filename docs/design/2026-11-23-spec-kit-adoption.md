# 引入 Spec Kit：变更区与知识区分离

> 状态：已实施（2026-11-23）　|　类型：决策　|　影响：`AGENTS.md`、`docs/design/`、`docs/task-checklist/`、新增 `specs/`
>
> 结论先行：**Spec Kit 只做“变更流水线”，`docs/` 只做“长期知识”**。计划与任务全部进 `specs/NNN/`，
> 实施后蒸馏进 `docs/` 并归档；`design/` 退化为 ADR，`task-checklist/` 退化为 program 看板，两者都不再生产计划。

## 背景

引入前仓库里有四套并行的“计划”：

| 位置 | 装什么 | 问题 |
| --- | --- | --- |
| `docs/design/` 的计划类 | 4 份已实施计划 + 1 份跨版本总案 | 与 Spec Kit 的 `plan.md` 完全重叠 |
| `docs/task-checklist/` | 12 份清单（7 份已完成） | 与 `tasks.md` 重叠；完成的还留在活跃区 |
| `.trae/documents/` | 2 份 IDE 侧开发计划 | 不在版本控制内，外人看不到 |
| （拟引入）`specs/` | Spec Kit 产物 | 不治理就会变成第五套 |

结果是同一件事写三遍，且状态表三处维护。

## 决策

### 1. 四个区，各管一段生命周期

```text
宪法区  docs/principal.md + AGENTS.md ──生成──> .specify/memory/constitution.md
变更区  specs/NNN-slug/{spec,plan,tasks,research,contracts}
沉淀区  docs/overview.md（能力面）· guides/* · data-flow.md · design/*（仅 ADR）
归档区  docs/archive/specs/NNN-slug/（复用既有 30 份旧 spec 的位置）
看板区  docs/task-checklist/（仅跨功能 program）
```

### 2. 产物归属

| Spec Kit 产物 | 唯一归属 | 不再写进 |
| --- | --- | --- |
| `spec.md` | `specs/NNN/`，上线后能力进 `overview.md` | `design/`、已删除的 `features.md` |
| `plan.md` | `specs/NNN/`，实施后只提炼决策进 ADR | `design/*-plan.md` |
| `tasks.md` | `specs/NNN/`，完成后随 spec 归档 | 单功能 `task-checklist/*.md` |
| `research.md` | 随 spec 归档；可复用结论进 ADR | `design/` 的评估正文 |
| `contracts/` | 对外契约回写 `guides/api.md` / `sse-protocol.md` | — |
| `quickstart.md` | 并入 `guides/` | — |
| `checklists/requirements.md` | 转成测试断言 | 任务清单（它不是任务清单） |

### 3. 存量处置（已执行）

- 4 份已实施计划 + 2 份 IDE 计划 → `docs/archive/plans/`；design 20 → 16
- 7 份已完成清单 → `docs/archive/checklists/`；task-checklist 13 → 6
- `.trae/documents/` 停用（2 份转档、1 个空文件删除）

### 4. constitution 由源文件生成

`scripts/sync-constitution.mjs` 从 `docs/principal.md` 的 8 条约束 + `AGENTS.md` 硬规则生成
`.specify/memory/constitution.md`，文件头标注“生成文件，勿手改”。`--check` 用于 CI 防漂移；
`constitution` 命令不再人工维护，改规则永远走源文件。

## 后果

**收益**

- 一个变更只有一个入口（spec），一套任务（tasks），一处状态（specs/ + 归档索引）
- 活跃文档从 33 降到 22，且不再随功能增长
- 决策仍有可检索的索引，不会埋在归档 spec 里

**代价**

- 文档站读不到 `specs/`（不在打包范围），查历史要 IDE / grep；`docs/archive/specs/README.md` 提供目录
- 跨版本 program 仍需要人工汇总（`task-checklist/readme.md`）
- Spec Kit 命令依赖 `.specify/` 初始化；未装 CLI 前只有约定，没有自动化

## 替代方案

| 方案 | 为什么没选 |
| --- | --- |
| 保留双体系（design 计划 + specs） | 同一条信息写两遍，状态必然漂移 |
| 彻底删除 design / checklist，只留 specs | 决策无法按主题检索；跨功能 program 失去看板；文档站没有“为什么”的入口 |
| 把 specs 放进 `docs/` | 文档站只打包五个目录，spec 会撑大首屏包体并污染导航 |

## 关联

- 变更流程与命令：[specs/README.md](../../specs/README.md)
- 归档索引：[archive/specs/README.md](../archive/specs/README.md)
- 硬规则：[AGENTS.md](../../AGENTS.md) 的「变更流程」
