package com.dark.wms.tool;

import com.dark.wms.client.WmsEndpoints;
import com.dark.wms.client.WmsRestClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * tools/list 只读守护断言：MCP 暴露的工具集合必须恰好等于 spec 附录 A 定稿的
 * 10 个只读工具名单——防写操作工具混入；映射端点必须全部落在只读白名单内
 * （写操作 / exportXls / 面单打印与发送 / {@code /ai/*} 一律不得出现）。
 */
class WmsToolListGuardTest {

    /** spec 附录 A 定稿：首批 10 个只读工具 */
    private static final Set<String> READONLY_TOOLS = Set.of(
            "wms_search_stock", "wms_search_products", "wms_get_product",
            "wms_search_stock_in_orders", "wms_get_stock_in_order",
            "wms_search_out_orders", "wms_get_out_order",
            "wms_search_shipments", "wms_search_warehouse_network", "wms_search_tasks");

    /** 与 WmsEndpoints.all() 一致的只读端点白名单（新增端点必须显式过此处评审） */
    private static final Set<String> READONLY_ENDPOINT_ALLOWLIST = Set.of(
            "/goods/wmsProducts/list", "/goods/wmsProducts/queryById",
            "/inventory/wmsInventory/list",
            "/inorder/wmsStockInOrders/list", "/inorder/wmsStockInOrders/queryById",
            "/inorder/wmsStockInOrders/queryWmsStockInOrderItemsByMainId",
            "/outorder/wmsOutOrders/list", "/outorder/wmsOutOrders/queryById",
            "/outorder/wmsOutOrders/queryWmsOutOrdersItemsByMainId",
            "/outorder/wmsOutOrders/queryWmsOutOrdersAllocationByMainId",
            "/shipment/wmsShipment/list", "/shipment/wmsShipment/queryWmsShipmentDetailByMainId",
            "/warehouse/wmsWarehouses/list", "/warehouse/wmsStorageZones/list", "/warehouse/wmsStorageLocations/list",
            "/inorder/receiveTasks/list", "/inorder/putawayTasks/list", "/wave/pickingTasks/list",
            "/wave/wmsWaveMaster/list", "/wave/wmsShortageRegistration/list");

    /** 排除面关键词：工具名/端点出现即视为写操作、文件流导出、面单动作或 WMS 内置 AI 混入 */
    private static final List<String> FORBIDDEN_FRAGMENTS = List.of(
            "add", "edit", "delete", "audit", "submit", "create", "complete", "print", "send",
            "import", "exportxls", "/ai/");

    private static Set<String> exposedToolNames() {
        WmsTools tools = new WmsTools(mock(WmsRestClient.class), new ObjectMapper());
        ToolCallbackProvider provider = MethodToolCallbackProvider.builder().toolObjects(tools).build();
        return Arrays.stream(provider.getToolCallbacks())
                .map(ToolCallback::getToolDefinition)
                .map(def -> def.name())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    void 暴露的工具恰好等于10个只读工具名单() {
        assertThat(exposedToolNames()).containsExactlyInAnyOrderElementsOf(READONLY_TOOLS);
    }

    @Test
    void 工具名全部带wms前缀且无写操作语义() {
        Set<String> names = exposedToolNames();
        assertThat(names).isNotEmpty().allMatch(n -> n.startsWith("wms_"));
        names.forEach(name ->
                FORBIDDEN_FRAGMENTS.forEach(f ->
                        assertThat(name.toLowerCase()).as("工具名 %s 不应包含 %s", name, f).doesNotContain(f)));
    }

    @Test
    void 映射端点全部落在只读白名单内() {
        assertThat(WmsEndpoints.all()).allSatisfy(ep -> assertThat(READONLY_ENDPOINT_ALLOWLIST).contains(ep));
    }

    @Test
    void 端点白名单无写操作与排除面() {
        WmsEndpoints.all().forEach(ep ->
                FORBIDDEN_FRAGMENTS.forEach(f ->
                        assertThat(ep.toLowerCase()).as("端点 %s 不应包含 %s", ep, f).doesNotContain(f)));
    }
}
