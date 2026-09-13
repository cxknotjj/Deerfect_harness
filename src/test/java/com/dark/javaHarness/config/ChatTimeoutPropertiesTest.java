package com.dark.javaHarness.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.dark.javaHarness.config.agent.ChatClientFactory;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * LLM 超时配置单测：
 * - ChatTimeoutProperties 纯绑定载体：字段无初始化值（未配置为 null），setter 绑定后 getter 透传
 * - ChatClientFactory.resolve 兜底口径：null → 默认秒数（行为与历史硬编码一致）、显式值按秒转 Duration
 */
class ChatTimeoutPropertiesTest {

    /** resolve 兜底：未配置（null）→ 回退默认秒数（与旧硬编码 10/300/120 同口径） */
    @Test
    void resolve_nullConfigured_fallsBackToDefault() {
        assertEquals(Duration.ofSeconds(10), ChatClientFactory.resolve(null, 10));
        assertEquals(Duration.ofSeconds(300), ChatClientFactory.resolve(null, 300));
        assertEquals(Duration.ofSeconds(120), ChatClientFactory.resolve(null, 120));
    }

    /** resolve 显式配置：按秒转 Duration（50 → 50s），默认值仅在未配置时参与 */
    @Test
    void resolve_configured_returnsConfiguredDuration() {
        assertEquals(Duration.ofSeconds(50), ChatClientFactory.resolve(50, 10));
        assertEquals(Duration.ofSeconds(50), ChatClientFactory.resolve(50, 300));
    }

    /** 绑定载体：yaml app.chat.timeouts.* 三键经 setter 绑定后 getter 透传 */
    @Test
    void setters_bindAllThreeTimeouts() {
        ChatTimeoutProperties props = new ChatTimeoutProperties();
        props.setConnectTimeoutSeconds(15);
        props.setReadTimeoutSeconds(600);
        props.setStreamIdleTimeoutSeconds(90);

        assertEquals(15, props.getConnectTimeoutSeconds());
        assertEquals(600, props.getReadTimeoutSeconds());
        assertEquals(90, props.getStreamIdleTimeoutSeconds());
    }

    /** 未配置态：三字段保持 null（消费方 resolve 据此判定兜底） */
    @Test
    void unconfigured_fieldsStayNull() {
        ChatTimeoutProperties props = new ChatTimeoutProperties();

        assertNull(props.getConnectTimeoutSeconds());
        assertNull(props.getReadTimeoutSeconds());
        assertNull(props.getStreamIdleTimeoutSeconds());
    }
}
