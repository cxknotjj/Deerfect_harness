package com.dark.wms.tool;

import com.dark.wms.client.WmsApiException;
import com.dark.wms.client.WmsEndpoints;
import com.dark.wms.client.WmsPage;
import com.dark.wms.client.WmsRestClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 工具 handler 单测：mock WMS REST 客户端，覆盖正常/空结果/非 2xx/超时，
 * 以及组合查询（productName → productId）与聚合工具（主表+子表一次返回）。
 */
@ExtendWith(MockitoExtension.class)
class WmsToolsTest {

    @Mock
    private WmsRestClient client;

    private WmsTools tools;

    @BeforeEach
    void setUp() {
        tools = new WmsTools(client, new ObjectMapper());
    }

    @Test
    void searchStock_按productId直查_透传分页() {
        when(client.list(eq(WmsEndpoints.INVENTORY_LIST), anyMap()))
                .thenReturn(new WmsPage(List.of(Map.of("id", "i1", "stockQuantity", 5)), 1));
        String out = tools.searchStock("p1", null, null, null, null, null, null);
        assertThat(out).doesNotContain("\"error\":true").contains("\"stockQuantity\":5");
        verify(client).list(eq(WmsEndpoints.INVENTORY_LIST),
                argThat(m -> "p1".equals(m.get("productId")) && "1".equals(m.get("pageNo")) && "20".equals(m.get("pageSize"))));
    }

    @Test
    void searchStock_按productName组合查询_先换productId再查库存() {
        when(client.list(eq(WmsEndpoints.PRODUCTS_LIST), anyMap()))
                .thenReturn(new WmsPage(List.of(
                        Map.of("id", "p9", "productName", "红牛"),
                        Map.of("id", "p10", "productName", "红牛整箱")), 2));
        when(client.list(eq(WmsEndpoints.INVENTORY_LIST), anyMap()))
                .thenReturn(new WmsPage(List.of(Map.of("id", "i1")), 1));
        String out = tools.searchStock(null, "红牛", null, null, null, null, null);
        InOrder inOrder = inOrder(client);
        inOrder.verify(client).list(eq(WmsEndpoints.PRODUCTS_LIST), anyMap());
        inOrder.verify(client).list(eq(WmsEndpoints.INVENTORY_LIST),
                argThat(m -> "p9".equals(m.get("productId"))));
        // 多匹配时取第一个，并在结果中回显匹配到的商品供核对（组合行为对模型透明）
        assertThat(out).contains("\"matchedProductId\":\"p9\"").contains("\"matchedProductName\":\"红牛\"");
    }

    @Test
    void searchStock_productName无匹配_不再查库存() {
        when(client.list(eq(WmsEndpoints.PRODUCTS_LIST), anyMap())).thenReturn(new WmsPage(List.of(), 0));
        String out = tools.searchStock(null, "不存在的商品", null, null, null, null, null);
        assertThat(out).contains("\"error\":true").contains("未找到");
        verify(client, never()).list(eq(WmsEndpoints.INVENTORY_LIST), anyMap());
    }

    @Test
    void searchStock_无任何条件_返回引导性错误() {
        String out = tools.searchStock(null, null, null, null, null, null, null);
        assertThat(out).contains("\"error\":true").contains("请至少提供一个查询条件");
        verifyNoInteractions(client);
    }

    @Test
    void searchStock_空结果_带提示() {
        when(client.list(eq(WmsEndpoints.INVENTORY_LIST), anyMap())).thenReturn(new WmsPage(List.of(), 0));
        String out = tools.searchStock("p1", null, null, null, null, null, null);
        assertThat(out).contains("未查询到符合条件的记录").doesNotContain("\"error\":true");
    }

    @Test
    void searchStock_WMS鉴权失败_返回结构化错误不抛崩() {
        when(client.list(eq(WmsEndpoints.INVENTORY_LIST), anyMap()))
                .thenThrow(new WmsApiException("WMS 鉴权失败（HTTP 401）：请检查 WMS_API_TOKEN 与鉴权头名称配置"));
        String out = tools.searchStock("p1", null, null, null, null, null, null);
        assertThat(out).contains("\"error\":true").contains("鉴权失败");
    }

    @Test
    void searchStock_WMS超时_返回结构化错误不抛崩() {
        when(client.list(eq(WmsEndpoints.INVENTORY_LIST), anyMap()))
                .thenThrow(new WmsApiException("WMS 请求超时：WMS 响应过慢或网络不稳定，请稍后重试"));
        String out = tools.searchStock("p1", null, null, null, null, null, null);
        assertThat(out).contains("\"error\":true").contains("请求超时");
    }

    @Test
    void searchStock_未预期异常_同样兜底为错误文本() {
        when(client.list(anyString(), anyMap())).thenThrow(new RuntimeException("boom"));
        String[] holder = new String[1];
        assertThatCode(() -> holder[0] = tools.searchStock("p1", null, null, null, null, null, null))
                .doesNotThrowAnyException();
        assertThat(holder[0]).contains("\"error\":true").contains("工具执行失败");
    }

    @Test
    void getStockInOrder_主表加明细一次返回() {
        when(client.one(WmsEndpoints.STOCK_IN_ORDERS_BY_ID, "o1"))
                .thenReturn(Map.of("orderNumber", "RK001", "status", "部分收货"));
        when(client.children(WmsEndpoints.STOCK_IN_ORDER_ITEMS, "o1"))
                .thenReturn(List.of(Map.of("productName", "红牛", "expectedQuantity", 10)));
        String out = tools.getStockInOrder("o1");
        assertThat(out).contains("RK001").contains("红牛").contains("\"items\"").doesNotContain("\"error\":true");
    }

