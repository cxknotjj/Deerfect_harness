package com.dark.javaHarness.voice;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Edge TTS 配置（app.tts.*）。
 *
 * <p>python 指向已安装 edge-tts 的解释器；本机使用独立 venv，容器内使用镜像自带的 python3。
 * 所有文本长度与并发限制在服务端生效，避免模型传入超长文本或并发拉起过多进程。
 */
@Component
@ConfigurationProperties(prefix = "app.tts")
public class TtsProperties {

    private boolean enabled;
    private String python;
    private String voice;
    private String rate;
    private String volume;
    private int maxChars;
    private int timeoutSeconds;
    private int maxConcurrent;
    private long artifactTtlSeconds;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getPython() {
        return python;
    }

    public void setPython(String python) {
        this.python = python;
    }

    public String getVoice() {
        return voice;
    }

    public void setVoice(String voice) {
        this.voice = voice;
    }

    public String getRate() {
        return rate;
    }

    public void setRate(String rate) {
        this.rate = rate;
    }

    public String getVolume() {
        return volume;
    }

    public void setVolume(String volume) {
        this.volume = volume;
    }

    public int getMaxChars() {
        return maxChars;
    }

    public void setMaxChars(int maxChars) {
        this.maxChars = maxChars;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public int getMaxConcurrent() {
        return maxConcurrent;
    }

    public void setMaxConcurrent(int maxConcurrent) {
        this.maxConcurrent = maxConcurrent;
    }

    public long getArtifactTtlSeconds() {
        return artifactTtlSeconds;
    }

    public void setArtifactTtlSeconds(long artifactTtlSeconds) {
        this.artifactTtlSeconds = artifactTtlSeconds;
    }
}
