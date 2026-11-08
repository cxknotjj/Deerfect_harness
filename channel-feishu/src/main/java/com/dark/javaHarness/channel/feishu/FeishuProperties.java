package com.dark.javaHarness.channel.feishu;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 飞书渠道配置（{@code channel.feishu.*}），M3a 只需要「一个群 + 一个 webhook」。
 *
 * <p><b>为什么只有单个 webhook 别名</b>：群机器人 webhook 的 URL 本身就指向那个群，
 * 个人 agent 没有「发给多个人」的需求。要支持多个群就再配几个 bean，而不是把 URL
 * 塞进业务表——凭证不该躺在数据库里，{@code notify_channel} 只写 {@code feishu:main}。
 *
 * <p>凭据走环境变量（{@code FEISHU_WEBHOOK_URL} / {@code FEISHU_WEBHOOK_SECRET}）。
 * {@code secret} 不是可选项：URL 泄露即可被人冒名发消息，签名是唯一的门槛，
 * 所以 {@link FeishuChannelConfig} 在启动时强制校验，缺了直接起不来。
 */
@Component
@ConfigurationProperties(prefix = "channel.feishu")
public class FeishuProperties {

    /** 渠道总开关（false 时无 Bean、无渠道注册） */
    private boolean enabled;

    /** webhook 别名：{@code notify_channel} 写 {@code feishu:main} 里的那个 main */
    private String alias = "main";

    /** 群机器人 webhook 地址（凭证，走环境变量） */
    private String webhookUrl;

    /** 签名密钥（凭证，走环境变量；为空则拒绝启动，不静默按无签名发送） */
    private String webhookSecret;

    /** 连接与读超时（毫秒）：webhook 挂起时不能把投递线程按住太久 */
    private int timeoutMs = 10_000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getAlias() {
        return alias;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }

    public String getWebhookUrl() {
        return webhookUrl;
    }

    public void setWebhookUrl(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    public String getWebhookSecret() {
        return webhookSecret;
    }

    public void setWebhookSecret(String webhookSecret) {
        this.webhookSecret = webhookSecret;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }
}
