package com.dark.wms.tool;

import com.dark.wms.client.WmsEndpoints;
import com.dark.wms.client.WmsPage;
import com.dark.wms.client.WmsRestClient;
import com.dark.wms.client.WmsApiException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * WMS 只读 MCP 工具：对模型只暴露一个 {@code wms_query(resource, params)}，
 * 内部按资源分派到十段查询逻辑。
 *
 * <p>原先这里是十个独立工具（{@code wms_search_stock} / {@code wms_search_stock_in_orders} …），
 * 近义名字挤在工具面里，模型在「选哪个工具」这一步就开始出错；收敛后「查什么」变成资源枚举，
 * 「怎么查」放在 skills/wms/SKILL.md。{@link #ALLOWED} 是模型可见参数的唯一契约。
 *
 * <p>下面这些查询方法已移除 {@code @Tool}（不再是 MCP 工具），保留 {@code @ToolParam}
 * 仅作参数说明。
 *
 * <p>约定：
 * <ul>
 *   <li>全部只读 GET——写操作、exportXls、面单打印/发送、{@code /ai/*} 一律不包装</li>
 *   <li>错误以结构化 JSON 文本返回（{@code {"error":true,"message":...}}），不抛崩，MCP 会话保持存活</li>
 *   <li>组合查询：{@code resource=stock} 支持 productName，服务端先查商品表换 productId
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

    /**
     * 资源 → 允许的参数。未知参数直接拒绝，而不是静默忽略——
     * 忽略会让「字段名写错」变成「返回全量数据」，比报错更难发现。
     */
    private static final Map<String, Set<String>> ALLOWED = new LinkedHashMap<>();

    static {
        ALLOWED.put("stock", Set.of("productId", "productName", "locationCode", "batchNumber",
                "containerCode", "pageNo", "pageSize"));
        ALLOWED.put("product", Set.of("productName", "productCode", "productBarcode",
                "productBrand", "ownerId", "pageNo", "pageSize"));
        ALLOWED.put("product_detail", Set.of("id"));
        ALLOWED.put("stock_in_order", Set.of("orderNumber", "orderType", "status",
                "expectedArrivalTime", "pageNo", "pageSize"));
        ALLOWED.put("stock_in_order_detail", Set.of("id"));
        ALLOWED.put("out_order", Set.of("orderNo", "orderType", "status", "warehouseId",
                "ownerId", "pageNo", "pageSize"));
        ALLOWED.put("out_order_detail", Set.of("id"));
        ALLOWED.put("shipment", Set.of("shipmentNo", "trackingNo", "orderNo", "status",
                "pageNo", "pageSize"));
        ALLOWED.put("warehouse", Set.of("code", "name", "status", "pageNo", "pageSize"));
        ALLOWED.put("storage_zone", Set.of("code", "name", "warehouseId", "status",
                "pageNo", "pageSize"));
        ALLOWED.put("storage_location", Set.of("code", "warehouseId", "zoneId", "status",
                "pageNo", "pageSize"));
        ALLOWED.put("receive_task", Set.of("taskNumber", "status", "productId", "pageNo", "pageSize"));
        ALLOWED.put("putaway_task", Set.of("taskNumber", "status", "productId", "pageNo", "pageSize"));
        ALLOWED.put("picking_task", Set.of("taskNumber", "status", "productId", "pageNo", "pageSize"));
        ALLOWED.put("wave", Set.of("waveNo", "status", "pageNo", "pageSize"));
        ALLOWED.put("shortage", Set.of("status", "productId", "pageNo", "pageSize"));
    }

    /** 可用资源名（供测试与运维核对） */
    public static Set<String> resources() {
        return ALLOWED.keySet();
    }

    /** 参数契约（守护测试核对：每个资源都应有白名单） */
    static Map<String, Set<String>> contract() {
        return ALLOWED;
    }

    /**
     * 唯一对模型暴露的工具：resource 决定查什么，params 传过滤条件与分页。
     *
     * <p>params 用 Object 而不是 Map：实测有的模型会把嵌套对象序列化成字符串
     * （{@code "params":"{\"productId\":\"p1\"}"}），声明成 Map 会在绑定阶段直接失败。
     */
    @Tool(name = "wms_query",
            description = "查询 WMS 仓储只读数据的唯一入口。resource 决定查什么"
                    + "（库存/商品/出入库单/发运/仓库网络/作业任务），params 传过滤条件与分页"
                    + "（pageNo/pageSize，服务端上限 20）。各 resource 支持的参数与查询套路见"
                    + " skill「wms」——不确定时先读它，不要猜参数")
    public String query(
            @ToolParam(description = "资源类型：stock / product / product_detail / stock_in_order / "
                    + "stock_in_order_detail / out_order / out_order_detail / shipment / warehouse / "
                    + "storage_zone / storage_location / receive_task / putaway_task / picking_task / "
                    + "wave / shortage") String resource,
            @ToolParam(description = "JSON 对象（键值对）：分页 pageNo/pageSize，其余字段见 skill「wms」；"
                    + "不确定就只传 resource", required = false) Object params) {
        String name = resource == null ? "" : resource.trim().toLowerCase(Locale.ROOT);
        Set<String> allowed = ALLOWED.get(name);
        if (allowed == null) {
            return errorJson("未知 resource=" + resource + "；可选：" + String.join(" / ", ALLOWED.keySet()));
        }

        Map<String, Object> input = new LinkedHashMap<>();
        if (params instanceof Map<?, ?> map) {
            map.forEach((key, value) -> input.put(String.valueOf(key), value));
        } else if (params instanceof String text) {
            try {
                Map<?, ?> parsed = mapper.readValue(text, Map.class);
                if (parsed != null) {
                    parsed.forEach((key, value) -> input.put(String.valueOf(key), value));
                }
            } catch (JacksonException e) {
                return errorJson("params 不是合法 JSON 对象");
            }
        } else if (params != null) {
            return errorJson("params 必须是对象（键值对）");
        }

        List<String> unknown = input.keySet().stream().filter(key -> !allowed.contains(key)).toList();
        if (!unknown.isEmpty()) {
            return errorJson(name + " 不支持参数 " + unknown + "；可用：" + allowed);
        }

        return switch (name) {
            case "stock" -> searchStock(text(input, "productId"), text(input, "productName"),
                    text(input, "locationCode"), text(input, "batchNumber"), text(input, "containerCode"),
                    number(input, "pageNo"), number(input, "pageSize"));
            case "product" -> searchProducts(text(input, "productName"), text(input, "productCode"),
                    text(input, "productBarcode"), text(input, "productBrand"), text(input, "ownerId"),
                    number(input, "pageNo"), number(input, "pageSize"));
            case "product_detail" -> requireId(name, input, this::getProduct);
            case "stock_in_order" -> searchStockInOrders(text(input, "orderNumber"),
                    text(input, "orderType"), text(input, "status"), text(input, "expectedArrivalTime"),
                    number(input, "pageNo"), number(input, "pageSize"));
            case "stock_in_order_detail" -> requireId(name, input, this::getStockInOrder);
            case "out_order" -> searchOutOrders(text(input, "orderNo"), text(input, "orderType"),
                    text(input, "status"), text(input, "warehouseId"), text(input, "ownerId"),
                    number(input, "pageNo"), number(input, "pageSize"));
            case "out_order_detail" -> requireId(name, input, this::getOutOrder);
            case "shipment" -> searchShipments(text(input, "shipmentNo"), text(input, "trackingNo"),
                    text(input, "orderNo"), text(input, "status"),
                    number(input, "pageNo"), number(input, "pageSize"));
            case "warehouse" -> searchWarehouseNetwork("warehouse", text(input, "code"),
                    text(input, "name"), null, null, text(input, "status"),
                    number(input, "pageNo"), number(input, "pageSize"));
            case "storage_zone" -> searchWarehouseNetwork("zone", text(input, "code"),
                    text(input, "name"), text(input, "warehouseId"), null, text(input, "status"),
                    number(input, "pageNo"), number(input, "pageSize"));
            case "storage_location" -> searchWarehouseNetwork("location", text(input, "code"),
                    null, text(input, "warehouseId"), text(input, "zoneId"), text(input, "status"),
                    number(input, "pageNo"), number(input, "pageSize"));
            case "receive_task" -> searchTasks("receive", text(input, "taskNumber"),
                    text(input, "status"), text(input, "productId"),
                    number(input, "pageNo"), number(input, "pageSize"));
            case "putaway_task" -> searchTasks("putaway", text(input, "taskNumber"),
                    text(input, "status"), text(input, "productId"),
                    number(input, "pageNo"), number(input, "pageSize"));
            case "picking_task" -> searchTasks("picking", text(input, "taskNumber"),
                    text(input, "status"), text(input, "productId"),
                    number(input, "pageNo"), number(input, "pageSize"));
            case "wave" -> searchTasks("wave", text(input, "waveNo"),
                    text(input, "status"), null, number(input, "pageNo"), number(input, "pageSize"));
            case "shortage" -> searchTasks("shortage", null,
                    text(input, "status"), text(input, "productId"),
                    number(input, "pageNo"), number(input, "pageSize"));
            default -> errorJson("未知 resource=" + resource);
        };
    }

    /** 详情类资源统一校验 id，缺了给出可纠正的错误而不是查空 */
    private String requireId(String resource, Map<String, Object> input,
                             java.util.function.Function<String, String> lookup) {
        String id = text(input, "id");
        return id == null || id.isBlank()
                ? errorJson(resource + " 缺少必填参数 id")
                : lookup.apply(id);
    }

    private static String text(Map<String, Object> params, String key) {
        Object value = params.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static Integer number(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value instanceof Number num) {
            return num.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---------- 1. 库存（组合查询：productName → productId 透明换查） ----------

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
        } catch (JacksonException e) {
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
