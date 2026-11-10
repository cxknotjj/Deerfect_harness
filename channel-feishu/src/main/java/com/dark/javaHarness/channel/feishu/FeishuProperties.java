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

        /**
         * 指令授权：只有这个 open_id 能使用 {@code /new} {@code /help} {@code /agent} 等指令。
         *
         * <p>为空时取 {@link #allowedUsers} 的第一位（启动日志会注明回退了），
         * 白名单也为空时指令对所有人不可用——那个状态下消息本身就不会被处理。
         * 指令能在群里执行（重置的只是「这个人 + 这个群」的会话），所以不能让群里任何人
         * 都能重置别人的上下文。
         */
        private String commandUser;

        /** 入站队列容量：满了直接丢弃并记日志，不做无界堆积 */
        private int queueCapacity = 32;

        /** 单条消息的生成上限（秒）；超时放弃回复，但会话记忆仍会落库 */
        private int chatTimeoutSeconds = 300;

        /** 回复正文长度上限（字符）：超长截断，避免整条消息因体积超限发不出去 */
        private int replyMaxChars = 3000;

        /** 长任务回执：回复超过 receipt-delay-ms 仍未完成时，给原消息贴一个表情回应 */
        private boolean receiptReactionEnabled = true;

        /** 延迟阈值（毫秒）：低于它的快速回复完全不碰回执接口 */
        private long receiptDelayMs = 3000;

        /** 回执表情（飞书 emoji 类型名）；"OnIt" 是「在处理」 */
        private String receiptEmoji = "OnIt";

        /** 表情包：与 QQ 共用 emojis/ 数据，配置各自独立（渠道之间不互相依赖） */
        private Emoji emoji = new Emoji();

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

        public String getCommandUser() {
            return commandUser;
        }

        public void setCommandUser(String commandUser) {
            this.commandUser = commandUser;
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

        public int getReplyMaxChars() {
            return replyMaxChars;
        }

        public void setReplyMaxChars(int replyMaxChars) {
            this.replyMaxChars = replyMaxChars;
        }

        public boolean isReceiptReactionEnabled() {
            return receiptReactionEnabled;
        }

        public void setReceiptReactionEnabled(boolean receiptReactionEnabled) {
            this.receiptReactionEnabled = receiptReactionEnabled;
        }

        public long getReceiptDelayMs() {
            return receiptDelayMs;
        }

        public void setReceiptDelayMs(long receiptDelayMs) {
            this.receiptDelayMs = receiptDelayMs;
        }

        public String getReceiptEmoji() {
            return receiptEmoji;
        }

        public void setReceiptEmoji(String receiptEmoji) {
            this.receiptEmoji = receiptEmoji;
        }

        public Emoji getEmoji() {
            return emoji;
        }

        public void setEmoji(Emoji emoji) {
            this.emoji = emoji == null ? new Emoji() : emoji;
        }

        /**
         * 表情包配置。识别 LLM 回复末尾的 {@code [emoji:分类]} 标记，剥离后追加一张图片。
         *
         * <p>默认关闭（与其它渠道配置同一口径：类里的默认值不产生外部副作用，yaml 里开）。
         * 数据与 QQ 共用 {@code emojis/} 目录与索引，但配置键独立——渠道模块之间不能互相依赖。
         */
        public static class Emoji {

            /** 总开关；false 时只剥离标记（不把 {@code [emoji:happy]} 露给用户），不选图、不发送 */
            private boolean enabled;

            /** 表情包根目录（相对启动工作目录，支持绝对路径） */
            private String dir = "emojis";

            /** 表情名 → 图片文件名 JSON；缺失时自动创建空映射 */
            private String indexFile = "emojis/emoji-index.json";

            /** 分类命中后发送概率（0.0~1.0）；1.0 = 必发 */
            private double probability = 1.0;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public String getDir() {
                return dir;
            }

            public void setDir(String dir) {
                this.dir = dir;
            }

            public String getIndexFile() {
                return indexFile;
            }

            public void setIndexFile(String indexFile) {
                this.indexFile = indexFile;
            }

            public double getProbability() {
                return probability;
            }

            public void setProbability(double probability) {
                this.probability = probability;
            }
        }
    }
}
