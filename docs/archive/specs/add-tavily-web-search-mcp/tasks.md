# Tasks

- [x] Task 1: mcp-config.json 接入条目与不入库
  - [x] 1.1 `git rm --cached mcp-config.json` + `.gitignore` 加 `mcp-config.json`（文件保留在本地工作区）
  - [x] 1.2 mcp-config.json 写入 tavily 条目：`"tavily": { "url": "https://mcp.tavily.com/mcp/?tavilyApiKey=<真实key，本地文件不提交>" }`
  - [x] 1.3 README 补无 key 条目模板与「该文件不入库、需自建」说明
- [x] Task 2: 工具分配（数据路径，改库免重启，依赖 Task 1）
  - [x] 2.1 先 `SELECT agent_name, tools FROM agent WHERE agent_name IN ('general','researcher')` 查现值，再追加（不覆盖既有声明）`tavily-search`；SQL 样例沉淀进 README
- [x] Task 3: 文档更新（依赖 Task 1、2 定稿）
  - [x] 3.1 README：MCP 接入说明（mcp-config.json 模板 / 不入库 / Streamable HTTP）；tools 列分配 tavily-search 的 SQL；已知限制一句（结果不经裁剪链，预算硬截断兜底）
  - [x] 3.2 docs/HARNESS_TODO.md L71-72 补进度说明（MCP hosted 链路已接通、分配经 tools 列、验收状态），条目保持未勾选直至端到端验收达成
- [x] Task 4: 端到端验证（依赖 Task 1–3）
  - [x] 4.1 重启服务：日志确认「后台预热完成 server 'tavily'：N 个工具」且 mcp_server_log 记 CONNECTED（实测：注册 5 个工具，CONNECTED tools=5）
  - [x] 4.2 向 researcher 提「近期事件」类问题：回答含带来源的检索结果；tool_call_log 能看到 tavily_search 调用记录（2026-09-12 20:07 复验通过：POST /api/chat agentId=6 返回 agent=researcher、status=SUCCEEDED、内容为 9月8-12日实时新闻带来源链接；tool_call_log #31 首次调用 ERROR 10s 超时 → #32 重试 OK 2986ms，server=tavily；另证实初始化后 GET SSE 收 405 为 Tavily 不支持服务端推送的规范合规行为，不影响 POST 工具链路）
  - [x] 4.3 `mvn -s .mvn/settings.xml test` 全绿（446 用例 + 新增 3 个 splitEndpointUrl 用例）
- [x] Task 5: 修复 SDK baseUri.resolve 丢 query（e2e 发现，超出原「不改 Java」约束）
  - [x] 5.1 根因定位：SDK 每次请求做 `baseUri.resolve(endpoint)`（endpoint 默认 "/mcp"，绝对路径引用）——RFC 解析丢弃 base 的 query，`?tavilyApiKey=` 从未发出 → Tavily 401 → initialize 失败隔离成 0 工具（jshell 验证 resolve 结果 + curl 状态码矩阵）
  - [x] 5.2 McpToolProvider.httpTransport 拆分：带 query 的 url → origin baseUri + 「路径?query」endpoint 绝对引用（resolve 完整还原）；无 query 走原行为零回归
  - [x] 5.3 单测锁定 round-trip 不变量（McpToolProviderTest +3 用例，17/17 绿）
- [x] Task 6: 工具名修正（e2e 发现）
  - [x] 6.1 Tavily 真实注册名为下划线（tavily_search/extract/crawl/map/research，MCP tools/list 实测），agent 表 `tavily-search` → `tavily_search`（REPLACE 更新 2 行）
  - [x] 6.2 README / HARNESS_TODO 进度行同步改名

# Task Dependencies

- Task 2 依赖 Task 1（server 条目先就位，分配才有意义）
- Task 3 依赖 Task 1、2（文档描述最终形态）
- Task 4 依赖 Task 1–3（全链路就绪后验收）
- Task 5、6 由 Task 4 端到端验证暴露，修复后 4.2 需复验
