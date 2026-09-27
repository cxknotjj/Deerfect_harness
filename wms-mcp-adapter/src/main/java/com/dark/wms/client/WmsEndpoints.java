package com.dark.wms.client;

import java.util.List;

/**
 * WMS OpenAPI 端点路径常量：首批 10 个只读工具的全部映射端点（GET）。
 *
 * <p>只读守护的结构性保证之一：本类仅收录只读 GET 端点——写操作、exportXls 文件流导出、
 * 面单打印/发送、{@code /ai/*} 一律不得加入（守护测试 {@code WmsToolListGuardTest} 校验）。
 * 路径与 spec.md 附录 A 定稿一致（源自仓库根 wms-openapi.json）。
 */
public final class WmsEndpoints {

    /** 商品检索 */
    public static final String PRODUCTS_LIST = "/goods/wmsProducts/list";
    /** 商品详情 */
    public static final String PRODUCTS_BY_ID = "/goods/wmsProducts/queryById";
    /** 库存检索 */
    public static final String INVENTORY_LIST = "/inventory/wmsInventory/list";
    /** 入库单检索 */
    public static final String STOCK_IN_ORDERS_LIST = "/inorder/wmsStockInOrders/list";
    /** 入库单详情（主表） */
    public static final String STOCK_IN_ORDERS_BY_ID = "/inorder/wmsStockInOrders/queryById";
    /** 入库单明细（子表） */
    public static final String STOCK_IN_ORDER_ITEMS = "/inorder/wmsStockInOrders/queryWmsStockInOrderItemsByMainId";
    /** 出库单检索 */
    public static final String OUT_ORDERS_LIST = "/outorder/wmsOutOrders/list";
    /** 出库单详情（主表） */
    public static final String OUT_ORDERS_BY_ID = "/outorder/wmsOutOrders/queryById";
    /** 出库单明细（子表） */
    public static final String OUT_ORDER_ITEMS = "/outorder/wmsOutOrders/queryWmsOutOrdersItemsByMainId";
    /** 出库单分配明细（子表） */
    public static final String OUT_ORDER_ALLOCATIONS = "/outorder/wmsOutOrders/queryWmsOutOrdersAllocationByMainId";
    /** 包裹/发运检索 */
    public static final String SHIPMENT_LIST = "/shipment/wmsShipment/list";
    /** 包裹明细（子表） */
    public static final String SHIPMENT_DETAILS = "/shipment/wmsShipment/queryWmsShipmentDetailByMainId";
    /** 仓库检索 */
    public static final String WAREHOUSES_LIST = "/warehouse/wmsWarehouses/list";
    /** 库区检索 */
    public static final String STORAGE_ZONES_LIST = "/warehouse/wmsStorageZones/list";
    /** 库位检索 */
    public static final String STORAGE_LOCATIONS_LIST = "/warehouse/wmsStorageLocations/list";
    /** 收货任务检索 */
    public static final String RECEIVE_TASKS_LIST = "/inorder/receiveTasks/list";
    /** 上架任务检索 */
    public static final String PUTAWAY_TASKS_LIST = "/inorder/putawayTasks/list";
    /** 拣货任务检索 */
    public static final String PICKING_TASKS_LIST = "/wave/pickingTasks/list";
    /** 波次检索 */
    public static final String WAVE_MASTER_LIST = "/wave/wmsWaveMaster/list";
    /** 缺货登记检索 */
    public static final String SHORTAGE_LIST = "/wave/wmsShortageRegistration/list";

    private WmsEndpoints() {
    }

    /** 全部映射端点（供守护测试白名单校验：新增端点必须显式过白名单评审） */
    public static List<String> all() {
        return List.of(PRODUCTS_LIST, PRODUCTS_BY_ID, INVENTORY_LIST,
                STOCK_IN_ORDERS_LIST, STOCK_IN_ORDERS_BY_ID, STOCK_IN_ORDER_ITEMS,
                OUT_ORDERS_LIST, OUT_ORDERS_BY_ID, OUT_ORDER_ITEMS, OUT_ORDER_ALLOCATIONS,
                SHIPMENT_LIST, SHIPMENT_DETAILS,
                WAREHOUSES_LIST, STORAGE_ZONES_LIST, STORAGE_LOCATIONS_LIST,
                RECEIVE_TASKS_LIST, PUTAWAY_TASKS_LIST, PICKING_TASKS_LIST,
                WAVE_MASTER_LIST, SHORTAGE_LIST);
    }
}
