package com.dark.javaHarness.channel.feishu.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Clock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 飞书 API 客户端单测：token 缓存、open_id/chat_id 寻址、错误码透出。
 *
 * <p>只验回复链路（入站用）。webhook 的故事在 {@code FeishuWebhookSenderTest}。
 */
class FeishuApiClientTest {

    private StubFeishuApiServer server;
    private FeishuApiClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new StubFeishuApiServer();
        client = new FeishuApiClient("app-id", "app-secret", server.url(),
                HttpClient.newHttpClient(), Clock.systemUTC());
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void sendText_usesOpenId() {
        FeishuApiClient.Result result = client.sendText("ou_user", "回复内容");

        assertTrue(result.sent());
        assertEquals("om_test", result.messageId());
        String request = server.messageRequests().get(0);
        assertTrue(request.startsWith("receive_id_type=open_id|"), request);
        assertTrue(request.contains("\"receive_id\":\"ou_user\""), request);
        // content 必须是「JSON 字符串」，不是对象
        assertTrue(request.contains("\"content\":\"{\\\"text\\\":\\\"回复内容\\\"}\""), request);
    }

    @Test
    void sendTextToChat_usesChatId() {
        client.sendTextToChat("oc_chat", "群回复");

        String request = server.messageRequests().get(0);
        assertTrue(request.startsWith("receive_id_type=chat_id|"), request);
        assertTrue(request.contains("\"receive_id\":\"oc_chat\""), request);
    }

    @Test
    void tokenIsCachedBetweenSends() {
        client.sendText("ou_user", "第一条");
        client.sendText("ou_user", "第二条");

        assertEquals(1, server.tokenRequests().size(), "2 小时有效期内不该每条消息都换 token");
        assertEquals(2, server.messageRequests().size());
    }

    @Test
    void businessError_isSurfacedWithCode() {
        server.respondMessage(200, "{\"code\":99991663,\"msg\":\"bot is not in chat\"}");

        FeishuApiClient.Result result = client.sendTextToChat("oc_chat", "内容");

        assertFalse(result.sent());
        assertTrue(result.reason().contains("99991663"), result.reason());
        assertTrue(result.reason().contains("not in chat"), result.reason());
    }

    @Test
    void httpError_isSurfaced() {
        server.respondMessage(500, "gateway down");

        FeishuApiClient.Result result = client.sendText("ou_user", "内容");

        assertFalse(result.sent());
        assertTrue(result.reason().contains("HTTP 500"), result.reason());
    }

    @Test
    void tokenFailure_stopsBeforeSending() {
        server.respondToken("{\"code\":10003,\"msg\":\"invalid app_secret\"}");

        FeishuApiClient.Result result = client.sendText("ou_user", "内容");

        assertFalse(result.sent());
        assertTrue(result.reason().contains("tenant_access_token"), result.reason());
        assertTrue(server.messageRequests().isEmpty(), "拿不到 token 就不该再发消息");
    }

    @Test
    void blankTarget_isRejectedWithoutRequest() {
        FeishuApiClient.Result result = client.sendText(" ", "内容");

        assertFalse(result.sent());
        assertTrue(server.messageRequests().isEmpty());
    }
}
