# 🔌 MCP 工具接入

> README「MCP 工具接入」一节的完整版：配置模板、按 agent 分配工具与 general 最小权限对齐。

---


工具生态经 MCP 扩展：client 支持 stdio（本地进程）与 Streamable HTTP（远程 server），懒连接 + 按 server 失败隔离（外部 server 挂了不影响主链路），连接事件落库 `mcp_server_log`。

连接配置在项目根 `mcp-config.json`（Claude/Cursor 同款 `mcpServers` 结构）。**该文件含 API Key，属本地配置不入库**（已在 `.gitignore`，clone 后需自建），模板：

```json
{
  "mcpServers": {
    "tavily": { "url": "https://mcp.tavily.com/mcp/?tavilyApiKey=<你的Tavily key>" }
  }
}
```

- 远程 server 填 `url`，本地进程填 `command`(±`args`)；`enabled: false` 跳过条目；**改配置需重启**
- **按 agent 分配**：`agent` 表 `tools` 列按精确工具名授予（改库免重启）。如把 Tavily 搜索分给 general/researcher：

```sql
UPDATE agent SET tools = CONCAT(tools, ', tavily_search')
WHERE agent_name IN ('general', 'researcher') AND tools NOT LIKE '%tavily_search%';
```

- 未声明的工具模型不可见；声明时 server 未连接则该 token 跳过（warn），不影响其余工具

> [!IMPORTANT]
> **最小权限**：`general` 是所有回退路径的落点（路由兜底/未识别专家/lead 漏指派），已收敛为只读探索者
> （网页抓取 + 沙箱只读文件 + 浏览器 + MCP 白名单）——执行类 `sandbox.base` 与写入类 `sandbox.write`
> 只留给 lead 明确指派的 `coder`/`analyst`。general 的 `tools` 列对齐已由 V17 迁移自动落库（新库/存量库跑 Flyway 即生效），仅 Flyway 管不到的外部库需手工对齐
> （数据驱动优先于代码内置，改库即生效）：

```sql
UPDATE agent SET tools = 'web, sandbox.read, sandbox.browser, tavily_search'
WHERE agent_name = 'general' AND (tools LIKE '%sandbox.base%' OR tools LIKE '%sandbox.write%');
```

> [!NOTE]
> MCP 工具返回内容不经过项目的内容裁剪链（缓存/相关段落过滤），超长由工具结果硬预算统一截断。


---

[⬅ 返回 README](../../README.md)
