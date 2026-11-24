package com.dark.javaHarness.memory.mem0;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * mem0 记忆插件配置（app.memory.mem0.*）。
 *
 * <p>数值唯一来源是 application-agent.yaml 的 {@code app.memory.mem0} 块；本类只做绑定载体。
 * 密钥只从环境变量注入，不落库、不写日志。
 */
@Component
@ConfigurationProperties(prefix = "app.memory.mem0")
public class Mem0Properties {

    /** 总开关：false 时不装配客户端、不注入记忆段、不投递写入 */
    private boolean enabled;

    /** mem0 OSS REST 端点（无 /v1/ 前缀） */
    private String baseUrl;

    /** mem0 API Key（m0sk_…），来自 MEM0_API_KEY */
    private String apiKey;

    /** 是否允许检索（关闭后只写不读） */
    private boolean searchEnabled;

    /** 是否允许写入（关闭后只读不写） */
    private boolean writeEnabled;

    /** 单次召回条数 */
    private int topK;

    /** 注入段字符上限（超出截断） */
    private int maxChars;

    /** 连接超时（毫秒） */
    private int connectTimeoutMs;

    /** 读超时（毫秒） */
    private int readTimeoutMs;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public boolean isSearchEnabled() {
        return searchEnabled;
    }

    public void setSearchEnabled(boolean searchEnabled) {
        this.searchEnabled = searchEnabled;
    }

    public boolean isWriteEnabled() {
        return writeEnabled;
    }

    public void setWriteEnabled(boolean writeEnabled) {
        this.writeEnabled = writeEnabled;
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public int getMaxChars() {
        return maxChars;
    }

    public void setMaxChars(int maxChars) {
        this.maxChars = maxChars;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }
}
