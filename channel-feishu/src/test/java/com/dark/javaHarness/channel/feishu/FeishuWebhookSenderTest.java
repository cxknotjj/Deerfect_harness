package com.dark.javaHarness.channel.feishu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dark.javaHarness.contract.spi.MessageSender.DeliveryReceipt;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 飞书 webhook 单测：签名向量、四类错误分流、以及三种「不该发出去」的前置校验。
 *
 * <p>错误分流是这里唯一有价值的断言：outbox 只看 {@code retryable}，分错就会要么把
 * 配置错误重试三轮，要么把限流直接判死。
 */
class FeishuWebhookSenderTest {

    /** 固定时钟：签名带 timestamp，不固定就没法断言 */
    private static final Instant FIXED = Instant.ofEpochSecond(1_700_000_000L);

    private StubWebhookServer server;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        server = new StubWebhookServer();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void success_sendsTextPayloadAndReportsSent() throws IOException {
        DeliveryReceipt receipt = sender().sendForReceipt("main", "日报已生成");

        assertTrue(receipt.sent());
        assertNull(receipt.messageId(), "webhook 不返回 message_id，不能编一个出来");
        assertEquals(1, server.requests().size());

        JsonNode body = json.readTree(server.requests().get(0));
        assertEquals("text", body.path("msg_type").asText());
        assertEquals("日报已生成", body.path("content").path("text").asText());
    }

    @Test
    void signature_matchesFeishuAlgorithmWithSecondLevelTimestamp() throws IOException {
        sender().sendForReceipt("main", "内容");

        JsonNode body = json.readTree(server.requests().get(0));
        assertEquals("1700000000", body.path("timestamp").asText(),
                "timestamp 必须是秒级：毫秒会稳定返回签名错误");
        // 由 Python hmac(key="1700000000\ntest-secret", msg=b"", sha256) 独立算出的向量
        assertEquals("mbm4Y4oluIPQ00qlBIhX8vAZ0EKv3nw0LuTb91jPL84=", body.path("sign").asText());
    }

    @Test
    void code9499_isFatal() {
        server.respond(200, "{\"code\":9499,\"msg\":\"Bad Request\"}");

        DeliveryReceipt receipt = sender().sendForReceipt("main", "内容");

        assertFalse(receipt.sent());
        assertFalse(receipt.retryable(), "请求体非法 / 超 20 KB，重试只会一直失败");
        assertTrue(receipt.reason().contains("9499"), receipt.reason());
    }

    @Test
    void code11232_isRetryable() {
        server.respond(200, "{\"code\":11232,\"msg\":\"too many request\"}");

        DeliveryReceipt receipt = sender().sendForReceipt("main", "内容");

        assertFalse(receipt.sent());
        assertTrue(receipt.retryable(), "限流是瞬时故障，退避后应重试");
        assertTrue(receipt.reason().contains("限流"), receipt.reason());
    }

    @Test
    void signError_isFatalAndMentionsClock() {
        server.respond(200, "{\"code\":19021,\"msg\":\"sign match fail or timestamp is not within one hour\"}");

        DeliveryReceipt receipt = sender().sendForReceipt("main", "内容");

        assertFalse(receipt.sent());
        assertFalse(receipt.retryable());
        assertTrue(receipt.reason().contains("时钟"), "要提示查时钟，否则只会反复重试");
    }

    @Test
    void http5xx_isRetryable() {
        server.respond(503, "gateway down");

        DeliveryReceipt receipt = sender().sendForReceipt("main", "内容");

        assertFalse(receipt.sent());
        assertTrue(receipt.retryable());
        assertTrue(receipt.reason().contains("503"), receipt.reason());
    }

    @Test
    void http4xx_isFatal() {
        server.respond(404, "not found");

        DeliveryReceipt receipt = sender().sendForReceipt("main", "内容");

        assertFalse(receipt.sent());
        assertFalse(receipt.retryable(), "地址写错，重试没有意义");
    }

    @Test
    void unparseableBody_isRetryable() {
        server.respond(200, "<html>proxy error</html>");

        DeliveryReceipt receipt = sender().sendForReceipt("main", "内容");

        assertFalse(receipt.sent());
        assertTrue(receipt.retryable(), "看不懂的响应按未知失败处理：宁可多试一次也别丢消息");
    }

    @Test
    void unknownAlias_isRejectedWithoutRequest() {
        DeliveryReceipt receipt = sender().sendForReceipt("other", "内容");

        assertFalse(receipt.sent());
        assertFalse(receipt.retryable());
        assertTrue(receipt.reason().contains("别名"), receipt.reason());
        assertTrue(server.requests().isEmpty(), "别名不对不该发出请求");
    }

    @Test
    void blankText_isRejectedWithoutRequest() {
        DeliveryReceipt receipt = sender().sendForReceipt("main", " ");

        assertFalse(receipt.sent());
        assertFalse(receipt.retryable());
        assertTrue(server.requests().isEmpty());
    }

    @Test
    void oversizedBody_isRejectedWithoutRequest() {
        DeliveryReceipt receipt = sender().sendForReceipt("main", "长".repeat(10_000));

        assertFalse(receipt.sent());
        assertFalse(receipt.retryable());
        assertTrue(receipt.reason().contains("20 KB"), receipt.reason());
        assertTrue(server.requests().isEmpty(), "超限的请求不该发出去——发了也只会 9499");
    }

    @Test
    void connectionRefused_isRetryable() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        FeishuWebhookSender sender = senderWith(props("http://127.0.0.1:" + closedPort + "/hook", "test-secret"));

        DeliveryReceipt receipt = sender.sendForReceipt("main", "内容");

        assertFalse(receipt.sent());
        assertTrue(receipt.retryable(), "连接被拒是典型瞬时故障");
    }

    @Test
    void lastAttempt_recordsFailureForHealth() {
        server.respond(500, "boom");
        FeishuWebhookSender sender = sender();

        sender.sendForReceipt("main", "内容");

        FeishuWebhookSender.Attempt attempt = sender.lastAttempt();
        assertNotNull(attempt);
        assertEquals(FIXED, attempt.at());
        assertFalse(attempt.sent());
        assertTrue(attempt.reason().contains("500"));
    }

    private FeishuWebhookSender sender() {
        return senderWith(props(server.url(), "test-secret"));
    }

    private static FeishuWebhookSender senderWith(FeishuProperties props) {
        return new FeishuWebhookSender(props, StubWebhookServer.httpClient(),
                Clock.fixed(FIXED, ZoneOffset.UTC));
    }

    private static FeishuProperties props(String url, String secret) {
        FeishuProperties props = new FeishuProperties();
        props.setEnabled(true);
        props.setAlias("main");
        props.setWebhookUrl(url);
        props.setWebhookSecret(secret);
        return props;
    }
}
