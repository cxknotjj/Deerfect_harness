package com.dark.javaHarness.tool;

/**
 * 工具调用上下文（Spring AI ToolContext）的共享键：
 * 与轨迹标识同层下传，供需要会话范围的工具（如 recall_history）做服务端过滤。
 */
public final class ToolContextKeys {

    /** 当前调用所属会话 ID（服务端注入，不作为模型可见工具参数） */
    public static final String SESSION_ID = "sessionId";

    private ToolContextKeys() {
    }
}
