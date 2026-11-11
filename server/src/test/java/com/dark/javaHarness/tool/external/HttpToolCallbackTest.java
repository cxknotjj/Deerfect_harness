package com.dark.javaHarness.tool.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class HttpToolCallbackTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<String> lastUrl = new AtomicReference<>();
    private final AtomicReference<Map<String, String>> lastHeaders = new AtomicReference<>();

    @Test
    void schema_containsResourceEnumAndRequired() {
        String schema = callback("http://wms", "raw", 6000, 200, "{}")
                .getToolDefinition().inputSchema();

        assertTrue(schema.contains("\"enum\":[\"stock\",\"detail\"]"), schema);
        assertTrue(schema.contains("\"required\":[\"resource\"]"), schema);
    }

    @Test
    void call_paramsAsJsonString_areParsedNotDropped() {
        // 真实模型（DeepSeek）会把嵌套对象序列化成字符串，参数不能因此丢失
        String out = callback("http://wms", "raw", 6000, 200, "{\"ok\":true}")
                .call("{\"resource\":\"stock\",\"params\":\"{\\\"productId\\\":\\\"p1\\\",\\\"pageNo\\\":1}\"}");

        assertEquals("{\"ok\":true}", out);
        assertTrue(lastUrl.get().contains("productId=p1"), lastUrl.get());
        assertTrue(lastUrl.get().contains("pageNo=1"), lastUrl.get());
    }

    @Test
    void call_paramsAsNonObject_isRejected() {
        String out = callback("http://wms", "raw", 6000, 200, "{}")
                .call("{\"resource\":\"stock\",\"params\":123}");

        assertTrue(out.contains("必须是对象"), out);
        assertNull(lastUrl.get());
    }

    @Test
    void call_unknownResource_listsChoices() {
        String out = callback("http://wms", "raw", 6000, 200, "{}")
                .call("{\"resource\":\"nope\"}");

        assertTrue(out.contains("未知"), out);
        assertTrue(out.contains("stock"), out);
        assertNull(lastUrl.get(), "参数不合法不该发出请求");
    }

    @Test
    void call_unknownParam_listsAllowed() {
        String out = callback("http://wms", "raw", 6000, 200, "{}")
                .call("{\"resource\":\"stock\",\"params\":{\"foo\":1}}");

        assertTrue(out.contains("不支持参数"), out);
        assertTrue(out.contains("productId"), "应列出可用参数：" + out);
        assertNull(lastUrl.get());
    }

    @Test
    void call_missingRequiredParam() {
        String out = callback("http://wms", "raw", 6000, 200, "{}")
                .call("{\"resource\":\"detail\",\"params\":{}}");

        assertTrue(out.contains("缺少必填参数 id"), out);
    }

    @Test
    void call_missingBaseUrl_givesActionableHint() {
        String out = callback("", "raw", 6000, 200, "{}")
                .call("{\"resource\":\"stock\",\"params\":{\"productId\":\"p1\"}}");

        assertTrue(out.contains("WMS_API_BASE"), out);
        assertTrue(out.contains("未配置"), out);
    }

    @Test
    void call_buildsUrlWithEncodedParamsAndAuthHeader() {
        String out = callback("http://wms/", "raw", 6000, 200, "{\"ok\":true}")
                .call("{\"resource\":\"stock\",\"params\":{\"productId\":\"商品 A\",\"pageNo\":2}}");

        assertEquals("{\"ok\":true}", out);
        assertTrue(lastUrl.get().startsWith("http://wms/inventory/list?"), lastUrl.get());
        assertTrue(lastUrl.get().contains("productId=%E5%95%86%E5%93%81+A"), lastUrl.get());
        assertTrue(lastUrl.get().contains("pageNo=2"), lastUrl.get());
        assertEquals("tok", lastHeaders.get().get("X-Access-Token"));
    }

    @Test
    void call_fixedParamsAreAlwaysAdded() {
        callback("http://wms", "raw", 6000, 200, "{}")
                .call("{\"resource\":\"detail\",\"params\":{\"id\":\"9\"}}");

        assertTrue(lastUrl.get().contains("taskType=RECEIVE_TASK"), lastUrl.get());
        assertTrue(lastUrl.get().contains("id=9"), lastUrl.get());
    }

    @Test
    void call_jeecgUnwrapsDropsAuditFieldsAndKeepsTotal() {
        String body = """
                {"success":true,"result":{"total":2,"records":[
                  {"id":1,"name":"a","createBy":"u1","updateTime":"t1"},
                  {"id":2,"name":"b"}]}}
                """;

        String out = callback("http://wms", "jeecg", 6000, 200, body)
                .call("{\"resource\":\"stock\"}");

        assertTrue(out.contains("\"total\":2"), out);
        assertTrue(out.contains("\"name\":\"a\""), out);
        assertTrue(out.contains("\"name\":\"b\""), out);
        assertFalse(out.contains("createBy"), "审计字段应剔除：" + out);
        assertFalse(out.contains("updateTime"), out);
    }

    @Test
    void call_jeecgSingleObject_isReturnedAsRow() {
        String out = callback("http://wms", "jeecg", 6000, 200,
                "{\"result\":{\"id\":7,\"productName\":\"螺丝\",\"createTime\":\"x\"}}")
                .call("{\"resource\":\"detail\",\"params\":{\"id\":\"7\"}}");

        assertTrue(out.contains("\"productName\":\"螺丝\""), out);
        assertFalse(out.contains("createTime"), out);
    }

    @Test
    void call_truncatesLongOutput() {
        String out = callback("http://wms", "raw", 20, 200, "x".repeat(100))
                .call("{\"resource\":\"stock\"}");

        assertTrue(out.startsWith("x".repeat(20)), out);
        assertTrue(out.contains("已截断"), out);
    }

    @Test
    void call_non2xx_returnsErrorWithBody() {
        String out = callback("http://wms", "raw", 6000, 500, "boom")
                .call("{\"resource\":\"stock\"}");

        assertTrue(out.contains("HTTP 500"), out);
        assertTrue(out.contains("boom"), out);
    }

    @Test
    void call_invalidJson_isRejectedWithoutRequest() {
        String out = callback("http://wms", "raw", 6000, 200, "{}").call("not json");

        assertTrue(out.contains("不是合法 JSON"), out);
        assertNull(lastUrl.get());
    }

    private HttpToolCallback callback(String baseUrl, String format, int maxChars, int status, String body) {
        HttpTransport transport = (url, headers, timeout) -> {
            lastUrl.set(url);
            lastHeaders.set(headers);
            return new HttpTransport.Response(status, body);
        };
        return new HttpToolCallback(manifest(baseUrl, format, maxChars), transport, "tok", mapper);
    }

    private static HttpToolManifest manifest(String baseUrl, String format, int maxChars) {
        Map<String, HttpToolManifest.Resource> resources = new LinkedHashMap<>();
        resources.put("stock", new HttpToolManifest.Resource(
                "/inventory/list", List.of("productId", "pageNo"), List.of(), Map.of(), "库存"));
        resources.put("detail", new HttpToolManifest.Resource(
                "/goods/queryById", List.of("id"), List.of("id"), Map.of("taskType", "RECEIVE_TASK"), "详情"));
        return new HttpToolManifest("wms.query", Path.of("wms.yaml"), baseUrl, "${WMS_API_BASE:}",
                new HttpToolManifest.Auth("X-Access-Token", "WMS_API_TOKEN"), 15000,
                new HttpToolManifest.ResponseShape(format,
                        List.of("createBy", "createTime", "updateTime"), maxChars),
                new HttpToolManifest.ToolSpec("wms_query", "查询", "resource", "params"), resources);
    }
}
