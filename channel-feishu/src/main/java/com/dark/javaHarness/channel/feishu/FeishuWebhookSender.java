package com.dark.javaHarness.channel.feishu;

import com.dark.javaHarness.service.MessageSender;
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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 飞书群机器人 webhook：一个带签名的 HTTP POST，零 SDK。
 *
 * <p><b>为什么是 webhook 而不是应用/事件订阅</b>：投递可靠性只需要「发得出去」，不需要
 * 「收得进来」。群机器人不需要建应用、申请权限、审核、域名或公网回调——这是 M3a 与
 * M3b 成本差 6 倍的原因。
 *
 * <p><b>错误分流是这类渠道唯一有价值的部分</b>：outbox 只会看 {@code retryable}，所以
 * 「限流」「请求体非法」「签名错」「网络断」必须在这里分清楚——分不清就会要么该重试的
 * 直接判死，要么配置错的重试三轮后仍失败。
 *
 * <p>签名：{@code sign = Base64(HMAC-SHA256(key = timestamp + "\n" + secret, data = ""))}，
 * timestamp 必须用<b>秒级</b>。用毫秒会稳定返回签名错误——这是最常见的接入事故。
 */
public class FeishuWebhookSender implements MessageSender {

    private static final Logger log = LoggerFactory.getLogger(FeishuWebhookSender.class);

    /** 飞书 webhook 请求体上限 20 KB（含签名与 JSON 结构，按序列化后的字节算） */
    static final int MAX_BODY_BYTES = 20 * 1024;

    /** 错误信息里保留的响应体长度：够看懂原因，又不至于把整页 HTML 写进 last_error */
    private static final int MAX_REASON_LENGTH = 300;

    private static final String CHANNEL = "feishu";

    private final FeishuProperties props;
    private final HttpClient http;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    /** 最近一次真实投递：健康状态用它当探针，不额外往群里发测试消息 */
    private volatile Attempt lastAttempt;

