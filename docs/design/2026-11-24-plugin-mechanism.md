# 插件机制与长期记忆扩展点（ADR，2026-11-24）

> derived-from: `specs/001-plugin-mem0-memory`
> 状态：已实施

## 背景

长期记忆需要一个可关闭、可替换的外部记忆服务；直接把 mem0 客户端写进对话主流程会让
`PromptAssembler`、`TurnPersistence` 依赖具体厂商，后续换服务或增加第二记忆源都要改主链路。

## 决策

1. 新增最小插件机制：
   - `Plugin`（`name` / `capability` / `enabled`）+ `PluginCapability`（首期仅 `MEMORY`）
   - `PluginRegistry` 收集启用的 Spring Bean；同一扩展点多实现时**启动失败**，不静默取第一个
2. 长期记忆扩展点 `MemoryPlugin`：`search(MemoryQuery)` / `remember(MemoryTurn)`
3. 第一个实现 `mem0`（自托管 OSS REST）：配置驱动启停，客户端、注入段、写入投递全部条件装配
4. 注入与写入只走既有扩展位置：
   - 读取：`PromptSection` order 145（显式记忆 140 与画像 150 之间），`Context` 增加 `userMessage`
   - 写入：`TurnPersistence.writeBackBoth` 唯一汇合点 → `MemoryWriteDispatcher` 异步投递
5. 故障边界：HTTP 异常只在 `Mem0Client` 捕获；异步任务入口捕获所有异常；逻辑层不写空值兜底
6. 零数据库迁移：mem0 自存数据；观测走结构化日志 + Micrometer

## 备选方案与否决理由

| 方案 | 否决理由 |
| --- | --- |
| 直接在 `PromptAssembler` 注入 `Mem0Client` | 主流程依赖具体厂商，无法满足「可关闭/可替换」，后续加记忆源要改核心类 |
| `ServiceLoader` / PF4J 动态热插拔 | 单机单体部署无收益，增加类加载与生命周期复杂度 |
| 复用 `ExplicitMemorySectionProvider` provider 列表 | 该列表语义是「首个非空即返回」，新实现会被静默遮蔽 |
| 在 `agentService.execute*` 层挂写入钩子 | COMPLEX 降级重答会重复写；写回有 sync/stream 两条路径，必须收敛到 `writeBackBoth` |

## 后果

- 新增记忆实现（如 mem0 Platform）只需新增一个 `MemoryPlugin` Bean，主流程与文档骨架不变。
- 插件启用时启动期强校验配置，配置错误不会拖到运行期才暴露。
- 代价是引入一层接口与注册表（首期只有一个实现），复杂度记录在
  `specs/001-plugin-mem0-memory/plan.md` 的 Complexity Tracking。
