package com.dark.wms.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dark.wms.client.WmsEndpoints;
import com.dark.wms.client.WmsPage;
import com.dark.wms.client.WmsRestClient;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import tools.jackson.databind.ObjectMapper;

/**
 * 分发器契约：一个工具 + 资源枚举 + 参数白名单。
 *
 * <p>这些用例替代了原来「十个工具各自一个方法」的隐式契约——现在工具面只有一个入口，
 * 分派错误、参数散落、必填项漏判都会直接变成模型看到的错误文本，必须在这里拦住。
 */
@ExtendWith(MockitoExtension.class)
class WmsQueryDispatchTest {

    @Mock
    private WmsRestClient client;

    private WmsTools tools;

    @BeforeEach
    void setUp() {
        tools = new WmsTools(client, new ObjectMapper());
    }

    @Test
    void 只暴露一个工具且带resource枚举() {
        var provider = MethodToolCallbackProvider.builder().toolObjects(tools).build();
        ToolCallback[] callbacks = provider.getToolCallbacks();

        assertThat(callbacks).hasSize(1);
        assertThat(callbacks[0].getToolDefinition().name()).isEqualTo("wms_query");
        assertThat(callbacks[0].getToolDefinition().inputSchema())
                .contains("\"resource\"").contains("\"params\"");
    }

    @Test
    void query_全部资源都能分派且不报错() {
        org.mockito.Mockito.lenient().when(client.list(anyString(), anyMap()))
                .thenReturn(new WmsPage(List.of(), 0));
        org.mockito.Mockito.lenient().when(client.one(anyString(), anyString())).thenReturn(Map.of());
        org.mockito.Mockito.lenient().when(client.children(anyString(), anyString())).thenReturn(List.of());

        Map<String, Map<String, Object>> cases = new java.util.LinkedHashMap<>();
        cases.put("stock", Map.of("productId", "p1"));
        cases.put("product", Map.of("productName", "x"));
        cases.put("product_detail", Map.of("id", "1"));
        cases.put("stock_in_order", Map.of("orderNumber", "o1"));
        cases.put("stock_in_order_detail", Map.of("id", "1"));
        cases.put("out_order", Map.of("orderNo", "o1"));
        cases.put("out_order_detail", Map.of("id", "1"));
        cases.put("shipment", Map.of("shipmentNo", "s1"));
        cases.put("warehouse", Map.of("code", "W1"));
        cases.put("storage_zone", Map.of("warehouseId", "w1"));
        cases.put("storage_location", Map.of("zoneId", "z1"));
        cases.put("receive_task", Map.of("taskNumber", "T1"));
        cases.put("putaway_task", Map.of("taskNumber", "T1"));
        cases.put("picking_task", Map.of("taskNumber", "T1"));
        cases.put("wave", Map.of("waveNo", "WV1"));
        cases.put("shortage", Map.of("status", "OPEN"));

        cases.forEach((resource, params) -> assertThat(tools.query(resource, params))
                .as("resource=%s", resource)
                .doesNotContain("\"error\":true"));
    }

    @Test
    void query_非法params形态_给出可纠正错误() {
        assertThat(tools.query("stock", 123)).contains("必须是对象");
        assertThat(tools.query("stock", "{bad json")).contains("不是合法 JSON 对象");
        verifyNoInteractions(client);
    }

    @Test
    void query_字符串数字参数按整数解析() {
        when(client.list(eq(WmsEndpoints.INVENTORY_LIST), anyMap()))
                .thenReturn(new WmsPage(List.of(), 0));

        tools.query("stock", Map.of("productId", "p1", "pageNo", "2", "pageSize", "abc"));

        verify(client).list(eq(WmsEndpoints.INVENTORY_LIST),
                argThat(m -> "2".equals(m.get("pageNo")) && "20".equals(m.get("pageSize"))));
    }

