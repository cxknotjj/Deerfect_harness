package com.dark.javaHarness.tool;

import java.util.function.Consumer;

/**
 * 工具装饰上下文：单次请求内装饰器链共享的只读差异项，
 * 由 AgentRequestSpecFactory 在装饰前构造一次。
 *
 * @param agentName 调用方角色（观测记录的 agent_name 列 / load_skill 可见性键）
 * @param sessionId 会话 ID（懒加载展开键 / 观测记录的 session_id 列）
 * @param emitter   SSE 进度行发射器（无 SSE 链路为 null，如 QQ 渠道/API 直调）
 * @param contextWindow 本次调用命中模型的上下文窗口（0 = 未声明，预算装饰器不做窗口封顶）
 * @param maxOutput 本次调用的输出上限档位（窗口守卫扣除输出预留用）
 */
public record ToolDecorationContext(String agentName,
                                    String sessionId,
                                    Consumer<String> emitter,
                                    int contextWindow,
                                    int maxOutput) {

    /** 无窗口信息场景（单测/旧调用链）：窗口 0 = 不封顶，行为与引入守卫前一致 */
    public ToolDecorationContext(String agentName, String sessionId, Consumer<String> emitter) {
        this(agentName, sessionId, emitter, 0, 0);
    }
}
