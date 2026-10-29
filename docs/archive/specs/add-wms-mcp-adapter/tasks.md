# Tasks: add-wms-mcp-adapter

- [x] Task 1: 对照 WMS OpenAPI（仓库根 wms-openapi.json，189 端点）敲定首批只读端点映射
  - [x] SubTask 1.1: 解析 OpenAPI：鉴权未声明（adapter 以可配置 header 默认 X-Access-Token 应对）、分页 pageNo/pageSize、110 个非读端点确认排除
  - [x] SubTask 1.2: 10 个语义化工具映射定稿（见 spec.md 附录 A，实现时复制进模块 README）
- [x] Task 2: 搭建 wms-mcp-adapter 模块骨架
  - [x] SubTask 2.1: 根 pom.xml modules 新增 `wms-mcp-adapter`，模块 pom 继承 parent（Spring Boot + MCP server starter，版本由 dependencyManagement 锁定）
  - [x] SubTask 2.2: 独立 Spring Boot 应用 + application.yaml（端口、`wms.api-base`/`wms.api-token`/`wms.token-header`/`wms.mcp-token` 全环境变量注入空默认）
  - [x] SubTask 2.3: 验证 `mvn -pl wms-mcp-adapter -am package` 产出可独立运行 jar，server fat jar 内容不受影响
- [x] Task 3: WMS REST 客户端
  - [x] SubTask 3.1: RestClient 封装（可配置鉴权头、连接/读取超时、pageNo/pageSize 分页透传）
  - [x] SubTask 3.2: 非 2xx 错误分类（鉴权失败/参数错误/WMS 不可达/超时）→ 中文可读提示；WMS 不可达返回结构化错误文本不抛崩
- [x] Task 4: 首批只读 MCP 工具实现（附录 A 10 个）
  - [x] SubTask 4.1: 查询类 7 工具（search_stock/search_products/search_stock_in_orders/search_out_orders/search_shipments/search_warehouse_network/search_tasks，@Tool 中文 description + 参数说明）
  - [x] SubTask 4.2: 聚合类 3 工具（get_product/get_stock_in_order/get_out_order，主表 + 子表一次返回）
  - [x] SubTask 4.3: 组合查询：search_stock 支持 productName 入参（先查商品表换 productId，对模型透明）
- [x] Task 5: MCP Streamable HTTP 端点 + 调用方 token 校验
  - [x] SubTask 5.1: `/mcp` 端点暴露（Streamable HTTP）
  - [x] SubTask 5.2: 过滤器校验 `X-API-Token`（常量时间比较，对齐 ApiTokenFilter 口径），未通过拒绝
- [x] Task 6: 单元测试（无真实 WMS 依赖）
  - [x] SubTask 6.1: 工具 handler 单测（mock WMS 响应：正常/空结果/非 2xx/超时）
  - [x] SubTask 6.2: 鉴权单测（无 token/错 token 拒绝，对 token 放行）
  - [x] SubTask 6.3: tools/list 只读守护断言（10 个工具名单 + 无写操作端点映射）
- [x] Task 7: harness 侧接线（依赖 Task 1 工具名定稿，可与 Task 3-5 并行）
  - [x] SubTask 7.1: Flyway 迁移 `V<next>__add_wms_agent.sql`：INSERT IGNORE 新增 `wms` agent 行（is_internal=0，仓储专员 prompt：报库存必带储位/批次、单号引导、金额口径，tools 列绑定附录 A 工具名）
  - [x] SubTask 7.2: README「MCP 工具接入」节补 wms server 条目模板（mcp-config.json gitignored）+ 新增 `docs/guides/wms-mcp.md` 使用指南
- [x] Task 8: 端到端验证
  - [x] SubTask 8.1: adapter 独立起服务 → harness 配置后连接（启动日志/MCP 握手确认）
  - [x] SubTask 8.2: web 聊天自然语言查库存 → 回答带数据；`tool_call_log` 记录含来源 server
  - [x] SubTask 8.3: 全量回归 `mvn -pl server -am test` + adapter 模块测试 0 失败 0 错误

# Task Dependencies

- Task 1 已完成（映射定稿于 spec.md 附录 A）
- Task 2 → Task 3 → Task 4 → Task 5 → Task 6（串行）
- Task 7 依赖 Task 1（已完成），可在 Task 3-5 期间并行
- Task 8 依赖 Task 5、6、7 全部完成
