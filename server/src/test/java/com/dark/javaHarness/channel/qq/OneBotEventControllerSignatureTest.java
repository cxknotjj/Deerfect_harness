package com.dark.javaHarness.channel.qq;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * OneBot 上报验签单测：signatureValid 全分支（优化审查 2026-09-25 测试盲区项）。
 * 签名口径与 NapCat 一致：X-Signature: sha1=HMAC-SHA1(event-secret, rawBody)。
 */
class OneBotEventControllerSignatureTest {

    private static final String SECRET = "event-secret-123";

    private OneBotEventController controller(String eventSecret) {
        NapCatProperties props = new NapCatProperties();
        props.setEventSecret(eventSecret);
        return new OneBotEventController(new ObjectMapper(),
                mock(OneBotEventService.class), mock(Executor.class), props);
    }

    /** 独立计算期望签名（与实现同口径：sha1= 前缀 + HMAC-SHA1 小写 hex） */
    private static String sign(byte[] body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        StringBuilder hex = new StringBuilder("sha1=");
        for (byte b : mac.doFinal(body)) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    /** secret 未配置（null/空白）：跳过验签恒通过 */
    @Test
    void blankSecret_skipsValidation() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        assertTrue(controller(null).signatureValid(body, null));
        assertTrue(controller("  ").signatureValid(body, "sha1=anything"));
    }

    /** secret 已配置：正确签名（含大小写 hex 与带空白尾缀的 header）通过 */
    @Test
    void configuredSecret_validSignature_passes() throws Exception {
        byte[] body = "{\"post_type\":\"message\"}".getBytes(StandardCharsets.UTF_8);
        OneBotEventController c = controller(SECRET);
        assertTrue(c.signatureValid(body, sign(body, SECRET)));
        assertTrue(c.signatureValid(body, "  " + sign(body, SECRET) + "  "));
    }

    /** secret 已配置：错误签名 / 缺 sha1= 前缀 / 缺头 / null body 配错签 均拒绝 */
    @Test
    void configuredSecret_invalidInputs_rejected() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        OneBotEventController c = controller(SECRET);
        assertFalse(c.signatureValid(body, "sha1=" + "0".repeat(40)));
        assertFalse(c.signatureValid(body, sign(body, SECRET).toUpperCase())); // 大写 hex 不等价(常量时间比较)
        assertFalse(c.signatureValid(body, "deadbeef"));                       // 缺 sha1= 前缀
        assertFalse(c.signatureValid(body, null));                             // 缺头
        assertFalse(c.signatureValid(new byte[0], sign(body, SECRET)));        // 空 body 签名不匹配
    }

    /** 空 body 传 null：实现按空字节数组计算，正确签名仍通过 */
    @Test
    void nullBody_treatedAsEmpty_signaturesMatch() throws Exception {
        OneBotEventController c = controller(SECRET);
        assertTrue(c.signatureValid(null, sign(new byte[0], SECRET)));
    }
}