    @Test
    void query_按资源分派_库存组合查询仍然透明() {
        when(client.list(eq(WmsEndpoints.PRODUCTS_LIST), anyMap()))
                .thenReturn(new WmsPage(List.of(Map.of("id", "p9", "productName", "红牛")), 1));
        when(client.list(eq(WmsEndpoints.INVENTORY_LIST), anyMap()))
                .thenReturn(new WmsPage(List.of(Map.of("id", "i1", "stockQuantity", 5)), 1));

        String out = tools.query("stock", Map.of("productName", "红牛"));

        assertThat(out).contains("\"matchedProductId\":\"p9\"").contains("\"stockQuantity\":5");
    }

    @Test
    void query_params是JSON字符串也能解析() {
        when(client.list(eq(WmsEndpoints.INVENTORY_LIST), anyMap()))
                .thenReturn(new WmsPage(List.of(Map.of("id", "i1")), 1));

        String out = tools.query("stock", "{\"productId\":\"p1\",\"pageNo\":2}");

        assertThat(out).doesNotContain("\"error\":true");
        verify(client).list(eq(WmsEndpoints.INVENTORY_LIST),
                argThat(m -> "p1".equals(m.get("productId")) && "2".equals(m.get("pageNo"))));
    }

    @Test
    void query_未知资源_列出可选值且不发请求() {
        String out = tools.query("nope", Map.of());

        assertThat(out).contains("未知 resource").contains("stock");
        verifyNoInteractions(client);
    }

    @Test
    void query_未知参数_直接拒绝且不发请求() {
        String out = tools.query("stock", Map.of("foo", "1"));

        assertThat(out).contains("不支持参数").contains("productId");
        verifyNoInteractions(client);
    }

    @Test
    void query_详情缺id_给出可纠正错误() {
        String out = tools.query("product_detail", Map.of());

        assertThat(out).contains("缺少必填参数 id");
        verifyNoInteractions(client);
    }

    @Test
    void query_仓库网络_层级由资源固定() {
        when(client.list(eq(WmsEndpoints.STORAGE_ZONES_LIST), anyMap()))
                .thenReturn(new WmsPage(List.of(Map.of("id", "z1")), 1));

        tools.query("storage_zone", Map.of("code", "Z01", "warehouseId", "wh1"));

        verify(client).list(eq(WmsEndpoints.STORAGE_ZONES_LIST),
                argThat(m -> "Z01".equals(m.get("zoneCode")) && "wh1".equals(m.get("warehouseId"))));
    }

    @Test
    void query_任务类_服务端补jeecg枚举() {
        when(client.list(eq(WmsEndpoints.RECEIVE_TASKS_LIST), anyMap()))
                .thenReturn(new WmsPage(List.of(Map.of("id", "t1")), 1));

        tools.query("receive_task", Map.of("taskNumber", "T001"));

        verify(client).list(eq(WmsEndpoints.RECEIVE_TASKS_LIST),
                argThat(m -> "RECEIVE_TASK".equals(m.get("taskType")) && "T001".equals(m.get("taskNumber"))));
    }

    @Test
    void 资源与参数契约_覆盖全部只读面() {
        assertThat(WmsTools.resources()).hasSize(16);
        assertThat(WmsTools.resources()).contains("stock", "product", "out_order", "shipment",
                "warehouse", "storage_location", "receive_task", "shortage");
    }

    @Test
    void 每个资源都有非空参数白名单() {
        assertThat(WmsTools.contract()).isNotEmpty();
        WmsTools.contract().forEach((resource, params) ->
                assertThat(params).as("资源 %s 的参数白名单", resource).isNotEmpty());
    }

    @Test
    void 暴露的工具名与资源名不含写操作语义() {
        List<String> forbidden = List.of("add", "edit", "delete", "audit", "submit", "create",
                "complete", "print", "send", "import", "exportxls", "/ai/");
        List<String> names = new java.util.ArrayList<>(WmsTools.resources());
        names.add("wms_query");
        names.forEach(name -> forbidden.forEach(fragment ->
                assertThat(name.toLowerCase()).as("%s 不应包含 %s", name, fragment).doesNotContain(fragment)));
        assertThat(Arrays.stream(MethodToolCallbackProvider.builder().toolObjects(tools).build()
                        .getToolCallbacks())
                .map(callback -> callback.getToolDefinition().name()))
                .containsExactly("wms_query");
    }
}