    public FeishuWebhookSender(FeishuProperties props) {
        this(props, HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1000, props.getTimeoutMs())))
                .build(), Clock.systemUTC());
    }

    /** 测试用：注入可控时钟与 HttpClient（本地 HttpServer 验证请求体与错误分流） */
    FeishuWebhookSender(FeishuProperties props, HttpClient http, Clock clock) {
        this.props = props;
        this.http = http;
        this.clock = clock;
    }

    @Override
    public String channel() {
        return CHANNEL;
    }

    @Override
    public boolean send(String target, String text) {
        return sendForReceipt(target, text).sent();
    }

    /**
     * 发送并给出可分类的回执。
     *
     * <p>{@code target} 是配置里的别名（{@code main}），不是 URL——URL 是凭证，
     * 不该出现在 {@code notify_channel} 或数据库里。
     */
    @Override
    public DeliveryReceipt sendForReceipt(String target, String text) {
        DeliveryReceipt receipt = doSend(target, text);
        lastAttempt = new Attempt(clock.instant(), receipt.sent(), receipt.reason());
        return receipt;
    }

    private DeliveryReceipt doSend(String target, String text) {
        if (isBlank(target)) {
            return DeliveryReceipt.fatal("飞书目标为空（应写别名，如 feishu:main）");
        }
        String alias = isBlank(props.getAlias()) ? "main" : props.getAlias().trim();
        if (!alias.equals(target.trim())) {
            return DeliveryReceipt.fatal("未配置的飞书别名：" + target.trim() + "（当前仅 " + alias + "）");
        }
        if (isBlank(text)) {
            return DeliveryReceipt.fatal("飞书正文为空");
        }

        byte[] body;
        try {
            body = payload(text);
        } catch (IOException e) {
            return DeliveryReceipt.fatal("飞书请求体序列化失败：" + e.getMessage());
        }
        if (body.length > MAX_BODY_BYTES) {
            // 不截断：截断会静默丢信息，而「太长」正是邮件该接手的场景（D1-8）
            return DeliveryReceipt.fatal("飞书请求体 " + body.length + " 字节，超过 20 KB 上限，应改走邮件");
        }

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(props.getWebhookUrl().trim()))
                    .timeout(Duration.ofMillis(timeoutMs()))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
        } catch (IllegalArgumentException e) {
            return DeliveryReceipt.fatal("飞书 webhook 地址非法：" + e.getMessage());
        }

        try {
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return classify(response.statusCode(), response.body());
        } catch (IOException e) {
            // 连接被拒 / 读超时 / DNS：典型瞬时故障，下一轮重试有意义
            return DeliveryReceipt.failed("飞书 webhook 请求失败：" + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return DeliveryReceipt.failed("飞书 webhook 请求被中断");
        }
    }

    /**
     * 按飞书返回的 {@code code} 与 HTTP 状态分流。
     *
     * <ul>
     *   <li>{@code 0} → 送达（webhook 不返回 message_id）</li>
     *   <li>{@code 11232} → 限流，可重试（退避后通常就好）</li>
     *   <li>{@code 9499} → 请求体非法（含超 20 KB），重试只会一直失败</li>
     *   <li>sign / timestamp 相关 → 终态，并提示查 secret 与时钟</li>
     *   <li>HTTP 5xx / 429 → 可重试；其余 4xx → 终态（地址写错，重试无意义）</li>
     *   <li>其它业务码 → 按可重试处理：未知失败宁可多试一次，也别丢消息</li>
     * </ul>
     */
    DeliveryReceipt classify(int httpStatus, String body) {
        if (httpStatus == 429) {
            return DeliveryReceipt.failed("飞书限流（HTTP 429）：" + abbreviate(body));
        }
        if (httpStatus >= 500) {
            return DeliveryReceipt.failed("飞书服务不可用（HTTP " + httpStatus + "）：" + abbreviate(body));
        }
        if (httpStatus / 100 != 2) {
            return DeliveryReceipt.fatal("飞书请求被拒（HTTP " + httpStatus + "）：" + abbreviate(body));
        }

        JsonNode node = readTree(body);
        if (node == null) {
            return DeliveryReceipt.failed("飞书返回无法解析：" + abbreviate(body));
        }
        int code = node.path("code").asInt(-1);
        String message = node.path("msg").asText("");
        if (code == 0) {
            return DeliveryReceipt.sent(null);
        }
        if (code == 11232) {
            return DeliveryReceipt.failed("飞书限流（11232）：" + abbreviate(message));
        }
        if (code == 9499) {
            return DeliveryReceipt.fatal("飞书请求体非法（9499）：" + abbreviate(message) + "（检查是否超过 20 KB）");
        }
        if (code == 19021 || mentionsSign(message)) {
            return DeliveryReceipt.fatal("飞书签名校验失败（" + code + "）：" + abbreviate(message)
                    + "（检查 webhook-secret；时间戳需为秒级且与本机时钟一致）");
        }
        if (code < 0) {
            return DeliveryReceipt.failed("飞书返回缺少 code：" + abbreviate(body));
        }
        return DeliveryReceipt.failed("飞书错误码 " + code + "：" + abbreviate(message));
    }

    /**
     * 构造请求体：启用签名时带 {@code timestamp}（秒）与 {@code sign}。
     *
     * <p>正文按字面放进 {@code text}，不做 Markdown——群机器人对 markdown 的渲染依赖
     * 客户端版本，纯文本在手机与桌面表现一致。
     */
    byte[] payload(String text) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        String secret = props.getWebhookSecret();
        if (!isBlank(secret)) {
            String timestamp = String.valueOf(clock.instant().getEpochSecond());
            root.put("timestamp", timestamp);
            root.put("sign", sign(timestamp, secret));
        }
        root.put("msg_type", "text");
        root.put("content", Map.of("text", text));
        return json.writeValueAsBytes(root);
    }

    /**
     * 飞书签名：以 {@code timestamp + "\n" + secret} 为密钥，对空串做 HMAC-SHA256 再 Base64。
     *
     * <p>注意方向：{@code timestamp + secret} 是 <b>key</b>，不是待签数据。飞书文档的
     * Python 示例写的是 {@code hmac.new(string_to_sign.encode(), digestmod=sha256)}，
     * 省略的第二个参数就是空消息——照抄成「key=secret, data=timestamp」会一直签名失败。
     */
    static String sign(String timestamp, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec((timestamp + "\n" + secret).getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(new byte[0]));
        } catch (java.security.GeneralSecurityException e) {
            // JDK 一定带 HmacSHA256；走到这里说明运行环境被裁剪过，属于部署问题
            throw new IllegalStateException("HmacSHA256 不可用", e);
        }
    }

    /** 最近一次投递（健康检查用）；从未发送时为 null */
    public Attempt lastAttempt() {
        return lastAttempt;
    }

    private int timeoutMs() {
        return Math.max(1000, props.getTimeoutMs());
    }

    private JsonNode readTree(String body) {
        if (isBlank(body)) {
            return null;
        }
        try {
            return json.readTree(body);
        } catch (IOException e) {
            log.debug("[feishu] 响应体不是 JSON：{}", abbreviate(body));
            return null;
        }
    }

    private static boolean mentionsSign(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("sign") || lower.contains("timestamp");
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= MAX_REASON_LENGTH ? flat : flat.substring(0, MAX_REASON_LENGTH) + "…";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 一次投递的结果快照。
     *
     * @param at     发生时刻
     * @param sent   是否送达
     * @param reason 失败原因；成功时为 null
     */
    public record Attempt(Instant at, boolean sent, String reason) {
    }
}
