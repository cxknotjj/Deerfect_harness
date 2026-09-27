# 🏭 WMS 仓储查询 MCP 适配

> 独立部署的 MCP server（`wms-mcp-adapter` 模块）：把 WMS 仓储系统的只读 OpenAPI 包装为 10 个中文语义查询工具，harness 以自然语言查库存/查单据。harness 侧零代码改动——`mcp-config.json` 加一个 server 条目 + agent 表 `wms` 行（V25 迁移自动落库）。

---

## 它是什么

- **独立 Spring Boot 应用**（不进 server fat jar，`java -jar` 独立部署），暴露 MCP Streamable HTTP 端点（`/mcp`），与 harness、WMS 均为进程外解耦：WMS 宕机只影响 wms 工具调用（返回结构化错误文本，MCP 连接保持存活），不影响主链路
- **只读**：仅包装 WMS 查询类 GET 端点；全部写操作、`exportXls` 文件流导出与业务动作（发货/面单打印等）一律不暴露
- **双侧鉴权、零硬编码**：adapter→WMS 用只读账号凭证（环境变量注入，空默认）；harness→adapter 校验 `X-API-Token` 请求头（常量时间比较）
- **组合查询对模型透明**：按商品名称查库存时，adapter 内部先查商品表换 productId 再查库存，模型一次调用即得结果

## 构建与运行

```bash
mvn -pl wms-mcp-adapter -am package
java -jar wms-mcp-adapter/target/wms-mcp-adapter-<version>.jar
```

环境变量（启动前注入，无任何默认凭据）：

| 环境变量 | 作用 | 说明 |
|---|---|---|
| `WMS_API_BASE` | WMS OpenAPI 基础地址 | 如 `http://wms.example.com` |
| `WMS_API_TOKEN` | adapter→WMS 的鉴权凭证 | 只读账号 token |
| `WMS_TOKEN_HEADER` | adapter→WMS 的鉴权头名称 | 默认 `X-Access-Token`（jeecg 惯例；OpenAPI 未声明鉴权方案，接入前与 WMS 方确认） |
| `WMS_MCP_TOKEN` | harness→adapter 的调用方 token | mcp-config.json 条目 headers 中 `X-API-Token` 须与其一致 |
| 端口 | adapter 监听端口 | 由 adapter 的 `application.yaml` 配置（可用 `SERVER_PORT` 覆盖），下例以 `18080` 为例 |

## harness 接入

**1. mcp-config.json 新增 wms 条目**（项目根，gitignored，改配置需重启）：

```json
{
  "mcpServers": {
    "tavily": { "url": "https://mcp.tavily.com/mcp/?tavilyApiKey=<你的Tavily key>" },
    "wms": {
      "url": "http://127.0.0.1:18080/mcp",
      "headers": { "X-API-Token": "<与 adapter 的 WMS_MCP_TOKEN 一致>" }
    }
  }
}
```

> [!NOTE]
> 当前 harness 版本的 mcp-config.json 解析仅识别 `url`/`command`/`args`/`enabled` 字段（见 `server/src/main/java/com/dark/javaHarness/tool/McpConfigParser.java`），`headers` 字段的支持以 harness 版本为准——未支持时 token 不会随请求携带，adapter 将拒绝调用（见常见问题）。

**2. agent 表 wms 行**：由 Flyway 迁移 `V25__add_wms_agent.sql` 自动落库（`INSERT IGNORE` 幂等；is_internal=0，web AgentSelect 可见可绑定；tools 列绑定下表 10 个工具名，改库免重启）。前提是 `model_provider` 表已有 `qwen3.8-27b` 行（V1 种子数据自带）；缺该行时迁移不插行，补齐后重跑迁移即可。

**3. 使用**：QQ / web / CLI 任一渠道选择「仓储专员（wms）」或由编排指派后，即可自然语言查询；每次工具调用落库 `tool_call_log`（含来源 server=wms）。

## 工具清单（只读，10 个）

| # | MCP 工具 | 语义 | WMS 端点 | 关键参数（透传过滤） |
|---|---|---|---|---|
| 1 | wms_search_stock | 查库存（数量/批次/储位） | GET /inventory/wmsInventory/list | productId、locationCode、batchNumber、containerCode、pageNo/pageSize；支持 productName 先查商品换 id（组合） |
| 2 | wms_search_products | 商品检索 | GET /goods/wmsProducts/list | productName、productCode、productBarcode、productBrand、ownerId |
| 3 | wms_get_product | 商品详情 | GET /goods/wmsProducts/queryById | id |
| 4 | wms_search_stock_in_orders | 入库单检索 | GET /inorder/wmsStockInOrders/list | orderNumber、orderType、status、expectedArrivalTime |
| 5 | wms_get_stock_in_order | 入库单主+明细聚合 | GET /inorder/wmsStockInOrders/queryById + /queryWmsStockInOrderItemsByMainId | id |
| 6 | wms_search_out_orders | 出库单检索 | GET /outorder/wmsOutOrders/list | orderNo、orderType、status、warehouseId、ownerId |
| 7 | wms_get_out_order | 出库单主+明细+分配聚合 | GET /outorder/wmsOutOrders/queryById + /queryWmsOutOrdersItemsByMainId + /queryWmsOutOrdersAllocationByMainId | id |
| 8 | wms_search_shipments | 包裹/发运检索（含明细） | GET /shipment/wmsShipment/list (+ /queryWmsShipmentDetailByMainId) | 单号、状态、日期 |
| 9 | wms_search_warehouse_network | 仓库/库区/库位查询（level 分派） | GET /warehouse/wmsWarehouses\|wmsStorageZones\|wmsStorageLocations/list | level、编码/名称关键词、上级 id、状态 |
| 10 | wms_search_tasks | 收货/上架/拣货/波次/缺货任务查询（type 分派） | GET /inorder/receiveTasks\|inorder/putawayTasks\|wave/pickingTasks\|wave/wmsWaveMaster\|wave/wmsShortageRegistration/list | taskType、任务号、状态、productId |

## 常见问题

**Q：工具调用返回「WMS 暂不可达」？**

- 在 adapter 部署机验证 WMS 可达：`curl $WMS_API_BASE/<任一查询端点>`（带鉴权头）
- WMS 宕机期间 adapter 返回结构化错误文本、MCP 连接保持存活，WMS 恢复后无需重启 harness 或 adapter

**Q：adapter→WMS 鉴权失败？**

- 确认 `WMS_API_TOKEN` 为有效只读账号凭证，且 `WMS_TOKEN_HEADER` 与 WMS 方约定的请求头名称一致（默认 `X-Access-Token`）

**Q：harness→adapter 鉴权失败（调用被拒）？**

- 确认 mcp-config.json 条目 headers 中 `X-API-Token` 的值与 adapter 环境变量 `WMS_MCP_TOKEN` 完全一致
- 确认所用 harness 版本的 mcp-config.json 解析支持 `headers` 字段（当前版本仅识别 `url`/`command`/`args`/`enabled`，token 未携带会被 adapter 拒绝）

**Q：agent 的工具列表里没有 wms_* 工具？**

- mcp-config.json 无 wms 条目或 url 写错：harness 启动/预热日志会 warn「连接/发现失败」（改配置需重启）
- agent 表 tools 列未绑定：确认 wms 行 `tools` 列含对应工具名（精确名授予，改库免重启）
- 按名授予时 server 未连接则该 token 跳过（warn），不影响其余工具；`enabled: false` 会整体跳过条目

---

[⬅ 返回 README](../../README.md)
