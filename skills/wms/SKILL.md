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

## 资源与参数

| resource | 查什么 | 可用参数 |
| --- | --- | --- |
| `stock` | 库存 | `productId` `locationCode` `batchNumber` `containerCode` `pageNo` `pageSize` |
| `product` | 商品检索 | `productName` `productCode` `productBarcode` `productBrand` `ownerId` `pageNo` `pageSize` |
| `product_detail` | 商品详情 | `id`（必填） |
| `stock_in_order` | 入库单检索 | `orderNumber` `orderType` `status` `expectedArrivalTime` `pageNo` `pageSize` |
| `stock_in_order_detail` | 入库单主表 | `id`（必填） |
| `stock_in_order_items` | 入库单明细 | `id`（必填） |
| `out_order` | 出库单检索 | `orderNo` `orderType` `status` `warehouseId` `ownerId` `pageNo` `pageSize` |
| `out_order_detail` | 出库单主表 | `id`（必填） |
| `out_order_items` | 出库单明细 | `id`（必填） |
| `out_order_allocations` | 出库单分配明细 | `id`（必填） |
| `shipment` | 发运单检索 | `shipmentNo` `trackingNo` `orderNo` `status` `pageNo` `pageSize` |
| `shipment_detail` | 发运单明细 | `id`（必填） |
| `warehouse` | 仓库列表 | `code` `name` `status` `pageNo` `pageSize` |
| `storage_zone` | 库区列表 | `code` `name` `warehouseId` `status` `pageNo` `pageSize` |
| `storage_location` | 库位列表 | `code` `warehouseId` `zoneId` `status` `pageNo` `pageSize` |
| `receive_task` | 收货任务 | `taskNumber` `status` `productId` `pageNo` `pageSize` |
| `putaway_task` | 上架任务 | 同上 |
| `picking_task` | 拣货任务 | 同上 |
| `wave` | 波次主表 | `waveNo` `status` `pageNo` `pageSize` |
| `shortage` | 缺货登记 | `status` `productId` `pageNo` `pageSize` |

## 三条查询套路

**1. 只有商品名，要查库存**

库存接口只认 `productId`，不认名称。所以两步：

```text
wms_query(resource="product", params={"productName":"XXX"})   → 取第一条的 id
wms_query(resource="stock",   params={"productId":"<id>"})    → 查库存
```

商品名可能匹配多个，用返回的 `productName` 和用户说的核对一下；差太多就先把候选列给用户选。

**2. 查单据，先列表再详情**

```text
out_order(orderNo="...")            → 拿 id
out_order_detail(id="...")          → 主表
out_order_items(id="...")           → 明细
out_order_allocations(id="...")     → 分配明细
```

入库单同理：`stock_in_order` → `stock_in_order_detail` + `stock_in_order_items`。
发运单：`shipment` → `shipment_detail`。

**3. 仓库网络自顶向下**

```text
warehouse                → 仓库
storage_zone(warehouseId) → 库区
storage_location(zoneId)  → 库位
```

任务类直接选 `receive_task` / `putaway_task` / `picking_task` / `wave` / `shortage`，
**不要传 taskType**——服务端已经按资源固定好了。

## 边界

- **只读**。没有写接口，不要尝试下架、改单、打印面单。
- **不要编造 id**。id 只能从列表结果里取；取不到就如实说查不到。
- **空结果先核对条件**。名称、单号差一个字就查不到，别立刻换一堆条件乱试；
  先确认用户给的信息是否准确，再决定放宽还是缩小。
- **报错区分两类**：
  - 「未配置（缺少 WMS_API_BASE）」→ 是部署环境问题，告诉用户去配置，不是查错了
  - 「HTTP 401/403」→ 是 token 失效或没权限，同样不是数据问题
- **结果别整段复述**。列表就摘要（共多少条、关键字段），详情只讲用户问的那部分。
