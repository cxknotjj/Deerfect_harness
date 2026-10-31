package com.dark.javaHarness.channel.qq.client;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * QQ 渠道（NapCat / OneBot 11）配置（napcat.*）。
 *
 * <p>数值唯一来源是 application-qq.yaml（napcat 块，自 application.yaml 经
 * spring.config.import 引入）——本类只做绑定载体，零默认值；
 * token/secret 走 {@code ${ENV_VAR:}} 环境变量不落 git。{@code enabled=false} 时
 * NapCatChannelConfig 整链不装配（无端点、无线程池、无出站客户端）。
 */
@Component
@ConfigurationProperties(prefix = "napcat")
public class NapCatProperties {

    /** 渠道总开关（false 时 channel/qq 全链不装配，/onebot/event 端点不存在） */
    private boolean enabled;

    /** NapCat HTTP API 地址（onebot11 httpServers 的 host:port） */
    private String apiBaseUrl;

    /** 下行 API 鉴权 token（与 onebot11 httpServers.token 一致；空则不带鉴权头） */
    private String apiToken;

    /** 机器人自身 QQ 号（自消息过滤 + 群聊 @ 触发判据） */
    private String selfId;

    /** 回复使用的 Agent（agent 表主键）；空 = 默认。会话绑定该 Agent 后仍走统一路由判定（SIMPLE 直答 / COMPLEX 编排）；其 knowledge 绑定自动生效 */
    private Long agentId;

    /** 上报验签密钥（与 onebot11 httpClients.token 一致；空 = 不校验，联调期先留空） */
    private String eventSecret;

    /** 单条消息聊天超时秒数：超时放弃回复且静默（后台跑完仅落会话记忆），防 LLM 卡死占满处理线程；0 = 不限制 */
    private int chatTimeoutSeconds;

    /** 私聊白名单（QQ 号 CSV）：非空时仅名单内用户可私聊（防陌生人刷 LLM token）；空 = 不限制 */
    private String privateAllowUsers;

    private final GroupTrigger groupTrigger = new GroupTrigger();
    private final RateLimit rateLimit = new RateLimit();
    private final Reply reply = new Reply();
    private final Emoji emoji = new Emoji();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getApiBaseUrl() {
        return apiBaseUrl;
    }

    public void setApiBaseUrl(String apiBaseUrl) {
        this.apiBaseUrl = apiBaseUrl;
    }

    public String getApiToken() {
        return apiToken;
    }

    public void setApiToken(String apiToken) {
        this.apiToken = apiToken;
    }

    public String getSelfId() {
        return selfId;
    }

    public void setSelfId(String selfId) {
        this.selfId = selfId;
    }

    public Long getAgentId() {
        return agentId;
    }

    public void setAgentId(Long agentId) {
        this.agentId = agentId;
    }

    public String getEventSecret() {
        return eventSecret;
    }

    public void setEventSecret(String eventSecret) {
        this.eventSecret = eventSecret;
    }

    public int getChatTimeoutSeconds() {
        return chatTimeoutSeconds;
    }

    public void setChatTimeoutSeconds(int chatTimeoutSeconds) {
        this.chatTimeoutSeconds = chatTimeoutSeconds;
    }

    public String getPrivateAllowUsers() {
        return privateAllowUsers;
    }

    public void setPrivateAllowUsers(String privateAllowUsers) {
        this.privateAllowUsers = privateAllowUsers;
    }

    public GroupTrigger getGroupTrigger() {
        return groupTrigger;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public Reply getReply() {
        return reply;
    }

    public Emoji getEmoji() {
        return emoji;
    }

    /** 群聊触发方式：at=仅@机器人 | prefix=命令前缀 | all=全部响应 */
    public static class GroupTrigger {

        private String mode;

        /** prefix 模式的命令前缀 */
        private String prefix;

        /** 触发方式为 at 时：回复机器人消息（入站 reply 段指向机器人已发消息）是否也触发 */
        private boolean replyToBot;

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public String getPrefix() {
            return prefix;
        }

        public void setPrefix(String prefix) {
            this.prefix = prefix;
        }

        public boolean isReplyToBot() {
            return replyToBot;
        }

        public void setReplyToBot(boolean replyToBot) {
            this.replyToBot = replyToBot;
        }
    }

    /** 群聊防风控限频：同一用户回复最小间隔秒数；0 = 不限制（项目口径） */
    public static class RateLimit {

        private int perUserSeconds;

        public int getPerUserSeconds() {
            return perUserSeconds;
        }

        public void setPerUserSeconds(int perUserSeconds) {
            this.perUserSeconds = perUserSeconds;
        }
    }

    /** 回复输出控制：整段单条发送，超长按 maxLength 截断 */
    public static class Reply {

        /** 回复长度上限（字）：超出部分截断；0 = 不限制 */
        private int maxLength;
        /** 群聊回复首条是否 @ 提问者（防刷屏，默认关；详见 application-qq.yaml） */
        private boolean atAsker;

        public int getMaxLength() {
            return maxLength;
        }

        public void setMaxLength(int maxLength) {
            this.maxLength = maxLength;
        }

        public boolean isAtAsker() {
            return atAsker;
        }

        public void setAtAsker(boolean atAsker) {
            this.atAsker = atAsker;
        }
    }

    /**
     * 表情包发送（napcat.emoji.*）：行为开关与路径配置（数值唯一来源仍是 application-qq.yaml，
     * 零默认值）；分类-图片映射表本身不放 yaml，独立为 index-file 指向的 JSON 文件。
     * {@code enabled=false} 时整个模块零行为（不读映射文件/目录、不检测、不发送）。
     */
    public static class Emoji {

        /** 总开关：false 时表情模块零行为 */
        private boolean enabled;

        /** 表情包根目录（相对启动工作目录，支持绝对路径）；图片由使用者自行放置，不进 git */
        private String dir;

        /** 表情名 → 图片文件 的映射 JSON（表情名前缀即分类，如 happy_01）；缺失/损坏按空映射降级 */
        private String indexFile;

        /** 触发分类后发送概率（0.0~1.0）；1.0 = 必发，0 = 从不发送 */
        private double probability;

        /** 表情发送前停顿毫秒数（模拟「找图」节奏）；0 = 不等待 */
        private long sendDelayMs;

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

        public long getSendDelayMs() {
            return sendDelayMs;
        }

        public void setSendDelayMs(long sendDelayMs) {
            this.sendDelayMs = sendDelayMs;
        }
    }
}
