# 测试对数据库真实数据的依赖：全量审查报告

> 日期：2026-11-14 ｜ 范围：`deerfect-server` 全部 128 个测试类、1136 个用例
> 方式：静态审查 + 全新空库实跑对照（MySQL 8.4，Flyway V1–V24 全量迁移）
> 结论先行：**在全新初始化的空库上跑全量测试，0 个失败**；不存在「必须靠本地库额外数据才能过」的用例。但存在 2 个 P1 风险与 1 处配置漂移，测试目前**发现不了**。

---

## 一、测试与数据库的接触面

| 类别 | 数量 | 是否连库 |
| --- | --- | --- |
| 纯单元测试（Mockito） | 126 类 | 否 |
| `@SpringBootTest` 全上下文 | 1 类 | 是（`JavaHarnessApplicationTests`） |
| `@SpringBootTest + @Transactional` 集成测试 | 1 类 | 是（`MemoryRepositoryIntegrationTest`） |

**1. `JavaHarnessApplicationTests`**（`deerfect-server/src/test/java/com/dark/javaHarness/JavaHarnessApplicationTests.java`）

| 行 | 用例 | 依赖 |
| --- | --- | --- |
| 11 | `@SpringBootTest` | 启动全上下文 → 真实库 + Flyway + 校验器 |
| 18 | `contextLoads` | 库可连、V1–V24 可迁移、路由成员非空、被引用供应商凭证非空 |
| 30 | `gracefulShutdown_isConfigured` | 仅读 Environment（仓库 yaml） |
| 46 | `implicitConfigKeys_areDeclaredInYaml` | 仅读 Environment（仓库 yaml） |
| 60 | `runtimeLimits_areConfigured` | 仅读 Environment（仓库 yaml） |

**2. `MemoryRepositoryIntegrationTest`**（`.../memory/MemoryRepositoryIntegrationTest.java:29-30`）

8 个用例，类级 `@Transactional` 回滚，key 拼随机后缀（`:37`），不依赖库中已有行，也不会污染他人数据。

**其余 126 个测试类**：全部 Mockito，无 DataSource；单元测试里出现的 `4L/5L/11L/12L` 等数字均为 `when(...).thenReturn(...)` 的桩值（如 `llm/admin/LlmAdminServiceTest.java:210,312`），不查库。

---

## 二、逐项核对结论（对应任务列的 6 个排查点）

| # | 排查点 | 结论 | 证据 |
| --- | --- | --- | --- |
| ① | 除初始 agent 外的自建 agent | **不依赖** | 本地库多出 `java-tutor`（手工建），全新库无此行走查全绿；代码只在请求命中时按名查表，无列表断言 |
| ② | 默认工具以外的工具配置 | **不依赖** | 无测试读取 `agent.tools`；`ToolAssignmentsTest` 用内存字符串驱动，不查库 |
| ③ | MCP 配置与连接 | **不依赖本地文件** | `McpToolProviderTest:31` 注释明确避开项目根 `mcp-config.json`，用 `@TempDir`/临时文件；**实测把 `mcp-config.json` 移走后，`JavaHarnessApplicationTests` 仍 4/4 通过** |
| ④ | 硬编码 id / 名称 / 顺序 | **无风险** | 16 处硬编码 id 全是 Mockito 桩；无 `@TestMethodOrder`；surefire 未开并行 |
| ⑤ | 依赖供应商/模型/路由种子 | **仅启动校验依赖"有成员"** | V24 保证空库路由有成员；凭证由 yaml 默认 `${QWEN_API_KEY:dummy-key-for-startup}` 满足，**不带任何 key 也能过**（实测） |
| ⑥ | 测试间共享状态/顺序 | **无** | 无 `@Sql`、无数据初始化脚本、无可变静态字段；集成测试靠事务回滚与随机 key 隔离 |

---

## 三、实跑对照：全新库 vs 本地开发库

```text
全新空库（harness_fresh_test）：24 个迁移 → 1136 用例，Failures 0 / Errors 0 / Skipped 3
本地开发库（harness）        ：同代码同参数 → 同样全绿
```

两者都是绿的，但**数据并不等价**：

