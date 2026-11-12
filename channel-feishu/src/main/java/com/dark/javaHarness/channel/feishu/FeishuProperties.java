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

        /**
         * 群聊回复模式：{@code all} 每条都回 / {@code mention} 只在 @ 机器人时回 /
         * {@code probability} 按 {@link #replyProbability} 随机回。
         * 单聊不受影响；指令也不受限制（显式操作不该被概率吞掉）。
         */
        private String replyMode = "all";

        /** probability 模式的放行概率（0.0~1.0） */
        private double replyProbability = 1.0;

        /** 群聊回复是否 @ 提问者：多人群里一眼看出在回谁 */
        private boolean atAsker;

        /** 是否用引用回复（挂到原消息下）；原消息不可引用时自动退回普通发送 */
        private boolean quoteReply = true;

        /** 引用回复是否走话题（thread）；默认 false 只是引用块 */
        private boolean replyInThread;

        /** 长任务回执：回复超过 receipt-delay-ms 仍未完成时，给原消息贴一个表情回应 */
        private boolean receiptReactionEnabled = true;

        /** 延迟阈值（毫秒）：低于它的快速回复完全不碰回执接口 */
        private long receiptDelayMs = 3000;

        /** 回执表情（飞书 emoji 类型名）；"OnIt" 是「在处理」 */
        private String receiptEmoji = "OnIt";

        /** 语音回复：消费 core 的 voice:<id> flag，把语音工件转成飞书语音条 */
        private Voice voice = new Voice();

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

        public String getReplyMode() {
            return replyMode;
        }

        public void setReplyMode(String replyMode) {
            this.replyMode = replyMode;
        }

        public double getReplyProbability() {
            return replyProbability;
        }

        public void setReplyProbability(double replyProbability) {
            this.replyProbability = replyProbability;
        }

        public boolean isAtAsker() {
            return atAsker;
        }

        public void setAtAsker(boolean atAsker) {
            this.atAsker = atAsker;
        }

        public boolean isQuoteReply() {
            return quoteReply;
        }

        public void setQuoteReply(boolean quoteReply) {
            this.quoteReply = quoteReply;
        }

        public boolean isReplyInThread() {
            return replyInThread;
        }

        public void setReplyInThread(boolean replyInThread) {
            this.replyInThread = replyInThread;
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

        public Voice getVoice() {
            return voice;
        }

        public void setVoice(Voice voice) {
            this.voice = voice;
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
        /**
         * 语音回复：飞书语音条只认 opus，core 的 TTS 出的是 mp3，需要 ffmpeg 转一道
         * （再取时长作为上传参数）。运行环境没有 ffmpeg 时本条退回文字，不影响其它消息。
         */
        public static class Voice {

            /** 开关：false 时忽略 voice:<id> flag，按普通文字回复 */
            private boolean enabled = true;

            /** ffmpeg 可执行文件：PATH 里有就写 ffmpeg，否则给绝对路径（镜像里通常如此） */
            private String ffmpeg = "ffmpeg";

            /** 转码超时（秒）：卡住的子进程必须杀掉，不能拖住回复线程 */
            private int timeoutSeconds = 15;

            /** opus 码率：语音够用即可，32k 单声道一分钟约 240KB */
            private String bitrate = "32k";

            /** 声道数：语音 1 就够；音乐要 2 */
            private int channels = 1;

            /** 音乐参数：同一个工件库里的 format=music 走这套（码率与声道都不同） */
            private Music music = new Music();

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public String getFfmpeg() {
                return ffmpeg;
            }

            public void setFfmpeg(String ffmpeg) {
                this.ffmpeg = ffmpeg;
            }

            public int getTimeoutSeconds() {
                return timeoutSeconds;
            }

            public void setTimeoutSeconds(int timeoutSeconds) {
                this.timeoutSeconds = timeoutSeconds;
            }

            public int getChannels() {
                return channels;
            }

            public void setChannels(int channels) {
                this.channels = channels;
            }

            public Music getMusic() {
                return music;
            }

            public void setMusic(Music music) {
                this.music = music;
            }

            public String getBitrate() {
                return bitrate;
            }

            public void setBitrate(String bitrate) {
                this.bitrate = bitrate;
            }
        }

        /**
         * 音乐参数：音乐走二次有损编码，码率太低会明显发闷，所以单列一套。
         * 96k 立体声约 44 分钟能塞满飞书 30MB 上限，够放整张专辑。
         */
        public static class Music {

            /** opus 码率（音乐） */
            private String bitrate = "96k";

            /** 声道数（音乐固定立体声；飞书对立体声 opus 的播放表现需实机确认） */
            private int channels = 2;

            public String getBitrate() {
                return bitrate;
            }

            public void setBitrate(String bitrate) {
                this.bitrate = bitrate;
            }

            public int getChannels() {
                return channels;
            }

            public void setChannels(int channels) {
                this.channels = channels;
            }
        }

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
