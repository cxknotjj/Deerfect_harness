# Skill 功能优化清单

- 范围：`server` 的 Skill 装配链路（`SkillRepository` / `SkillManager` / `SkillMetaToolDecorator` / `PromptAssembler`）
- 创建：2026-11-01
- 最近整理：2026-11-02
- 状态：共 17 项，已完成 17 项，待办 0 项

## 进度概览

| 分类 | 已完成 | 待办 | 小计 |
|---|---|---|---|
| 现状梳理 | 1 | 0 | 1 |
| P0 正确性与健壮性 | 4 | 0 | 4 |
| P1 性能、可观测性与功能缺口 | 6 | 0 | 6 |
| P2 结构与扩展 | 6 | 0 | 6 |
| 合计 | 17 | 0 | 17 |

## 背景

`skills/<skill>/` 目录式技能经 front-matter 解析后，以「名称 + 描述」索引注入 system prompt；模型通过 `load_skill` 元工具按需取正文，正文按 `tool-result-budget` 截断。当前仓库有两个示例：`skills/systematic-debugging/SKILL.md`、`skills/web-research/web-research.md`。

## 已完成

- [x] 现状梳理与优化点识别（2026-11-01）
  - 作用：统一识别 Skill 链路的优化点，作为后续执行的唯一任务清单
  - 位置：[skill-optimization.md](./skill-optimization.md)
  - 做法：梳理扫描、解析、索引、load_skill 与观测链路，按 P0/P1/P2 分级
  - 验收：清单落地并明确每项的位置、做法与验收标准

