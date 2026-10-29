# Tavily Web Search（MCP hosted）接入 Spec

## Why

HARNESS_TODO P1「Web Search 接入」条目：researcher/general 缺「调研」第一步的搜索能力。用户决策走 **MCP hosted** 方式接入 Tavily 远程 MCP（`https://mcp.tavily.com/mcp/`，Streamable HTTP，匹配项目既有 `HttpClientStreamableHttpTransport`）——零 Java 工具代码，同时让 `mcp-config.json` 迎来第一个真实外部 server。范围取舍（用户明确）：**key 直写配置文件、文件转本地不入库；不做占位符机制、不改 legacy 白名单兜底**——工具分配走既有的数据路径（agent 表 `tools` 列），一条路够用。

## What Changes

- **mcp-config.json 新增 tavily 条目**（key 直写 `url`），文件转为本地配置：`git rm --cached` + 加入 `.gitignore`，防止 key 入库；README 提供无 key 模板
- **工具分配走既有数据路径**：agent 表 `tools` 列（追加、不覆盖既有声明）加 `tavily-search`，改库免重启，零 Java 改动；`MCP_TOOL_WHITELIST` / `TOOL_PURPOSES` / `McpConfigParser` 一律不动
- **文档**：README（mcp-config.json 模板与不入库说明、tools 列分配 SQL）；docs/HARNESS_TODO.md L71-72 补进度说明（条目保持未勾选直至端到端验收达成）
- **不改动**：McpToolProvider 连接生命周期（复用既有后台预热/懒连接/按 server 失败隔离/`mcp_server_log` 观测）、种子迁移
- **计划外新增（e2e 验证暴露的两坑，已修）**：
  1. `McpToolProvider.httpTransport` 拆分带 query 的 url（origin baseUri + 「路径?query」endpoint）——SDK 每次请求 `baseUri.resolve(endpoint)`（endpoint 默认 "/mcp"）会按 RFC 解析丢弃 base 的 query，`?tavilyApiKey=` 从未发出 → 401 → 隔离成 0 工具；round-trip 不变量由单测锁定，无 query url 走原行为零回归
  2. agent 表声明的工具名以 MCP `tools/list` 真实注册名为准——Tavily 实际是下划线 `tavily_search`（非官方文档宣传的连字符 `tavily-search`）

## Impact

- 受影响：`mcp-config.json`（转本地）、`.gitignore`、`README.md`、`docs/HARNESS_TODO.md`、（运行库）agent 表 `tools` 列
- 已知限制（接受，不立项）：加载日志会回显含 key 的 url（仅本地日志文件，均已 gitignore / 落 /tmp）；Tavily 返回内容不经项目裁剪链，靠既有 ToolCallBudget 硬截断兜底
- 验收口径（对齐 TODO）：researcher 对「近期事件」类问题能返回带来源的检索结果

## ADDED Requirements

### Requirement: Tavily 远程 server 接入（mcp-config.json）

mcp-config.json SHALL 提供 tavily 条目（`url: https://mcp.tavily.com/mcp/?tavilyApiKey=<key>`，Streamable HTTP），复用既有预热/懒连接/失败隔离/观测链路。

#### Scenario: 有效 key 正常接入

- **WHEN** 应用（重）启动且 key 有效
- **THEN** 后台预热连上 server 并注册 tavily-* 工具，日志显示「后台预热完成 server 'tavily'：N 个工具」，`mcp_server_log` 记 CONNECTED

#### Scenario: 连接失败隔离

- **WHEN** key 无效或网络不通
- **THEN** 按 server 失败隔离：该 server 工具面为空 + warn，主链路零感知（既有语义，无需新代码，验证即可）

#### Scenario: 配置文件不入库

- **WHEN** mcp-config.json 含明文 key
- **THEN** 该文件不在 git 跟踪中（.gitignore + rm --cached），README 提供无 key 的条目模板供他人复用

### Requirement: 工具分配（数据路径）

模型侧可见性 SHALL 通过 agent 表 `tools` 列精确工具名声明（既有机制，`resolveDeclared` 已支持 MCP callback 查找）分配 `tavily-search`。

#### Scenario: researcher/general 可用 search

- **WHEN** general / researcher 的 `tools` 列含 `tavily-search` 且 server 已连接
- **THEN** 该 agent 工具面含 `tavily-search`；`tavily-extract` / `tavily-map` / `tavily-crawl` 未声明不可见

#### Scenario: server 未连接时声明降级

- **WHEN** `tools` 列声明了 `tavily-search` 但 server 连接失败
- **THEN** 分配解析按既有语义将该 token 记 unknown warn 跳过，不影响其余工具分配

## MODIFIED Requirements

（无）

## REMOVED Requirements

### Requirement: 环境变量占位符展开（初版 spec 提出）

**Reason**：用户决策精简——key 直写本地配置文件，无需解析器改造。
**Migration**：无（未实现过）。

### Requirement: legacy 白名单兜底扩展（初版 spec 提出）

**Reason**：用户决策——数据路径一条路够用，不动 `MCP_TOOL_WHITELIST`。
**Migration**：无（未实现过）。
