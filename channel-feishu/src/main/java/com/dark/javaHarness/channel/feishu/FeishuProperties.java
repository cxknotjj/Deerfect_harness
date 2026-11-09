package com.dark.javaHarness.channel.feishu;

import java.util.ArrayList;
import java.util.List;
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

    /** M3b 事件订阅（长连接）配置；与出站 webhook 独立开关 */
    private Ws ws = new Ws();

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

    public Ws getWs() {
        return ws;
    }

    public void setWs(Ws ws) {
        this.ws = ws;
    }

    /**
     * 长连接（事件订阅）配置。
     *
     * <p>与 {@code channel.feishu.enabled} 分开：出站 webhook 只用 URL + secret，
     * 入站事件必须建自建应用拿 App ID / Secret。两者可以只开一个。
     */
    public static class Ws {

        /** 入站总开关（false 时不建立任何 WS 连接） */
        private boolean enabled;

        /** 自建应用 App ID（{@code FEISHU_APP_ID}） */
        private String appId;

        /** 自建应用 App Secret（{@code FEISHU_APP_SECRET}） */
        private String appSecret;

        /** 事件加密密钥：控制台开启加密时必填，否则留空 */
        private String encryptKey;

        /**
         * open_id 白名单。
         *
         * <p>为空时进入「配对模式」：只把收到消息的 open_id 打进日志，不回复、不调模型。
         * 长连接没有回调端点，验签由 SDK 的 App 凭据握手完成，白名单是第二道闸——
         * 机器人被拉进陌生群时，不至于对所有人开口。
         */
        private List<String> allowedUsers = new ArrayList<>();

        /** 入站队列容量：满了直接丢弃并记日志，不做无界堆积 */
        private int queueCapacity = 32;

        /** 单条消息的生成上限（秒）；超时放弃回复，但会话记忆仍会落库 */
        private int chatTimeoutSeconds = 300;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getAppId() {
            return appId;
        }

        public void setAppId(String appId) {
            this.appId = appId;
        }

        public String getAppSecret() {
            return appSecret;
        }

        public void setAppSecret(String appSecret) {
            this.appSecret = appSecret;
        }

        public String getEncryptKey() {
            return encryptKey;
        }

        public void setEncryptKey(String encryptKey) {
            this.encryptKey = encryptKey;
        }

        public List<String> getAllowedUsers() {
            return allowedUsers;
        }

        public void setAllowedUsers(List<String> allowedUsers) {
            this.allowedUsers = allowedUsers == null ? new ArrayList<>() : allowedUsers;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }

        public int getChatTimeoutSeconds() {
            return chatTimeoutSeconds;
        }

        public void setChatTimeoutSeconds(int chatTimeoutSeconds) {
            this.chatTimeoutSeconds = chatTimeoutSeconds;
        }
    }
}
