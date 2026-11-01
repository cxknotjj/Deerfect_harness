# Skill 功能优化清单

- 范围：`server` 的 Skill 装配链路（`SkillRepository` / `SkillManager` / `SkillMetaToolDecorator` / `PromptAssembler`）
- 创建：2026-11-01
- 状态：14 项待办（P0 4 项、P1 6 项、P2 4 项）；已完成 3 项

## 背景

`skills/<skill>/` 目录式技能经 front-matter 解析后，以「名称 + 描述」索引注入 system prompt；模型通过 `load_skill` 元工具按需取正文，正文按 `tool-result-budget` 截断。当前仓库有两个示例：`skills/systematic-debugging/SKILL.md`、`skills/web-research/web-research.md`。

## 已完成

- [x] 现状梳理与优化点识别（2026-11-01）

## P0 正确性与健壮性

- [ ] P0-1 技能重名检测与确定性去重
  - 位置：[SkillRepository.skillsFor/find](../server/src/main/java/com/dark/javaHarness/prompt/SkillRepository.java#L71-L90)、[SkillManager.LoadSkillCallback](../server/src/main/java/com/dark/javaHarness/prompt/SkillManager.java#L124-L129)
  - 做法：扫描后按 `name.toLowerCase()` 检测冲突，保留文件名排序第一条，其余记 warn 并跳过
  - 验收：同名不同内容的两个文件只返回一条；索引与 `load_skill` 指向同一正文；日志能看出被跳过的文件

- [ ] P0-2 清理 UTF-8 BOM
  - 位置：[SkillRepository.parse](../server/src/main/java/com/dark/javaHarness/prompt/SkillRepository.java#L137-L140)
  - 做法：读取后去掉开头 `\uFEFF`，再判断是否以 `---` 开头
  - 验收：带 BOM 的文件 front-matter 正常解析，`name/description/agents` 均生效；补单测

- [ ] P0-3 缓存指纹与淘汰
  - 位置：[SkillRepository.Cached/load/scan](../server/src/main/java/com/dark/javaHarness/prompt/SkillRepository.java#L55-L134)
  - 做法：缓存指纹由 `mtime` 改为 `mtime + size`；扫描结束清理本次未出现的文件名缓存
  - 验收：复制保留 mtime 的同名文件内容变化能触发热重载；删除/改名后旧缓存被清理；补单测

- [ ] P0-4 `agents` 大小写归一
  - 位置：[SkillRepository.parse/visibleTo](../server/src/main/java/com/dark/javaHarness/prompt/SkillRepository.java#L48-L52)
  - 做法：解析 `agents` 时统一转小写，与 agent 名口径一致
  - 验收：`agents: Researcher` 对 `researcher` 可见；补单测

## P1 性能、可观测性与功能缺口

- [ ] P1-1 请求级技能扫描缓存
  - 位置：[SkillRepository.scan](../server/src/main/java/com/dark/javaHarness/prompt/SkillRepository.java#L93-L112)、[SkillManager](../server/src/main/java/com/dark/javaHarness/prompt/SkillManager.java#L68-L112)、[PromptAssembler](../server/src/main/java/com/dark/javaHarness/prompt/PromptAssembler.java#L129-L140)
  - 做法：增加短 TTL 快照或请求级复用，`provide/skillNames/loadSkillTool` 共用一次扫描结果
  - 验收：单请求目录扫描次数由 3 次降为 1 次；热重载测试仍通过

- [ ] P1-2 技能索引数量/体积上限
  - 位置：[SkillManager.provide](../server/src/main/java/com/dark/javaHarness/prompt/SkillManager.java#L68-L86)
  - 做法：新增 `app.prompt.skills.max-index`（条数或 token 预算），超出时截断并提示
  - 验收：配置小值时索引有截断提示；默认行为不变；补单测

- [ ] P1-3 超长技能分段加载
  - 位置：[SkillManager.doLoad](../server/src/main/java/com/dark/javaHarness/prompt/SkillManager.java#L151-L170)
  - 做法：`load_skill` 增加 `offset`/`section` 参数，或改为按段落检索
  - 验收：超过 `tool-result-budget` 的正文可分多次取完；截断标记仅在仍有余量时出现

- [ ] P1-4 技能实际加载可观测
  - 位置：[SkillManager.doLoad](../server/src/main/java/com/dark/javaHarness/prompt/SkillManager.java#L151-L170)
  - 做法：元工具回调保存 `agentName`；加载时记录 `[skill] load agent/skill/chars/truncated`，并增加 Micrometer 计数
  - 验收：能按 agent/skill 统计实际加载次数与截断次数；不新增 SSE 工具行或 `tool_call_log`

- [ ] P1-5 技能管理/校验接口
  - 位置：新增只读 Controller；[SkillRepository](../server/src/main/java/com/dark/javaHarness/prompt/SkillRepository.java)
  - 做法：`GET /api/skills` 返回解析后的技能与错误列表；启动时做一次全量校验汇总
  - 验收：可查看 `name/description/agents/file/size/error`；重名、BOM、未闭合 front-matter、空正文、超大文件可见

- [ ] P1-6 移除 `load_skill` 的 `toolName` 别名
  - 位置：[SkillManager.extractSkillName](../server/src/main/java/com/dark/javaHarness/prompt/SkillManager.java#L173-L200)
  - 做法：只保留 `skillName` / `skill_name` 与裸字符串兜底，删除 `toolName` / `tool_name`
  - 验收：原别名的单测同步调整，技能名不再被误当工具名接受

## P2 结构与扩展

- [x] P2-1 目录式技能结构（替代顶层平铺）
  - 位置：[SkillRepository.scan/entryFile/load](../server/src/main/java/com/dark/javaHarness/prompt/SkillRepository.java#L103-L159)
  - 做法：每个技能一个子目录，入口优先 `SKILL.md`，其次 `<目录名>.md`；缓存键改相对路径；顶层 `.md` 不再扫描
  - 验收：`systematic-debugging/SKILL.md` 与 `web-research/web-research.md` 均能加载；顶层旧文件与辅助 `.md` 不被当作技能（2026-11-01 已验证）

- [ ] P2-2 front-matter 解析边界
  - 位置：[SkillRepository.parse](../server/src/main/java/com/dark/javaHarness/prompt/SkillRepository.java#L137-L177)
  - 做法：未闭合 front-matter 补 warn；明确当前只支持扁平 `key: value`，需要多行/列表时再引入 YAML
  - 验收：未闭合文件有 warn 且行为与文档一致；补单测

- [ ] P2-3 抽取重复的工具入参/截断逻辑
  - 位置：[SkillManager](../server/src/main/java/com/dark/javaHarness/prompt/SkillManager.java#L173-L215)、[ToolLazyManager](../server/src/main/java/com/dark/javaHarness/prompt/ToolLazyManager.java#L251-L270)、[ToolCallBudget](../server/src/main/java/com/dark/javaHarness/tool/budget/ToolCallBudget.java#L136-L150)
  - 做法：抽取公共 JSON 参数提取与 `truncateToBudget` 工具，收敛三处实现
  - 验收：三处行为保持不变，相关单测通过；重复代码删除

- [ ] P2-4 技能内容信任边界
  - 位置：[SkillManager.doLoad](../server/src/main/java/com/dark/javaHarness/prompt/SkillManager.java#L151-L170)、[SkillRepository.parse](../server/src/main/java/com/dark/javaHarness/prompt/SkillRepository.java#L137-L177)
  - 做法：限制单文件大小；若未来支持上传/外部同步，增加内容校验与来源标记
  - 验收：超大文件在解析前被拒绝并 warn；现有本地文件不受影响

- [ ] P2-5 技能附件可访问
  - 位置：[SkillManager.doLoad](../server/src/main/java/com/dark/javaHarness/prompt/SkillManager.java#L151-L170)
  - 做法：`load_skill` 返回入口正文时附带技能目录内辅助文件清单（或增加读取附件参数），让模型能按需读取技能资源
  - 验收：`systematic-debugging` 的辅助文档可被发现/读取；不改变现有正文截断预算

- [x] P2-6 本地 `spring-boot:run` 工作目录对齐仓库根
  - 位置：[server/pom.xml](../server/pom.xml#L218-L225)
  - 做法：`spring-boot-maven-plugin` 的 `workingDirectory` 设为 `${project.basedir}/..`
  - 验收：本地启动日志显示 skills/knowledge/channel 均从仓库根解析；Docker 运行不受影响（2026-11-01 已验证）
