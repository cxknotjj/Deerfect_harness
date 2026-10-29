# Checklist: add-wms-mcp-adapter

- [x] wms-mcp-adapter 独立 jar 可运行（java -jar），Streamable HTTP `/mcp` 端点可被 MCP 客户端握手（tools/list 返回附录 A 全部 10 个工具）
- [x] 工具全部为只读查询类且与附录 A 映射一一对应；tools/list 存在只读守护断言（防写操作/exportXls/ai 端点混入）
- [x] adapter → WMS 凭证（WMS_API_BASE / WMS_API_TOKEN / 鉴权头名）仅环境变量注入，源码与默认配置零硬编码
- [x] 调用方 token 校验生效：无 token / 错 token 被拒绝，正确 token 放行（常量时间比较）
- [x] 组合查询生效：按商品名称查库存时 adapter 自动换 productId，对模型透明
- [x] 聚合工具生效：入库单/出库单工具一次返回主表 + 明细（+ 分配），无需模型多次往返
- [x] WMS 不可达时工具返回结构化中文错误文本，MCP 连接与 harness 主链路不受影响
- [x] Flyway 迁移幂等（INSERT IGNORE），agent 表 `wms` 行 is_internal=0 + tools 列绑定工具名，web AgentSelect 可见且可绑定会话
- [x] mcp-config.json 模板与 docs/guides/wms-mcp.md 使用指南落档（adapter 构建运行/配置项/工具清单/常见问题）
- [x] 端到端：web 聊天自然语言查库存得到正确回答，tool_call_log 落库含来源 server
- [x] 全量 `mvn -pl server -am test` + wms-mcp-adapter 模块测试 0 失败 0 错误（全程无真实 WMS 依赖）
- [x] server fat jar 内容不受新模块影响（不含 wms-mcp-adapter class）
