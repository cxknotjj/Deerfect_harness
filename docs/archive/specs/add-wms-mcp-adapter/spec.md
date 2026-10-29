# WMS MCP 适配服务 Spec（方案 A：独立 MCP 适配）

## Why

WMS 仓储系统已完工（OpenAPI 见仓库根 `wms-openapi.json`，189 端点，jeecg 风格 list/queryById），但缺乏 AI 侧入口。按已确认的方案 A（独立系统 + AI 桥接、MCP server 包装、只读起步），新建独立 MCP 适配服务把 WMS 只读能力以语义化工具暴露给 harness，实现自然语言查库存/查单据；harness 侧零代码改动（配置 + agent 表一行接入）。

## What Changes

- 新增 Maven 模块 `wms-mcp-adapter`：独立 Spring Boot 应用（不进 server fat jar，独立部署），实现 MCP Streamable HTTP 端点，把 WMS 只读 GET 端点包装为 10 个中文业务语义 MCP 工具（映射见附录 A）
- WMS 凭证与地址经环境变量注入（`WMS_API_BASE` / `WMS_API_TOKEN`，只读账号），零硬编码；鉴权头默认 `X-Access-Token`（jeecg 惯例，OpenAPI 未声明鉴权方案，以可配置 header 应对，接入前与 WMS 方确认）
- 调用方鉴权：harness 经 mcp-config.json headers 携带 token，adapter 校验（常量时间比较，对齐 ApiTokenFilter 口径）
- harness 侧接线：`mcp-config.json` 新增 wms server 条目（该文件 gitignored，模板更新进 README）；Flyway 迁移 `INSERT IGNORE` 新增 `wms` 仓储专员 agent 行（is_internal=0，prompt 仓储人设，tools 列绑定 MCP 工具名）
- 新增使用指南 `docs/guides/wms-mcp.md`
- **不包含**（后续阶段）：写操作（WMS 共 110 个非读端点，含波次发货/面单打印等业务动作，一律不包装）、exportXls 文件流导出、`/ai/*`（WMS 自带 AI 端点）、多仓库权限模型

## Impact

- Affected specs: 无既有 spec 受影响；依赖既有「MCP 工具接入」（mcp-config.json 多 server 架构）与「agent 表驱动注册」能力
- Affected code: 新模块 `wms-mcp-adapter/`；根 `pom.xml`（modules 增条目）；`server/src/main/resources/db/migration/`（新增迁移）；`docs/guides/wms-mcp.md`（新增）；`README.md`（MCP 章节模板）

## ADDED Requirements

### Requirement: MCP 适配服务独立部署

wms-mcp-adapter SHALL 作为独立 Spring Boot 应用构建与运行（`java -jar`），暴露 MCP Streamable HTTP 端点（默认 `/mcp`），与 harness 及 WMS 均为进程外解耦；加入根 Maven reactor 统一构建，但不影响 server 产物内容。

#### Scenario: 独立启动与端点可达
- **WHEN** 配置环境变量后执行 `java -jar wms-mcp-adapter.jar`
- **THEN** 服务在配置端口监听，`/mcp` 端点可被 MCP 客户端初始化连接（tools/list 返回附录 A 全部工具）

### Requirement: 只读工具集（语义化包装，附录 A 定稿）

适配服务 SHALL 仅包装 WMS 只读 GET 端点为附录 A 定稿的 10 个粗粒度中文语义工具（查询库存/商品/入库单/出库单/包裹/仓储网络/作业任务 + 单据主子聚合），SHALL NOT 暴露任何写操作端点、exportXls 导出或 `/ai/*`。单据明细类工具 SHALL 聚合主表 + 子表一次返回（如入库单主表 + items 明细），避免模型多次往返。

#### Scenario: 工具清单只读
- **WHEN** MCP 客户端执行 tools/list
- **THEN** 返回的 10 个工具均为查询类，无任何变更类操作

#### Scenario: 工具语义可被模型正确选择
- **WHEN** 用户在聊天中问「XX 商品的库存还有多少」
- **THEN** 模型选择查库存工具并以合法参数调用成功；按商品名称查询时 adapter 内部先查商品表换 productId 再查库存（组合行为对模型透明）

### Requirement: 凭据与鉴权（双侧收口）

- 适配服务 → WMS：SHALL 经环境变量注入 `WMS_API_BASE` 与 `WMS_API_TOKEN`（只读账号），鉴权头名称可配置（默认 `X-Access-Token`），SHALL NOT 硬编码任何凭据
- harness → 适配服务：SHALL 校验调用方 token（HTTP header 携带），未携带或不匹配的请求 SHALL 被拒绝；token 同样经环境变量注入

#### Scenario: 无 token 调用被拒
- **WHEN** 未携带 token 的 MCP 请求到达 adapter
- **THEN** 请求被拒绝（401/协议层拒绝），不触达 WMS

#### Scenario: WMS 凭证零硬编码
- **WHEN** 审查 adapter 源码与默认配置
- **THEN** 不存在任何明文 WMS 地址凭证默认值（空默认 + 环境变量注入）

### Requirement: REST 客户端健壮性

适配服务的 WMS REST 客户端 SHALL 具备连接/读取超时、非 2xx 错误的中文可读分类提示（鉴权失败/参数错误/WMS 不可达/超时），WMS 不可用时工具调用 SHALL 返回结构化错误文本而非异常崩溃，MCP 连接保持存活。

#### Scenario: WMS 宕机不炸会话
- **WHEN** WMS 服务不可达时用户在聊天中查询库存
- **THEN** 模型收到「WMS 暂不可达」类错误文本并告知用户，harness 主链路与后续对话不受影响

### Requirement: harness 侧表驱动接入

- Flyway 迁移 SHALL 以 `INSERT IGNORE` 幂等新增 `wms` agent 行（is_internal=0、专属仓储专员 prompt、tools 列绑定附录 A 工具名），复用 agent 表驱动注册（改库即生效免重启）
- `mcp-config.json`（gitignored）模板 SHALL 在 README 给出 wms server 条目示例（url + token header）

#### Scenario: 三渠道即刻可用
- **WHEN** adapter 运行且 harness 完成配置
- **THEN** QQ / web / CLI 任一渠道询问库存类问题，模型调用 wms 工具并以自然语言回答

#### Scenario: 调用审计
- **WHEN** 上述工具调用发生
- **THEN** `tool_call_log` 落库记录工具名、参数摘要、成败与来源 server（既有 MCP 观测口径，零新增代码）

### Requirement: 测试覆盖

适配服务 SHALL 具备单元测试：工具 handler 逻辑（mock WMS REST 响应）、组合查询（productName→productId）、REST 客户端错误分类、调用方鉴权用例、tools/list 只读守护断言；全部测试在无真实 WMS 依赖下通过。

#### Scenario: 无 WMS 依赖下测试通过
- **WHEN** 执行 `mvn test`（含新模块）
- **THEN** 全部用例通过（WMS 交互均为 mock），CI 无需真实 WMS 环境

## 附录 A：工具 ↔ WMS 端点映射（定稿，源 wms-openapi.json）

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

排除面（SHALL NOT 包装）：全部 POST/PUT/DELETE（110 个）、`*/exportXls`（文件流）、`/shipment/waybill/printWaybill` 与 `/send`（业务动作）、`/ai/*`、`queryById` 类冗余明细（list 已返回全字段，仅单据主子聚合场景保留聚合工具）。
