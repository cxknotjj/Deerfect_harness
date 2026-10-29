# Checklist

- [x] mcp-config.json 含 tavily 条目（Streamable HTTP url）且已转本地不入库（git 不再跟踪、.gitignore 生效、本地文件保留）
- [x] README 提供无 key 的 mcp-config.json 条目模板与自建说明
- [x] 有效 key（重）启动后：日志显示「后台预热完成 server 'tavily'：5 个工具」，mcp_server_log 记 CONNECTED tools=5
- [x] 连接失败按 server 隔离：修复前的 FAILED 记录（含隔离期）主链路零感知，应用正常服务
- [x] general / researcher 的 agent 表 tools 列追加 tavily_search（真实注册名为下划线，MCP tools/list 实测；不覆盖既有声明），tavily_extract / tavily_map / tavily_crawl / tavily_research 未声明不可见
- [x] 工具名修正后分配成功：请求日志的 unknown token 名单不再含 tavily_search（只剩 Docker 缺失导致的 browser 4 项既有降级）
- [x] SDK baseUri.resolve 丢 query 修复：带 query url 拆 origin + endpoint（round-trip 单测锁定），无 query 走原行为零回归
- [x] README 与 docs/HARNESS_TODO.md 更新到位（TODO 条目保持未勾选直至验收达成）
- [x] `mvn -s .mvn/settings.xml test` 全绿（446 用例回归 + 新增 3 个 splitEndpointUrl 用例 17/17）
- [x] 端到端验收：researcher 对「近期事件」类问题返回带来源的检索结果（2026-09-12 20:07 通过——agent=researcher / SUCCEEDED / 实时新闻带来源；tool_call_log：tavily_search 首调 10s 超时 ERROR → 重试 OK 2986ms，server=tavily）
