package com.dark.javaHarness.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LLM 超时配置（app.chat.timeouts.*）：三处 LLM 通道超时的集中绑定载体。
 *
 * <p>数值唯一来源是 application.yaml（app.chat.timeouts 块）——本类只做绑定载体，
 * 字段保持 Integer 且无初始化值：未配置即为 null，由消费方按各自默认秒数兜底
 * （connect/read 经 {@link com.dark.javaHarness.config.agent.ChatClientFactory#resolve}，
 * stream-idle 兜底在 AgentChatCaller），未配置时行为与历史硬编码值完全一致
 * （连接 10s / 读 300s / 流式空闲 120s）。
 */
@Component
@ConfigurationProperties(prefix = "app.chat.timeouts")
public class ChatTimeoutProperties {

    /** HTTP 连接超时（秒）：第三方端点不可达时快速失败；null = 兜底 10 秒 */
    private Integer connectTimeoutSeconds;

    /** HTTP 读超时（秒）：阻塞调用通道 LLM 生成最长等待；null = 兜底 300 秒 */
    private Integer readTimeoutSeconds;

    /** 流式调用空闲超时（秒）：相邻信号间隔超过即判定端点挂起；null = 兜底 120 秒 */
    private Integer streamIdleTimeoutSeconds;

    /** 路由判定读超时（秒）：route-judge 是带 SIMPLE 兜底的轻量内部调用，
     * 不共用 300s 长回答读超时；null = 兜底 10 秒 */
    private Integer judgeReadTimeoutSeconds;

    public Integer getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public void setConnectTimeoutSeconds(Integer connectTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }

    public Integer getReadTimeoutSeconds() {
        return readTimeoutSeconds;
    }

    public void setReadTimeoutSeconds(Integer readTimeoutSeconds) {
        this.readTimeoutSeconds = readTimeoutSeconds;
    }

    public Integer getStreamIdleTimeoutSeconds() {
        return streamIdleTimeoutSeconds;
    }

    public Integer getJudgeReadTimeoutSeconds() {
        return judgeReadTimeoutSeconds;
    }

    public void setJudgeReadTimeoutSeconds(Integer judgeReadTimeoutSeconds) {
        this.judgeReadTimeoutSeconds = judgeReadTimeoutSeconds;
    }

    public void setStreamIdleTimeoutSeconds(Integer streamIdleTimeoutSeconds) {
        this.streamIdleTimeoutSeconds = streamIdleTimeoutSeconds;
    }
}
