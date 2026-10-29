package com.dark.javaHarness.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Agentic SIMPLE 路径配置（app.agentic.*）：路径 A 由 AgentScope ReActAgent 执行，
 * 路径 B（多 Agent 编排）不受影响。
 *
 * <p>数值唯一来源是 application-agent.yaml 的 app.agentic 块；本类只做绑定载体。
 */
@Component
@ConfigurationProperties(prefix = "app.agentic")
public class AgenticProperties {

    /** 是否启用 AgentScope ReAct 执行 SIMPLE 路径（false 回退既有 ChatClient 单次调用路径） */
    private boolean enabled = true;

    /** ReAct 最大迭代次数：工具调用上限 + 最终回答一轮，默认与 tool-call-limit=4 对齐为 5 */
    private int maxIters = 5;

    /** 模型调用最大尝试次数（AgentScope ExecutionConfig，含首次） */
    private int maxAttempts = 3;

    /** AgentScope 初始化失败时是否回退既有 ChatClient 路径（流已开始后失败不切换） */
    private boolean fallbackEnabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxIters() {
        return maxIters;
    }

    public void setMaxIters(int maxIters) {
        this.maxIters = maxIters;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public boolean isFallbackEnabled() {
        return fallbackEnabled;
    }

    public void setFallbackEnabled(boolean fallbackEnabled) {
        this.fallbackEnabled = fallbackEnabled;
    }
}
