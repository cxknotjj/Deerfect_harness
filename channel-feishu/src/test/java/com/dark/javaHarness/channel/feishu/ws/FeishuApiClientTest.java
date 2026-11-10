package com.dark.javaHarness.channel.feishu.ws;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
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

    @Test
    void downloadImage_returnsBytesAndContentType() {
        byte[] data = {1, 2, 3, 4};
        server.respondImage(200, data, "image/png");

        FeishuApiClient.Download download = client.downloadImage("om_1", "img_key_1");

        assertTrue(download.ok());
        assertArrayEquals(data, download.data());
        assertEquals("image/png", download.mimeType(), "mimeType 取自响应头");
        assertTrue(server.resourceRequests().get(0).contains("type=image"),
                "资源接口必须带 type=image：" + server.resourceRequests().get(0));
    }

    @Test
    void downloadImage_httpError_surfacesStatusAndBody() {
        server.respondImage(403,
                "{\"code\":99991672,\"msg\":\"no permission\"}".getBytes(StandardCharsets.UTF_8),
                "application/json");

        FeishuApiClient.Download download = client.downloadImage("om_1", "img_key_1");

        assertFalse(download.ok());
        assertTrue(download.reason().contains("403"), download.reason());
        assertTrue(download.reason().contains("no permission"), download.reason());
    }

    // ---------- 表情包：上传图片 + 发送 image 消息 ----------

    @Test
    void uploadImage_sendsMultipartAndReturnsKey() {
        FeishuApiClient.Upload upload = client.uploadImage("happy_01.png", new byte[] {1, 2, 3});

        assertTrue(upload.ok());
        assertEquals("img_test", upload.imageKey());
        String body = server.uploadRequests().get(0);
        assertTrue(body.contains("name=\"image_type\""), body);
        assertTrue(body.contains("name=\"image\"; filename=\"happy_01.png\""), body);
        assertTrue(body.contains("message"), "image_type 必须是 message（表情包按消息图片上传）");
    }

    @Test
    void uploadImage_httpError_surfacesReason() {
        server.respondUpload(403, "{\"code\":99991672,\"msg\":\"no permission\"}");

        FeishuApiClient.Upload upload = client.uploadImage("happy_01.png", new byte[] {1});

        assertFalse(upload.ok());
        assertTrue(upload.reason().contains("403"), upload.reason());
        assertTrue(upload.reason().contains("no permission"), upload.reason());
    }

    @Test
    void uploadImage_emptyData_isRejectedWithoutRequest() {
        FeishuApiClient.Upload upload = client.uploadImage("happy_01.png", new byte[0]);

        assertFalse(upload.ok());
        assertTrue(server.uploadRequests().isEmpty());
    }

    @Test
    void sendImage_usesImageMsgTypeAndKey() {
        FeishuApiClient.Result result = client.sendImage("ou_user", "img_1");

        assertTrue(result.sent());
        String request = server.messageRequests().get(0);
        assertTrue(request.contains("\"msg_type\":\"image\""), request);
        // content 仍是二次序列化的 JSON 字符串
        assertTrue(request.contains("\"content\":\"{\\\"image_key\\\":\\\"img_1\\\"}\""), request);
    }

    // ---------- 重试与令牌刷新 ----------

    @Test
    void sendText_retriesOnceOn429() {
        server.failNextMessages(1, 429, "{\"code\":99991400,\"msg\":\"too many request\"}");

        FeishuApiClient.Result result = client.sendText("ou_user", "回复内容");

        assertTrue(result.sent(), result.reason());
        assertEquals(2, server.messageRequests().size(), "429 应重试一次");
    }

    @Test
    void sendText_refreshesTokenOn401() {
        server.failNextMessages(1, 401, "{\"code\":99991663,\"msg\":\"token invalid\"}");

        FeishuApiClient.Result result = client.sendText("ou_user", "回复内容");

        assertTrue(result.sent(), result.reason());
        assertEquals(2, server.tokenRequests().size(), "401 必须清掉缓存重取 token，否则会一直失败到过期");
    }

    @Test
    void sendText_doesNotRetryOnClientError() {
        server.failNextMessages(1, 400, "{\"code\":230001,\"msg\":\"invalid param\"}");

        FeishuApiClient.Result result = client.sendText("ou_user", "回复内容");

        assertFalse(result.sent());
        assertEquals(1, server.messageRequests().size(), "参数错误重试没有意义，只会拖慢报错");
    }

    // ---------- 处理回执（表情回应） ----------

    @Test
    void addReaction_postsEmojiType() {
        FeishuApiClient.Reaction reaction = client.addReaction("om_1", "OnIt");

        assertTrue(reaction.ok(), reaction.reason());
        assertEquals("re_1", reaction.reactionId());
        String request = server.reactionRequests().get(0);
        assertTrue(request.startsWith("POST /open-apis/im/v1/messages/om_1/reactions"), request);
        assertTrue(request.contains("\"emoji_type\":\"OnIt\""), request);
    }

    @Test
    void deleteReaction_usesDeleteMethod() {
        FeishuApiClient.Reaction reaction = client.deleteReaction("om_1", "re_1");

        assertTrue(reaction.ok(), reaction.reason());
        assertTrue(server.reactionRequests().get(0)
                .startsWith("DELETE /open-apis/im/v1/messages/om_1/reactions/re_1"));
    }

    @Test
    void sendImageToChat_usesChatId() {
        client.sendImageToChat("oc_chat", "img_1");

        assertTrue(server.messageRequests().get(0).startsWith("receive_id_type=chat_id|"));
    }

    @Test
    void downloadImage_blankKey_isRejectedWithoutRequest() {
        FeishuApiClient.Download download = client.downloadImage("om_1", " ");

        assertFalse(download.ok());
        assertTrue(server.resourceRequests().isEmpty());
    }
}
