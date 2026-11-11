---
name: wms
description: 查询 WMS 仓储数据（库存、商品、出入库单、发运、仓库网络、作业任务）时使用；先读本说明确认 resource 与参数，再调用 wms_query。
agents: wms
---

# WMS 只读查询

## 只有一个工具

`wms_query(resource, params)`

- `resource`：查什么（下表第一列）
- `params`：过滤条件 + 分页。**只能传该 resource 列出的字段**，多传会被拒绝并列出可用项

分页统一用 `pageNo`（从 1 开始）和 `pageSize`（服务端上限 20）。不传就是第一页 20 条。

这个工具由 WMS MCP 服务提供。**服务没连上时工具会直接消失**，不是查不到数据——
遇到这种情况如实告诉用户「WMS 服务未连接」，不要反复换条件重试。

## 资源与参数

| resource | 查什么 | 可用参数 |
| --- | --- | --- |
| `stock` | 库存 | `productId` `productName` `locationCode` `batchNumber` `containerCode` `pageNo` `pageSize` |
| `product` | 商品检索 | `productName` `productCode` `productBarcode` `productBrand` `ownerId` `pageNo` `pageSize` |
| `product_detail` | 商品详情 | `id`（必填） |
| `stock_in_order` | 入库单检索 | `orderNumber` `orderType` `status` `expectedArrivalTime` `pageNo` `pageSize` |
| `stock_in_order_detail` | 入库单详情 | `id`（必填；返回主表 + 明细） |
| `out_order` | 出库单检索 | `orderNo` `orderType` `status` `warehouseId` `ownerId` `pageNo` `pageSize` |
| `out_order_detail` | 出库单详情 | `id`（必填；返回主表 + 明细 + 分配明细） |
| `shipment` | 发运单检索 | `shipmentNo` `trackingNo` `orderNo` `status` `pageNo` `pageSize` |
| `warehouse` | 仓库列表 | `code` `name` `status` `pageNo` `pageSize` |
| `storage_zone` | 库区列表 | `code` `name` `warehouseId` `status` `pageNo` `pageSize` |
| `storage_location` | 库位列表 | `code` `warehouseId` `zoneId` `status` `pageNo` `pageSize` |
| `receive_task` | 收货任务 | `taskNumber` `status` `productId` `pageNo` `pageSize` |
| `putaway_task` | 上架任务 | 同上 |
| `picking_task` | 拣货任务 | 同上 |
| `wave` | 波次主表 | `waveNo` `status` `pageNo` `pageSize` |
| `shortage` | 缺货登记 | `status` `productId` `pageNo` `pageSize` |

## 三条查询套路

**1. 按商品名查库存：一步**

```text
wms_query(resource="stock", params={"productName":"XXX"})
```

服务端会先用名称查商品表换 `productId` 再查库存，**不需要你自己查一遍商品**。
商品名可能匹配多个，结果里带 `matchedProductId` / `matchedProductName`，核对一下；
差太多就把候选列给用户选，别默认第一个是对的。

**2. 查单据：先列表拿 id，再查详情**

```text
out_order(orderNo="...")          → 拿 id
out_order_detail(id="...")        → 主表 + 明细 + 分配明细
```

入库单同理：`stock_in_order` → `stock_in_order_detail`。
发运单只有 `shipment`，默认给前 10 条附明细，不用再查详情。

**3. 仓库网络自顶向下**

```text
warehouse                      → 仓库
storage_zone(warehouseId)      → 库区
storage_location(zoneId)       → 库位
```

任务类直接选 `receive_task` / `putaway_task` / `picking_task` / `wave` / `shortage`，
**不要传 taskType**——服务端已经按资源固定好了。

## 边界

- **只读**。没有写接口，不要尝试下架、改单、打印面单。
- **不要编造 id**。id 只能从列表结果里取；取不到就如实说查不到。
- **空结果先核对条件**。名称、单号差一个字就查不到，别立刻换一堆条件乱试；
  先确认用户给的信息是否准确，再决定放宽还是缩小。
- **报错分两类**：
  - 工具不存在 / 「WMS 服务未连接」→ 部署问题，不是查错了
  - 「HTTP 401/403」或「未配置」→ token 失效或环境缺失，同样不是数据问题
- **结果别整段复述**。列表就摘要（共多少条、关键字段），详情只讲用户问的那部分。