- [x] P2-1 目录式技能结构（替代顶层平铺，2026-11-01）
  - 作用：让技能按目录组织，入口正文与辅助资源分离，为附件读取和多文件技能铺路
  - 位置：[SkillRepository.scan/entryFile/load](../../deerfect-server/src/main/java/com/dark/javaHarness/prompt/SkillRepository.java#L104-L159)
  - 做法：每个技能一个子目录，入口优先 `SKILL.md`，其次 `<目录名>.md`；缓存键改相对路径；顶层 `.md` 不再扫描
  - 验收：`systematic-debugging/SKILL.md` 与 `web-research/web-research.md` 均能加载；顶层旧文件与辅助 `.md` 不被当作技能

- [x] P2-6 本地 `spring-boot:run` 工作目录对齐仓库根（2026-11-01）
  - 作用：让本地运行与 Docker 运行期使用同一套相对路径，避免 skills/knowledge/channel 解析错位
  - 位置：[server/pom.xml](../../deerfect-server/pom.xml#L218-L225)
  - 做法：`spring-boot-maven-plugin` 的 `workingDirectory` 设为 `${project.basedir}/..`
  - 验收：本地启动日志显示 skills/knowledge/channel 均从仓库根解析；Docker 运行不受影响

## P0 正确性与健壮性

- [x] P0-1 技能重名检测与确定性去重（2026-11-02）
  - 作用：避免同名技能在索引与 `load_skill` 之间指向不同正文，保证加载结果可预期
  - 位置：[SkillRepository.skillsFor/find](../../deerfect-server/src/main/java/com/dark/javaHarness/prompt/SkillRepository.java#L82-L101)、[SkillManager.LoadSkillCallback](../../deerfect-server/src/main/java/com/dark/javaHarness/prompt/SkillManager.java#L124-L129)
  - 做法：扫描后按 `name.toLowerCase()` 检测冲突，保留入口路径排序第一条，其余记 warn 并跳过
  - 验收：同名不同内容的两个技能只返回一条；索引与 `load_skill` 指向同一正文；日志能看出被跳过的技能目录

- [x] P0-2 清理 UTF-8 BOM（2026-11-02）
  - 作用：避免 Windows 编辑器保存的 BOM 让 front-matter 整体失效，技能元数据静默丢失
  - 位置：[SkillRepository.parse](../../deerfect-server/src/main/java/com/dark/javaHarness/prompt/SkillRepository.java#L162-L165)
  - 做法：读取后去掉开头 `\uFEFF`，再判断是否以 `---` 开头
  - 验收：带 BOM 的入口文件 front-matter 正常解析，`name/description/agents` 均生效；补单测

- [x] P0-3 缓存指纹与淘汰（2026-11-02）
  - 作用：保证热重载可靠，并避免删除/改名后旧缓存长期残留或旧内容被继续使用
  - 位置：[SkillRepository.Cached/load/scan](../../deerfect-server/src/main/java/com/dark/javaHarness/prompt/SkillRepository.java#L67-L159)
  - 做法：缓存指纹由 `mtime` 改为 `mtime + size`；扫描结束清理本次未出现的入口路径缓存
  - 验收：复制保留 mtime 的同名文件内容变化能触发热重载；删除/改名后旧缓存被清理；补单测

- [x] P0-4 `agents` 大小写归一（2026-11-02）
  - 作用：避免 `agents: Researcher` 这类写法导致技能对目标 agent 不可见
  - 位置：[SkillRepository.parse](../../deerfect-server/src/main/java/com/dark/javaHarness/prompt/SkillRepository.java#L191-L198)、[Skill.visibleTo](../../deerfect-server/src/main/java/com/dark/javaHarness/prompt/SkillRepository.java#L60-L64)
  - 做法：解析 `agents` 时统一转小写，与 agent 名口径一致
  - 验收：`agents: Researcher` 对 `researcher` 可见；补单测

## P1 性能、可观测性与功能缺口

- [x] P1-1 请求级技能扫描缓存（2026-11-02）
  - 作用：降低每请求重复目录扫描的 I/O 开销，避免技能数量增长后拖慢 prompt 装配
  - 位置：[SkillRepository.scan](../../deerfect-server/src/main/java/com/dark/javaHarness/prompt/SkillRepository.java#L104-L127)、[SkillManager](../../deerfect-server/src/main/java/com/dark/javaHarness/prompt/SkillManager.java#L69-L112)、[PromptAssembler](../../deerfect-server/src/main/java/com/dark/javaHarness/prompt/PromptAssembler.java#L129-L140)
  - 做法：增加短 TTL 快照或请求级复用，`provide/skillNames/loadSkillTool` 共用一次扫描结果
  - 验收：单请求目录扫描次数由 3 次降为 1 次；热重载测试仍通过

- [x] P1-2 技能索引数量/体积上限（2026-11-02）
  - 作用：防止技能数量增长后 system prompt 的固定开销失控
  - 位置：[SkillManager.provide](../../deerfect-server/src/main/java/com/dark/javaHarness/prompt/SkillManager.java#L69-L86)
  - 做法：新增 `app.prompt.skills.max-index`（条数或 token 预算），超出时截断并提示
  - 验收：配置小值时索引有截断提示；默认行为不变；补单测

- [x] P1-3 超长技能分段加载（2026-11-02）
  - 作用：让长技能正文可以完整获取，而不是被静默截断后半段
  - 位置：[SkillManager.doLoad](../../deerfect-server/src/main/java/com/dark/javaHarness/prompt/SkillManager.java#L151-L170)
  - 做法：`load_skill` 增加 `offset`/`section` 参数，或改为按段落检索
  - 验收：超过 `tool-result-budget` 的正文可分多次取完；截断标记仅在仍有余量时出现

- [x] P1-4 技能实际加载可观测（2026-11-02）
  - 作用：补上「技能是否真的被加载」的观测，支持使用率与截断率分析
  - 位置：[SkillManager.doLoad](../../deerfect-server/src/main/java/com/dark/javaHarness/prompt/SkillManager.java#L151-L170)
  - 做法：元工具回调保存 `agentName`；加载时记录 `[skill] load agent/skill/chars/truncated`，并增加 Micrometer 计数
  - 验收：能按 agent/skill 统计实际加载次数与截断次数；不新增 SSE 工具行或 `tool_call_log`

- [x] P1-5 技能管理/校验接口（2026-11-02）
  - 作用：提供只读管理入口，便于运维定位解析错误与查看当前技能状态
  - 位置：新增只读 Controller；[SkillRepository](../../deerfect-server/src/main/java/com/dark/javaHarness/prompt/SkillRepository.java)
  - 做法：`GET /api/skills` 返回解析后的技能与错误列表；启动时做一次全量校验汇总
  - 验收：可查看 `name/description/agents/entryFile/size/error`；重名、BOM、未闭合 front-matter、空正文、超大文件可见

- [x] P1-6 移除 `load_skill` 的 `toolName` 别名（2026-11-02）
  - 作用：避免技能名被误当工具名接受，减少模型误调用与语义混淆
  - 位置：[SkillManager.extractSkillName](../../deerfect-server/src/main/java/com/dark/javaHarness/prompt/SkillManager.java#L173-L200)
  - 做法：只保留 `skillName` / `skill_name` 与裸字符串兜底，删除 `toolName` / `tool_name`
  - 验收：原别名的单测同步调整，技能名不再被误当工具名接受

## P2 结构与扩展

- [x] P2-2 front-matter 解析边界（2026-11-02）
  - 作用：明确解析能力边界，并对坏 front-matter 给出可定位的告警
  - 位置：[SkillRepository.parse](../../deerfect-server/src/main/java/com/dark/javaHarness/prompt/SkillRepository.java#L162-L201)
  - 做法：未闭合 front-matter 补 warn；明确当前只支持扁平 `key: value`，需要多行/列表时再引入 YAML
  - 验收：未闭合文件有 warn 且行为与文档一致；补单测

- [x] P2-3 抽取重复的工具入参/截断逻辑（2026-11-02）
  - 作用：收敛重复的入参提取与截断实现，降低三处逻辑漂移风险
  - 位置：[SkillManager](../../deerfect-server/src/main/java/com/dark/javaHarness/prompt/SkillManager.java#L173-L215)、[ToolLazyManager](../../deerfect-server/src/main/java/com/dark/javaHarness/prompt/ToolLazyManager.java#L251-L270)、[ToolCallBudget](../../deerfect-server/src/main/java/com/dark/javaHarness/tool/budget/ToolCallBudget.java#L136-L150)
  - 做法：抽取公共 JSON 参数提取与 `truncateToBudget` 工具，收敛三处实现
  - 验收：三处行为保持不变，相关单测通过；重复代码删除

- [x] P2-4 技能内容信任边界（2026-11-02）
  - 作用：防止超大或不可信技能内容消耗内存、灌爆上下文或引入提示注入
  - 位置：[SkillManager.doLoad](../../deerfect-server/src/main/java/com/dark/javaHarness/prompt/SkillManager.java#L151-L170)、[SkillRepository.parse](../../deerfect-server/src/main/java/com/dark/javaHarness/prompt/SkillRepository.java#L162-L201)
  - 做法：限制单文件大小；若未来支持上传/外部同步，增加内容校验与来源标记
  - 验收：超大文件在解析前被拒绝并 warn；现有本地文件不受影响

- [x] P2-5 技能附件可访问（2026-11-02）
  - 作用：让技能目录中的辅助文档可被发现和读取，发挥目录式结构的价值
  - 位置：[SkillManager.doLoad](../../deerfect-server/src/main/java/com/dark/javaHarness/prompt/SkillManager.java#L151-L170)
  - 做法：`load_skill` 返回入口正文时附带 `<skill>/` 下辅助文件清单，或增加读取附件参数
  - 验收：`systematic-debugging` 的辅助文档可被发现/读取；不改变现有正文截断预算

## 推荐执行顺序

1. **P0-1 / P0-2 / P0-4**：同一轮修改 `SkillRepository` 的扫描与解析，补对应单测，风险最低。
2. **P0-3 / P1-1**：一起治理缓存指纹与每请求重复扫描，避免后续性能问题。
3. **P1-4 / P1-5**：先补实际加载可观测性，再加只读管理/校验接口。
4. **P1-2 / P1-3 / P1-6 / P2-2 / P2-3 / P2-4 / P2-5**：按技能数量、正文长度和附件使用情况逐步推进，不必一次性完成。
