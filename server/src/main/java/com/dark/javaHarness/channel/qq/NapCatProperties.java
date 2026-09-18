package com.dark.javaHarness.channel.qq;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.List;

/**
 * QQ 渠道（NapCat / OneBot 11）配置（napcat.*）。
 *
 * <p>数值唯一来源是 application.yaml（napcat 块）——本类只做绑定载体，零默认值；
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

    /** 回复输出控制：超长按段落边界分段发送（兼作单条输出上限）；渐进模式下多段回答逐条弹出 */
    public static class Reply {

        private int maxLength;
        private int splitChars;
        private boolean progressive;
        /** 动态条间延迟基础值（毫秒）：实际延迟 = 基础值 + 字数 × delay-factor + 随机扰动 */
        private int interChunkDelayMs;
        private int maxChunks;
        private double delayFactor;
        private int jitterRangeMs;
        private int maxDelayMs;

        public int getMaxLength() {
            return maxLength;
        }

        public void setMaxLength(int maxLength) {
            this.maxLength = maxLength;
        }

        /** 渐进切分粒度阈值（字）：块超过此字数就按下一层边界（\n → 。！？）继续切 */
        public int getSplitChars() {
            return splitChars;
        }

        public void setSplitChars(int splitChars) {
            this.splitChars = splitChars;
        }

        public boolean isProgressive() {
            return progressive;
        }

        public void setProgressive(boolean progressive) {
            this.progressive = progressive;
        }

        public int getInterChunkDelayMs() {
            return interChunkDelayMs;
        }

        public void setInterChunkDelayMs(int interChunkDelayMs) {
            this.interChunkDelayMs = interChunkDelayMs;
        }

        public int getMaxChunks() {
            return maxChunks;
        }

        public void setMaxChunks(int maxChunks) {
            this.maxChunks = maxChunks;
        }

        /** 动态延迟字数系数（秒/字）：0.1 = 每字停 100ms，模拟打字节奏；0 = 只用基础延迟 */
        public double getDelayFactor() {
            return delayFactor;
        }

        public void setDelayFactor(double delayFactor) {
            this.delayFactor = delayFactor;
        }

        /** 动态延迟随机扰动幅度（±毫秒）：叠加在基础值+字数延迟上，避免机械等间隔 */
        public int getJitterRangeMs() {
            return jitterRangeMs;
        }

        public void setJitterRangeMs(int jitterRangeMs) {
            this.jitterRangeMs = jitterRangeMs;
        }

        /** 动态延迟上限（毫秒）：防超长块（无标点长文/合并尾条）算出分钟级停顿；0 = 不设上限 */
        public int getMaxDelayMs() {
            return maxDelayMs;
        }

        public void setMaxDelayMs(int maxDelayMs) {
            this.maxDelayMs = maxDelayMs;
        }
    }

    /**
     * 表情包发送（napcat.emoji.*）：行为开关与路径配置（数值唯一来源仍是 application.yaml，
     * 零默认值）；情绪-表情映射表本身不放 yaml，独立为 index-file 指向的 JSON 文件。
     * {@code enabled=false} 时整个模块零行为（不读映射文件/目录、不检测、不发送）。
     */
    public static class Emoji {

        /** 总开关：false 时表情模块零行为 */
        private boolean enabled;

        /** 表情包根目录（相对启动工作目录，支持绝对路径）；图片由使用者自行放置，不进 git */
        private String dir;

        /** 情绪-表情映射表 JSON 路径（表情名 → {path, tags}）；缺失/损坏按空映射降级 */
        private String indexFile;

        /** 单次回复最多发送张数；0 = 不限制 */
        private int maxPerReply;

        /** 命中后发送概率（0.0~1.0）；1.0 = 必发，0 = 从不发送 */
        private double probability;

        /** 表情发送前停顿毫秒数（模拟「找图」节奏）；0 = 不等待 */
        private long sendDelayMs;

        /** 句尾 ~ / ！ / ! 兜底触发的默认表情名（须为映射表中已有的表情名） */
        private String cheerEmoji;

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

        public int getMaxPerReply() {
            return maxPerReply;
        }

        public void setMaxPerReply(int maxPerReply) {
            this.maxPerReply = maxPerReply;
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

        public String getCheerEmoji() {
            return cheerEmoji;
        }

        public void setCheerEmoji(String cheerEmoji) {
            this.cheerEmoji = cheerEmoji;
        }
    }

    /** 情绪-表情映射项（index-file JSON 单条结构，Jackson 绑定载体）：path 相对表情根目录 */
    public static class EmojiItem {

        /** 图片文件名（相对 dir） */
        private String path;

        /** 触发关键词（chunk 文本 contains 任一 tag 即命中） */
        private List<String> tags;

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public List<String> getTags() {
            return tags;
        }

        public void setTags(List<String> tags) {
            this.tags = tags;
        }
    }
}