    @Test
    void getStockInOrder_未找到_带提示() {
        when(client.one(WmsEndpoints.STOCK_IN_ORDERS_BY_ID, "o404")).thenReturn(Map.of());
        when(client.children(WmsEndpoints.STOCK_IN_ORDER_ITEMS, "o404")).thenReturn(List.of());
        String out = tools.getStockInOrder("o404");
        assertThat(out).contains("未找到该 id 对应的入库单");
    }

    @Test
    void getOutOrder_主表加明细加分配一次返回() {
        when(client.one(WmsEndpoints.OUT_ORDERS_BY_ID, "w1")).thenReturn(Map.of("orderNo", "CK001"));
        when(client.children(WmsEndpoints.OUT_ORDER_ITEMS, "w1")).thenReturn(List.of(Map.of("productId", "p1")));
        when(client.children(WmsEndpoints.OUT_ORDER_ALLOCATIONS, "w1")).thenReturn(List.of(Map.of("locationCode", "A-01")));
        String out = tools.getOutOrder("w1");
        assertThat(out).contains("CK001").contains("\"items\"").contains("\"allocations\"").contains("A-01");
    }

    @Test
    void getProduct_返回完整档案() {
        when(client.one(WmsEndpoints.PRODUCTS_BY_ID, "p1")).thenReturn(Map.of("productName", "测试商品"));
        String out = tools.getProduct("p1");
        assertThat(out).contains("测试商品").contains("\"product\"");
    }

    @Test
    void searchWarehouseNetwork_level分派与参数映射() {
        when(client.list(eq(WmsEndpoints.STORAGE_ZONES_LIST), anyMap())).thenReturn(new WmsPage(List.of(), 0));
        String out = tools.searchWarehouseNetwork("zone", "Z01", "干货区", "wh1", null, null, null, null);
        assertThat(out).doesNotContain("\"error\":true");
        verify(client).list(eq(WmsEndpoints.STORAGE_ZONES_LIST),
                argThat(m -> "Z01".equals(m.get("zoneCode")) && "干货区".equals(m.get("zoneName"))
                        && "wh1".equals(m.get("warehouseId")) && !m.containsKey("zoneId")));
    }

    @Test
    void searchWarehouseNetwork_非法level_返回引导性错误() {
        String out = tools.searchWarehouseNetwork("bad", null, null, null, null, null, null, null);
        assertThat(out).contains("\"error\":true").contains("level 须为");
        verifyNoInteractions(client);
    }

    @Test
    void searchTasks_type分派_波次用waveNo() {
        when(client.list(eq(WmsEndpoints.WAVE_MASTER_LIST), anyMap())).thenReturn(new WmsPage(List.of(), 0));
        String out = tools.searchTasks("wave", "WV001", null, "p1", null, null);
        assertThat(out).doesNotContain("\"error\":true");
        verify(client).list(eq(WmsEndpoints.WAVE_MASTER_LIST),
                argThat(m -> "WV001".equals(m.get("waveNo")) && !m.containsKey("taskNumber") && !m.containsKey("productId")));
    }

    @Test
    void searchTasks_收货任务映射taskType与taskStatus() {
        when(client.list(eq(WmsEndpoints.RECEIVE_TASKS_LIST), anyMap())).thenReturn(new WmsPage(List.of(), 0));
        tools.searchTasks("receive", "T001", "待执行", "p1", null, null);
        verify(client).list(eq(WmsEndpoints.RECEIVE_TASKS_LIST),
                argThat(m -> "T001".equals(m.get("taskNumber")) && "receive".equals(m.get("taskType"))
                        && "待执行".equals(m.get("taskStatus")) && "p1".equals(m.get("productId"))));
    }

    @Test
    void searchTasks_非法type_返回引导性错误() {
        String out = tools.searchTasks("bad", null, null, null, null, null);
        assertThat(out).contains("\"error\":true").contains("taskType 须为");
        verifyNoInteractions(client);
    }

    @Test
    void searchShipments_逐单附带包裹明细() {
        when(client.list(eq(WmsEndpoints.SHIPMENT_LIST), anyMap()))
                .thenReturn(new WmsPage(List.of(Map.of("id", "s1", "shipmentNo", "SF001")), 1));
        when(client.children(WmsEndpoints.SHIPMENT_DETAILS, "s1"))
                .thenReturn(List.of(Map.of("trackingNo", "SF123")));
        String out = tools.searchShipments(null, null, null, null, null, null);
        assertThat(out).contains("SF001").contains("SF123").contains("\"details\"");
    }

    @Test
    void pageSize_钳制在1到20之间() {
        when(client.list(eq(WmsEndpoints.INVENTORY_LIST), anyMap())).thenReturn(new WmsPage(List.of(), 0));

        // 传 100：实际发送 20（服务端上限）
        tools.searchStock("p1", null, null, null, null, 2, 100);
        verify(client).list(eq(WmsEndpoints.INVENTORY_LIST),
                argThat(m -> "2".equals(m.get("pageNo")) && "20".equals(m.get("pageSize"))));

        // 传 0：钳到下限 1
        tools.searchStock("p1", null, null, null, null, null, 0);
        verify(client).list(eq(WmsEndpoints.INVENTORY_LIST),
                argThat(m -> "1".equals(m.get("pageSize"))));

        // 缺省：默认 20（含第 1 次，累计 2 次 pageSize=20）
        tools.searchStock("p1", null, null, null, null, null, null);
        verify(client, times(2)).list(eq(WmsEndpoints.INVENTORY_LIST),
                argThat(m -> "20".equals(m.get("pageSize"))));
    }
}
