package com.dark.javaHarness.channel.feishu.ws;

import com.dark.javaHarness.channel.feishu.FeishuProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 飞书应用 API（入站回复用）：tenant_access_token + 发消息。
 *
 * <p>为什么不用 SDK 的 {@code client.im().v1().message().create()}：出站只需要两个 REST
 * 调用，用 JDK HttpClient 写出来是一屏可读的代码，出错时能直接看到 HTTP 状态与飞书
 * 错误码；SDK 的封装会把这两层折成一个异常，排障时反而要多跳一次。
 *
 * <p>token 缓存到过期前 60 秒：飞书 token 有效期 2 小时，每条消息都换一次 token 会
 * 平白多一次往返，也更容易触发限流。
 */
public class FeishuApiClient {

    private static final Logger log = LoggerFactory.getLogger(FeishuApiClient.class);

    private static final String DEFAULT_BASE_URL = "https://open.feishu.cn";

    /** 提前刷新窗口：避免拿着「还剩 3 秒」的 token 去发消息 */
    private static final Duration REFRESH_AHEAD = Duration.ofSeconds(60);

    private final String appId;
    private final String appSecret;
    private final String baseUrl;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private final Clock clock;

    private volatile Token token;

    public FeishuApiClient(FeishuProperties props) {
        this(props.getWs().getAppId(), props.getWs().getAppSecret(), DEFAULT_BASE_URL,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), Clock.systemUTC());
    }

    /** 测试用：可替换 base URL、HttpClient 与时钟 */
    public FeishuApiClient(String appId, String appSecret, String baseUrl, HttpClient http, Clock clock) {
        this.appId = appId;
        this.appSecret = appSecret;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = http;
        this.clock = clock;
    }

    /**
     * 给单聊用户发一条纯文本。
     *
     * @param openId 接收者 open_id（事件里的 sender.open_id）
     * @return 成功带 message_id；失败带可读原因（含飞书 code）
     */
    public Result sendText(String openId, String text) {
        return send("open_id", openId, text);
    }

    /** 给群聊发一条纯文本（群里 @ 机器人后的回复走这条） */
    public Result sendTextToChat(String chatId, String text) {
        return send("chat_id", chatId, text);
    }

    private Result send(String receiveIdType, String receiveId, String text) {
        if (receiveId == null || receiveId.isBlank()) {
            return new Result(false, null, receiveIdType + " 为空");
        }
        if (text == null || text.isBlank()) {
            return new Result(false, null, "正文为空");
        }
        String accessToken;
        try {
            accessToken = tenantAccessToken();
        } catch (Exception e) {
            return new Result(false, null, "获取 tenant_access_token 失败：" + e.getMessage());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("receive_id", receiveId);
        body.put("msg_type", "text");
        try {
            // content 是「JSON 字符串」而不是对象：飞书要求二次序列化
            body.put("content", json.writeValueAsString(Map.of("text", text)));
        } catch (IOException e) {
            return new Result(false, null, "回复内容序列化失败：" + e.getMessage());
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl
                        + "/open-apis/im/v1/messages?receive_id_type=" + receiveIdType))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(toJson(body), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode node = readTree(response.body());
            int code = node == null ? -1 : node.path("code").asInt(-1);
            if (response.statusCode() / 100 == 2 && code == 0) {
                String messageId = node.path("data").path("message_id").asText(null);
                return new Result(true, messageId, null);
            }
            String msg = node == null ? response.body() : node.path("msg").asText("");
            return new Result(false, null, "HTTP " + response.statusCode() + " code=" + code + "：" + abbreviate(msg));
        } catch (IOException e) {
            return new Result(false, null, "发送请求失败：" + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, null, "发送被中断");
        }
    }

    /** 当前 token（测试与排障用）；无缓存时返回 null */
    public String cachedToken() {
        Token current = token;
        return current == null ? null : current.value();
    }

    private String tenantAccessToken() throws IOException, InterruptedException {
        Token current = token;
        Instant now = clock.instant();
        if (current != null && now.isBefore(current.expiresAt().minus(REFRESH_AHEAD))) {
            return current.value();
        }
        synchronized (this) {
            current = token;
            if (current != null && now.isBefore(current.expiresAt().minus(REFRESH_AHEAD))) {
                return current.value();
            }
            Map<String, Object> body = Map.of("app_id", appId, "app_secret", appSecret);
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/open-apis/auth/v3/tenant_access_token/internal"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(toJson(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode node = readTree(response.body());
            int code = node == null ? -1 : node.path("code").asInt(-1);
            String value = node == null ? null : node.path("tenant_access_token").asText(null);
            if (response.statusCode() / 100 != 2 || code != 0 || value == null || value.isBlank()) {
                throw new IOException("飞书返回 HTTP " + response.statusCode() + " code=" + code
                        + "：" + (node == null ? abbreviate(response.body()) : node.path("msg").asText("")));
            }
            long expireSeconds = node.path("expire").asLong(7200);
            this.token = new Token(value, now.plusSeconds(expireSeconds));
            log.info("[feishu] tenant_access_token 已刷新，有效期 {}s", expireSeconds);
            return value;
        }
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    private JsonNode readTree(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return json.readTree(body);
        } catch (IOException e) {
            return null;
        }
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "…";
    }

    private record Token(String value, Instant expiresAt) {
    }

    /**
     * 发送结果。
     *
     * @param sent      是否成功
     * @param messageId 飞书消息 ID（成功时）
     * @param reason    失败原因（含飞书 code，便于对照文档）
     */
    /**
     * 下载消息里的图片资源。
     *
     * <p>接口 {@code GET /open-apis/im/v1/messages/{message_id}/resources/{file_key}?type=image}，
     * 需要 {@code im:resource} 权限；缺权限时飞书返回 403 + JSON 错误体，这里折成可读原因。
     *
     * @return 成功时 data 为原始字节、mimeType 取自响应头；失败时 reason 可读
     */
    public Download downloadImage(String messageId, String fileKey) {
        if (messageId == null || messageId.isBlank() || fileKey == null || fileKey.isBlank()) {
            return new Download(false, null, null, "messageId 或 fileKey 为空");
        }
        String accessToken;
        try {
            accessToken = tenantAccessToken();
        } catch (Exception e) {
            return new Download(false, null, null, "获取 tenant_access_token 失败：" + e.getMessage());
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl
                        + "/open-apis/im/v1/messages/" + messageId + "/resources/" + fileKey + "?type=image"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            byte[] body = response.body();
            if (response.statusCode() / 100 == 2 && body != null && body.length > 0) {
                String mimeType = response.headers().firstValue("Content-Type")
                        .orElse("image/png").split(";")[0].trim();
                return new Download(true, body, mimeType, null);
            }
            String detail = body == null ? "" : new String(body, StandardCharsets.UTF_8);
            return new Download(false, null, null, "HTTP " + response.statusCode() + "：" + abbreviate(detail));
        } catch (IOException e) {
            return new Download(false, null, null, "下载请求失败：" + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Download(false, null, null, "下载被中断");
        }
    }

    /** 图片下载结果：成功带原始字节与 mimeType，失败带可读原因 */
    public record Download(boolean ok, byte[] data, String mimeType, String reason) {
    }

    public record Result(boolean sent, String messageId, String reason) {
    }
}