| 维度 | 本地开发库 | 全新库（迁移产物） | 说明 |
| --- | --- | --- | --- |
| agent 数 | 16 | 15 | 本地多 `java-tutor`（手工） |
| llm_provider | 3 | 2 | 本地多 1 个供应商（手工） |
| llm_model | 12 | 9 | 本地多 3 个模型部署（手工） |
| `lead.route_id` | 1（main） | 3（judge） | 迁移回填与本地手工改库不一致 |
| `aggregator/analyst/coder.route_id` | 1（main） | 4（multi） | 同上 |
| `general.tools` | 缺 `browser_scroll` | 含 `browser_scroll` | 本地是手工对齐过的旧值 |

**这组差异本身就是问题**：同一份代码，CI/新部署与开发者机器上的路由与工具授权不同；而**没有任何测试断言种子状态**，所以漂移不会变红。根因与任务 12 同源：V20 按硬编码 id 回填 + 本地手工改库，且无"空库契约测试"。

---

## 四、风险清单

### P1（建议尽快处理）

1. **真模型用例用「环境变量存在」当开关**
   `AgentScopeGeneralAgentTest.java:71,92` 的 `@EnabledIfEnvironmentVariable(named="DEEPSEEK_API_KEY", matches=".+")` 会在任何注入了该变量（哪怕是占位值）的环境里发起**真实外网调用**。本次审查中，注入 `DEEPSEEK_API_KEY=dummy` 后立即出现 2 个 401 失败——与数据库无关，却会让人误判为"空库跑不过"。

2. **集成测试连的是开发者当前库**
   没有 `src/test/resources`、没有 test profile、没有 Testcontainers；`application.yaml` 的数据源默认指向本机 `harness`。测试理论上可能读到个人会话/记忆数据，也无法保证 CI 与本地同构。

### P2

3. **种子漂移无守护**：agent 路由、工具授权、供应商/模型数量均无断言，改迁移或手工改库都不会触发失败。
4. **`contextLoads` 隐式依赖 yaml 默认占位 key**：目前 `dummy-key-for-startup` 恰好满足启动校验；若默认值改为空，测试会以"绑定校验失败"的形式红，原因不直观。
5. **3 个跳过与 1 个跳过的口径差异**：不注入 `DEEPSEEK_API_KEY` 时 `AgentScopeGeneralAgentTest` 跳过 2 个 + 另 1 个其他跳过；注入后会真跑。文档若写"1136 全绿"需注明跳过数。

---

## 五、建议（本项不改代码，仅建议）

1. **测试库隔离**：加 `src/test/resources/application-test.yaml`（或 Testcontainers MySQL），CI 与本地都用一次性库；至少把库名固定为 `harness_test`，避免误连开发库。
2. **真模型用例改用标签**：`@Tag("live")` + surefire 默认排除，显式 `-Plive` 才跑；不要用"环境变量存在"做隐式开关。
3. **加一个「空库契约测试」**：全新库启动后断言关键快照（agent 名单、每个 agent 的 route code、general 的工具集合、路由成员数），把第三节的漂移变成红灯。这条与任务 12 的"全新库初始化测试"是同一件事，建议合并做。
4. **种子去硬编码 id**（任务 12）：按 `code`/语义键定位，避免再次出现 V20 那种 `m.id=12` 的空库事故。
5. **文档标注**：开发库允许手工改；CI 与验收以全新库为准。

---

## 六、复现方式

```bash
# 1) 建空库
mysql -uroot -e "DROP DATABASE IF EXISTS harness_fresh_test; CREATE DATABASE harness_fresh_test CHARACTER SET utf8mb4;"

# 2) 指向空库跑全量（不要注入 DEEPSEEK_API_KEY，否则会触发真模型用例）
env -u DEEPSEEK_API_KEY \
  SPRING_DATASOURCE_URL="jdbc:mysql://127.0.0.1:3306/harness_fresh_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true" \
  SPRING_DATASOURCE_USERNAME=root SPRING_DATASOURCE_PASSWORD= \
  DASHSCOPE_API_KEY=ci-placeholder \
  mvn -B -s .mvn/settings.xml -pl deerfect-server -am test

# 3) 验证不依赖本地 mcp 配置
mv mcp-config.json mcp-config.json.bak
mvn ... -Dtest=JavaHarnessApplicationTests   # 4/4 通过
mv mcp-config.json.bak mcp-config.json

# 4) 对比两库种子
mysql -uroot -e "SELECT agent_name, route_id, tools FROM harness.agent ORDER BY agent_name;"
mysql -uroot -e "SELECT agent_name, route_id, tools FROM harness_fresh_test.agent ORDER BY agent_name;"
```
