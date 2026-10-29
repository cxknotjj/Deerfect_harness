# Spec: 工具分配最小权限化——general 收敛为只读探索者

## Why

`general` 全量过宽且是所有回退路径的落点：路由兜底、未识别专家、lead 漏指派全部落
general——最宽权限（执行/容器写/网页/浏览器）给了最不可控的场景，违背最小权限原则。
沙箱语境下工具已原生分执行/只读文件/写入三类，无需再拆类。

## What Changes

1. **legacy switch general 分支收敛**（[ToolAssignments.java](file:///home/wsl/development/java-harness/src/main/java/com/dark/javaHarness/tool/ToolAssignments.java)）：
   `webTools + sandbox.read + sandbox.browser + mcpTools()`（与 researcher 面一致）——
   去掉 `sandbox.base`（执行）与 `sandbox.write`（写入）。
   - MCP 白名单（browser_click 等页面交互类）保留：性质是浏览器只读探索的推进动作
   - WebTools 整组注入不变（fetchUrl + getCurrentTime，均只读）
2. **生产库 tools 列对齐**：数据驱动优先于 legacy，生产库 general 行 tools 列非空
   （README 曾教过 `CONCAT(tools, ', tavily_search')`），必须同步收敛才真正生效。
   - 提供 UPDATE 语句写入 README 工具分配节（**不做迁移脚本**——沿用「种子迁移链
     不波及真实库既有数据」决策，改库由用户手工执行，改库即生效免重启）
   - README 分配表如有 general 行同步更正
3. **测试同步**（[ToolAssignmentsTest.java](file:///home/wsl/development/java-harness/src/test/java/com/dark/javaHarness/tool/ToolAssignmentsTest.java)）：
   - `general_getsFullToolset` → 改为只读面断言：callbacks 9→6（read 2 + browser 3 +
     MCP 0 重名）+ webTools + `verify(never) baseTools/writeTools`
   - `duplicateNames`：general 10→6（重算：6 沙箱 + 3 MCP − hover − click 重名）
   - `declaredTools_blankOrMissing_fallsBackToLegacy`：general 9→6
   - researcher/coder/analyst/未登记用例零改动
4. **HARNESS_TODO L56-62 勾选**，注明验证方式。

## 不改的东西

- coder（base+write）/ analyst（base+read）/ researcher 面零变化
- 分配机制零变化（组名/精确名跨目录查找/同名去重/MCP 白名单语义原样）
- coder 验收闭环不变：读文件走 `run_shell_command`（cat）→ 改走 write 组 → 跑命令走执行类

## ADDED Requirements

### Requirement: general 只读面
general（含所有回退落点）SHALL 不持有执行类（sandbox.base）与写入类（sandbox.write）工具；
数据驱动与 legacy 两级分配 SHALL 同时收敛。

#### Scenario: legacy 面无执行无写入
- **WHEN** `forAgent("general")`
- **THEN** callbacks 仅含 read/browser/MCP 白名单子集；verify baseTools/writeTools 未被调用

#### Scenario: 未指派子任务继承只读面
- **WHEN** lead 未指派专家，子任务回退 general 兜底身份
- **THEN** 该子任务经 `forAgent("general")` 获得只读面，无执行与写入工具

### Requirement: coder 闭环保持
coder SHALL 保持「读文件（shell cat）→ 改文件（write 组）→ 跑命令（base 组）」闭环能力。

#### Scenario: coder 面不变
- **WHEN** `forAgent("coder")`
- **THEN** 分配结果与改造前一致（base + write）

## 验收

- [x] ToolAssignmentsTest 全绿 13 例（general 面断言更新 5 callbacks + verify never base/write，其余用例零改动）
- [x] 全量 `mvn test` 回归绿（488 例，0 失败 0 错误，1 skip 既有基线）
- [x] README「MCP 工具接入」节含生产库 general 收敛 UPDATE 语句
- [x] HARNESS_TODO L56 勾选

## 实施记录（2026-09-14）

- [ToolAssignments.java](file:///home/wsl/development/java-harness/src/main/java/com/dark/javaHarness/tool/ToolAssignments.java) legacy general 分支：`base+read+write+browser+MCP` → `read+browser+MCP`
- 测试同步遇 IDE 脏缓冲交错损坏（部分 Edit 被回滚、部分保留），按既定根治流程完整重写 + 同目录暂存 + `mv` 原子替换 + grep 校验
- 断言重算更正：general 只读面 = 5（read 2 + browser 3 + MCP 0 重名），duplicateNames 场景 = 6（spec 初稿误写 6/7 已修正）
