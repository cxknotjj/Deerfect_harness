package com.dark.wms.tool;

import com.dark.wms.client.WmsEndpoints;
import com.dark.wms.client.WmsPage;
import com.dark.wms.client.WmsRestClient;
import com.dark.wms.client.WmsApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WMS 只读 MCP 工具集（spec 附录 A 定稿 10 个，全部 {@code wms_} 前缀）：
 * 7 个查询 + 3 个主子表聚合，中文 description 与参数说明供模型正确选择与传参。
 *
 * <p>约定：
 * <ul>
 *   <li>全部只读 GET——写操作、exportXls、面单打印/发送、{@code /ai/*} 一律不包装</li>
 *   <li>错误以结构化 JSON 文本返回（{@code {"error":true,"message":...}}），不抛崩，MCP 会话保持存活</li>
 *   <li>组合查询：{@code wms_search_stock} 支持 productName 入参，服务端先查商品表换 productId
 *       再查库存，组合行为对模型透明</li>
 * </ul>
 */
@Slf4j
@Component
public class WmsTools {

    /** 包裹检索附带明细的条数上限：避免大结果集逐单拉明细拖垮响应 */
    private static final int SHIPMENT_DETAIL_LIMIT = 10;
    /** 分页默认值 */
    private static final String DEFAULT_PAGE_NO = "1";
    private static final int DEFAULT_PAGE_SIZE = 20;
    /** pageSize 服务端钳制上限：防模型传大页数一次拉取过多记录撑爆工具结果 */
    private static final int MAX_PAGE_SIZE = 20;

    private final WmsRestClient client;
    private final ObjectMapper mapper;

    public WmsTools(WmsRestClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    // ---------- 1. 库存（组合查询：productName → productId 透明换查） ----------

    @Tool(name = "wms_search_stock",
            description = "查询 WMS 库存（数量/批次/储位）：按商品、储位编码、批次号、容器编码过滤，"
                    + "返回库存数量、分配数量、可用数量等。支持直接传 productName，"
                    + "服务端自动先查商品表换 productId 再查库存（对调用方透明）")
    public String searchStock(
            @ToolParam(description = "商品 id（可选；与 productName 至少提供其一，也可两者都不传改用其他过滤条件）", required = false)
            String productId,
            @ToolParam(description = "商品名称（可选；多个匹配商品时按第一个匹配商品查询，结果中带 matchedProductName 供核对）", required = false)
            String productName,
            @ToolParam(description = "储位编码（可选，精确过滤）", required = false) String locationCode,
            @ToolParam(description = "批次号（可选，精确过滤）", required = false) String batchNumber,
            @ToolParam(description = "容器编码（可选，精确过滤）", required = false) String containerCode,
            @ToolParam(description = "页码，默认 1", required = false) Integer pageNo,
            @ToolParam(description = "每页条数，服务端上限 20", required = false) Integer pageSize) {
        try {
            String pid = productId;
            Map<String, Object> matched = Map.of();
            if (pid == null || pid.isBlank()) {
                boolean hasOtherFilter = notBlank(locationCode) || notBlank(batchNumber) || notBlank(containerCode);
                if (notBlank(productName)) {
                    // 组合查询：先查商品表换 productId，对模型透明
                    WmsPage products = client.list(WmsEndpoints.PRODUCTS_LIST,
                            filterParams("productName", productName,
                                    "pageNo", DEFAULT_PAGE_NO, "pageSize", DEFAULT_PAGE_SIZE));
                    if (products.records().isEmpty()) {
                        return errorJson("未找到名称为「" + productName + "」的商品，无法查询其库存；"
                                + "可先用 wms_search_products 核对商品名称");
                    }
                    Map<String, Object> first = products.records().get(0);
                    pid = String.valueOf(first.get("id"));
                    matched = Map.of("matchedProductId", pid,
                            "matchedProductName", String.valueOf(first.getOrDefault("productName", "")));
                } else if (!hasOtherFilter) {
                    return errorJson("请至少提供一个查询条件：productId、productName、locationCode、batchNumber、containerCode 之一");
                }
            }
            Map<String, String> query = filterParams("productId", pid, "locationCode", locationCode,
                    "batchNumber", batchNumber, "containerCode", containerCode);
            query.putAll(paging(pageNo, pageSize));
            return pageJson(client.list(WmsEndpoints.INVENTORY_LIST, query), query, matched);
        } catch (WmsApiException e) {
            return errorJson(e.getMessage());
        } catch (Exception e) {
            return unexpected(e);
        }
    }

    // ---------- 2. 商品 ----------

    @Tool(name = "wms_search_products",
            description = "检索 WMS 商品档案：按商品名称/编码/条码/品牌/货主过滤，返回商品名称、编码、规格、条码、品牌等信息")
    public String searchProducts(
            @ToolParam(description = "商品名称（可选，精确过滤）", required = false) String productName,
            @ToolParam(description = "商品编码/SKU 编码（可选，精确过滤）", required = false) String productCode,
            @ToolParam(description = "商品条码（可选，精确过滤）", required = false) String productBarcode,
            @ToolParam(description = "商品品牌（可选，精确过滤）", required = false) String productBrand,
            @ToolParam(description = "货主 id（可选，精确过滤）", required = false) String ownerId,
            @ToolParam(description = "页码，默认 1", required = false) Integer pageNo,
            @ToolParam(description = "每页条数，服务端上限 20", required = false) Integer pageSize) {
        try {
            Map<String, String> query = filterParams("productName", productName, "productCode", productCode,
                    "productBarcode", productBarcode, "productBrand", productBrand, "ownerId", ownerId);
            query.putAll(paging(pageNo, pageSize));
            return pageJson(client.list(WmsEndpoints.PRODUCTS_LIST, query), query, Map.of());
        } catch (WmsApiException e) {
            return errorJson(e.getMessage());
        } catch (Exception e) {
            return unexpected(e);
        }
    }

    @Tool(name = "wms_get_product",
            description = "查询单个 WMS 商品完整档案（按商品 id）")
    public String getProduct(
            @ToolParam(description = "商品 id") String id) {
        try {
            Map<String, Object> product = client.one(WmsEndpoints.PRODUCTS_BY_ID, id);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("product", product);
            if (product.isEmpty()) {
                out.put("hint", "未找到该 id 对应的商品");
            }
            return toJson(out);
        } catch (WmsApiException e) {
            return errorJson(e.getMessage());
        } catch (Exception e) {
            return unexpected(e);
        }
    }

    // ---------- 3. 入库单 ----------

    @Tool(name = "wms_search_stock_in_orders",
            description = "检索 WMS 入库单：按入库单号/单据类型/状态/预期到货时间过滤，返回单号、类型、收货/上架进度等")
    public String searchStockInOrders(
            @ToolParam(description = "入库单号（可选，精确过滤）", required = false) String orderNumber,
            @ToolParam(description = "单据类型（可选，如采购入库等，以 WMS 字典为准）", required = false) String orderType,
            @ToolParam(description = "状态（可选，以 WMS 字典为准）", required = false) String status,
            @ToolParam(description = "预期到货时间（可选，格式 yyyy-MM-dd HH:mm:ss）", required = false) String expectedArrivalTime,
            @ToolParam(description = "页码，默认 1", required = false) Integer pageNo,
            @ToolParam(description = "每页条数，服务端上限 20", required = false) Integer pageSize) {
        try {
            Map<String, String> query = filterParams("orderNumber", orderNumber, "orderType", orderType,
                    "status", status, "expectedArrivalTime", expectedArrivalTime);
            query.putAll(paging(pageNo, pageSize));
            return pageJson(client.list(WmsEndpoints.STOCK_IN_ORDERS_LIST, query), query, Map.of());
        } catch (WmsApiException e) {
            return errorJson(e.getMessage());
        } catch (Exception e) {
            return unexpected(e);
        }
    }

    @Tool(name = "wms_get_stock_in_order",
            description = "查询单个 WMS 入库单完整详情：主表单据 + 商品明细行一次返回（免多次往返）")
    public String getStockInOrder(
            @ToolParam(description = "入库单 id（可先用 wms_search_stock_in_orders 检索得到）") String id) {
        try {
            Map<String, Object> main = client.one(WmsEndpoints.STOCK_IN_ORDERS_BY_ID, id);
            List<Map<String, Object>> items = client.children(WmsEndpoints.STOCK_IN_ORDER_ITEMS, id);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("order", main);
            out.put("items", items);
            if (main.isEmpty()) {
                out.put("hint", "未找到该 id 对应的入库单");
            }
            return toJson(out);
        } catch (WmsApiException e) {
            return errorJson(e.getMessage());
        } catch (Exception e) {
            return unexpected(e);
        }
    }

    // ---------- 4. 出库单 ----------

    @Tool(name = "wms_search_out_orders",
            description = "检索 WMS 出库单：按出库单号/单据类型/状态/仓库/货主过滤，返回单号、类型、数量、收件人、发货信息等")
    public String searchOutOrders(
            @ToolParam(description = "出库单号（可选，精确过滤）", required = false) String orderNo,
            @ToolParam(description = "单据类型（可选，以 WMS 字典为准）", required = false) String orderType,
            @ToolParam(description = "状态（可选，以 WMS 字典为准）", required = false) String status,
            @ToolParam(description = "仓库 id（可选，精确过滤）", required = false) String warehouseId,
            @ToolParam(description = "货主 id（可选，精确过滤）", required = false) String ownerId,
            @ToolParam(description = "页码，默认 1", required = false) Integer pageNo,
            @ToolParam(description = "每页条数，服务端上限 20", required = false) Integer pageSize) {
        try {
            Map<String, String> query = filterParams("orderNo", orderNo, "orderType", orderType,
                    "status", status, "warehouseId", warehouseId, "ownerId", ownerId);
            query.putAll(paging(pageNo, pageSize));
            return pageJson(client.list(WmsEndpoints.OUT_ORDERS_LIST, query), query, Map.of());
        } catch (WmsApiException e) {
            return errorJson(e.getMessage());
        } catch (Exception e) {
            return unexpected(e);
        }
    }

    @Tool(name = "wms_get_out_order",
            description = "查询单个 WMS 出库单完整详情：主表单据 + 商品明细 + 库存分配明细一次返回（免多次往返）")
    public String getOutOrder(
            @ToolParam(description = "出库单 id（可先用 wms_search_out_orders 检索得到）") String id) {
        try {
            Map<String, Object> main = client.one(WmsEndpoints.OUT_ORDERS_BY_ID, id);
            List<Map<String, Object>> items = client.children(WmsEndpoints.OUT_ORDER_ITEMS, id);
            List<Map<String, Object>> allocations = client.children(WmsEndpoints.OUT_ORDER_ALLOCATIONS, id);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("order", main);
            out.put("items", items);
            out.put("allocations", allocations);
            if (main.isEmpty()) {
                out.put("hint", "未找到该 id 对应的出库单");
            }
            return toJson(out);
        } catch (WmsApiException e) {
            return errorJson(e.getMessage());
        } catch (Exception e) {
            return unexpected(e);
        }
    }

    // ---------- 5. 包裹/发运（list + 逐单附带明细） ----------

    @Tool(name = "wms_search_shipments",
            description = "检索 WMS 包裹/发运记录（含包裹明细）：按发运单号/运单号/订单号/状态过滤，"
                    + "返回物流承运、包裹数量、收发地址等，结果自动附带每个包裹的明细（最多前 10 条）")
    public String searchShipments(
            @ToolParam(description = "发运单号（可选，精确过滤）", required = false) String shipmentNo,
            @ToolParam(description = "运单号/物流单号（可选，精确过滤）", required = false) String trackingNo,
            @ToolParam(description = "关联出库单号（可选，精确过滤）", required = false) String orderNo,
            @ToolParam(description = "状态（可选，以 WMS 字典为准）", required = false) String status,
            @ToolParam(description = "页码，默认 1", required = false) Integer pageNo,
            @ToolParam(description = "每页条数，服务端上限 20", required = false) Integer pageSize) {
        try {
            Map<String, String> query = filterParams("shipmentNo", shipmentNo, "trackingNo", trackingNo,
                    "orderNo", orderNo, "status", status);
            query.putAll(paging(pageNo, pageSize));
            WmsPage page = client.list(WmsEndpoints.SHIPMENT_LIST, query);
            // 附带包裹明细（cap 前 10 条，避免大结果集逐单拉明细拖垮响应）；
            // 复制后再写入 details，兼容客户端返回的不可变记录结构
            List<Map<String, Object>> records = page.records();
            List<Map<String, Object>> enriched = new ArrayList<>(records.size());
            for (int i = 0; i < records.size(); i++) {
                Map<String, Object> record = records.get(i);
                if (i < SHIPMENT_DETAIL_LIMIT) {
                    Map<String, Object> copy = new LinkedHashMap<>(record);
                    try {
                        copy.put("details", client.children(WmsEndpoints.SHIPMENT_DETAILS, String.valueOf(record.get("id"))));
                    } catch (WmsApiException e) {
                        copy.put("detailsError", e.getMessage());
                    }
                    record = copy;
                }
                enriched.add(record);
            }
            return pageJson(new WmsPage(enriched, page.total()), query, Map.of());
        } catch (WmsApiException e) {
            return errorJson(e.getMessage());
        } catch (Exception e) {
            return unexpected(e);
        }
    }

    // ---------- 6. 仓储网络（level 分派） ----------

    @Tool(name = "wms_search_warehouse_network",
            description = "查询 WMS 仓储网络：仓库/库区/库位三级结构。level=warehouse 查仓库、zone 查库区、location 查库位，"
                    + "配合编码/名称关键词、上级仓库 id、上级库区 id 过滤")
    public String searchWarehouseNetwork(
            @ToolParam(description = "查询层级：warehouse=仓库 / zone=库区 / location=库位") String level,
            @ToolParam(description = "编码关键词（仓库编码/库区编码/库位编码，精确过滤）", required = false) String code,
            @ToolParam(description = "名称关键词（仓库名称/库区名称，精确过滤；库位无名称字段）", required = false) String name,
            @ToolParam(description = "上级仓库 id（zone/location 层级可用）", required = false) String warehouseId,
            @ToolParam(description = "上级库区 id（location 层级可用）", required = false) String zoneId,
            @ToolParam(description = "状态（可选，以 WMS 字典为准）", required = false) String status,
            @ToolParam(description = "页码，默认 1", required = false) Integer pageNo,
            @ToolParam(description = "每页条数，服务端上限 20", required = false) Integer pageSize) {
        try {
            String endpoint = switch (level == null ? "" : level.trim().toLowerCase()) {
                case "warehouse" -> WmsEndpoints.WAREHOUSES_LIST;
                case "zone" -> WmsEndpoints.STORAGE_ZONES_LIST;
                case "location" -> WmsEndpoints.STORAGE_LOCATIONS_LIST;
                default -> null;
            };
            if (endpoint == null) {
                return errorJson("level 须为 warehouse / zone / location 之一（分别对应仓库/库区/库位）");
            }
            Map<String, String> query = new LinkedHashMap<>();
            switch (level.trim().toLowerCase()) {
                case "warehouse" -> query.putAll(filterParams("warehouseCode", code, "warehouseName", name, "status", status));
                case "zone" -> query.putAll(filterParams("zoneCode", code, "zoneName", name, "warehouseId", warehouseId, "status", status));
                case "location" -> query.putAll(filterParams("locationCode", code, "warehouseId", warehouseId, "zoneId", zoneId, "status", status));
                default -> { /* 不可达：endpoint 判空已兜住 */ }
            }
            query.putAll(paging(pageNo, pageSize));
            return pageJson(client.list(endpoint, query), query, Map.of());
        } catch (WmsApiException e) {
            return errorJson(e.getMessage());
        } catch (Exception e) {
            return unexpected(e);
        }
    }

    // ---------- 7. 作业任务（taskType 分派） ----------

    @Tool(name = "wms_search_tasks",
            description = "查询 WMS 作业任务：收货/上架/拣货任务、波次、缺货登记。"
                    + "taskType=receive 查收货任务、putaway 查上架任务、picking 查拣货任务、wave 查波次、shortage 查缺货登记")
    public String searchTasks(
            @ToolParam(description = "任务类型：receive=收货 / putaway=上架 / picking=拣货 / wave=波次 / shortage=缺货登记") String taskType,
            @ToolParam(description = "任务号（receive/putaway/picking 对应任务号；wave 对应波次号；shortage 不支持）", required = false) String taskNumber,
            @ToolParam(description = "状态（可选，以 WMS 字典为准）", required = false) String status,
            @ToolParam(description = "商品 id（receive/putaway/picking/shortage 可用）", required = false) String productId,
            @ToolParam(description = "页码，默认 1", required = false) Integer pageNo,
            @ToolParam(description = "每页条数，服务端上限 20", required = false) Integer pageSize) {
        try {
            String type = taskType == null ? "" : taskType.trim().toLowerCase();
            String endpoint = switch (type) {
                case "receive" -> WmsEndpoints.RECEIVE_TASKS_LIST;
                case "putaway" -> WmsEndpoints.PUTAWAY_TASKS_LIST;
                case "picking" -> WmsEndpoints.PICKING_TASKS_LIST;
                case "wave" -> WmsEndpoints.WAVE_MASTER_LIST;
                case "shortage" -> WmsEndpoints.SHORTAGE_LIST;
                default -> null;
            };
            if (endpoint == null) {
                return errorJson("taskType 须为 receive / putaway / picking / wave / shortage 之一");
            }
            Map<String, String> query = switch (type) {
                // 三类任务共用同一组字段：taskNumber/taskType/taskStatus/productId
                case "receive", "putaway", "picking" -> filterParams("taskNumber", taskNumber,
                        "taskType", jeecgTaskType(type), "taskStatus", status, "productId", productId);
                // 波次主表：waveNo/status（无任务号字段语义，taskNumber 即波次号）
                case "wave" -> filterParams("waveNo", taskNumber, "status", status);
                // 缺货登记：status/productId（无任务号字段）
                default -> filterParams("status", status, "productId", productId);
            };
            query.putAll(paging(pageNo, pageSize));
            return pageJson(client.list(endpoint, query), query, Map.of());
        } catch (WmsApiException e) {
            return errorJson(e.getMessage());
        } catch (Exception e) {
            return unexpected(e);
        }
    }

    /** 语义 taskType → jeecg 字典枚举（库中 taskType 列存大写 TASK 枚举，语义小写直传会恒 0 结果） */
    private static String jeecgTaskType(String type) {
        return switch (type) {
            case "receive" -> "RECEIVE_TASK";
            case "putaway" -> "PUTAWAY_TASK";
            case "picking" -> "PICKING_TASK";
            default -> type;
        };
    }

    // ---------- 结果装配 ----------

    /** 分页结果统一装配：条件回显 + 总数 + 记录 + 空结果提示 + 附加信息（如组合查询匹配到的商品） */
    private String pageJson(WmsPage page, Map<String, String> query, Map<String, Object> extra) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", query);
        out.put("total", page.total());
        out.put("recordCount", page.records().size());
        out.put("records", page.records());
        if (page.records().isEmpty()) {
            out.put("hint", "未查询到符合条件的记录");
        }
        out.putAll(extra);
        return toJson(out);
    }

    /** 拼装过滤参数：跳过 null/空白值（jeecg 字段等值过滤，传了就参与过滤） */
    private static Map<String, String> filterParams(Object... kvPairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kvPairs.length; i += 2) {
            Object value = kvPairs[i + 1];
            if (value != null && !String.valueOf(value).isBlank()) {
                map.put(String.valueOf(kvPairs[i]), String.valueOf(value));
            }
        }
        return map;
    }

    /**
     * 分页参数（jeecg list 必填 pageNo/pageSize）：pageNo 缺省 1；pageSize 强制钳制在 [1, 20]，
     * 缺省 20——超上限压回 20、非正数抬到 1，防止大页拉取撑爆工具结果。
     */
    private static Map<String, String> paging(Integer pageNo, Integer pageSize) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("pageNo", pageNo == null || pageNo < 1 ? DEFAULT_PAGE_NO : String.valueOf(pageNo));
        int size = pageSize == null ? DEFAULT_PAGE_SIZE : Math.max(1, Math.min(pageSize, MAX_PAGE_SIZE));
        map.put("pageSize", String.valueOf(size));
        return map;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /** 结构化错误文本：{"error":true,"message":...}，不抛崩 */
    private String errorJson(String message) {
        ObjectNode node = mapper.createObjectNode();
        node.put("error", true);
        node.put("message", message);
        return toJson(node);
    }

    /** 未预期异常兜底：同样转结构化文本，保证工具调用不向上抛崩 */
    private String unexpected(Exception e) {
        log.warn("[wms-tool] 工具执行异常", e);
        return errorJson("工具执行失败：" + e.getMessage());
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{\"error\":true,\"message\":\"工具结果序列化失败\"}";
        }
    }

    /** 把 @Tool 方法注册为 ToolCallbackProvider，供 MCP server 端点暴露（对齐 harness McpServerTools 模式） */
    @Configuration
    public static class WmsToolConfiguration {
        @Bean
        ToolCallbackProvider wmsToolCallbackProvider(WmsTools tools) {
            return MethodToolCallbackProvider.builder()
                    .toolObjects(tools)
                    .build();
        }
    }
}
